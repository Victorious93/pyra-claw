# USB serial provisioning (fleet contract v1)

> ⚠️ **UNTESTED ON HARDWARE.** Compiles for `esp32dev`; not yet verified on a real board.

The ESP32 wake node (`firmware/esp32-wake`) can be configured over its USB serial
port without rebuilding the firmware.

## Transport
- USB serial, **115200 8N1**.
- **JSON lines**: one JSON object per line, terminated by `\n` (`\r` is ignored).
- The device may also print non-JSON log lines; hosts must skip them.
- Lines that are not valid JSON, or have no `"v":1`, are ignored silently.

## Accepting window
Commands are only handled:
- during the **first 10 s after boot**, or
- **at any time while no Wi-Fi `ssid` is configured**.

Opening the port on most ESP32 dev boards resets the chip, which opens the window.

## Config keys
| Key | Type | Default | Notes |
|---|---|---|---|
| `ssid` | string | `WIFI_SSID` | max 32 chars; empty keeps the window open |
| `pass` | string, secret | `WIFI_PASS` | max 63 chars |
| `broker` | string | `MQTT_HOST` | MQTT host/IP (Pi hub) |
| `broker_port` | int | `1883` | 1-65535 |
| `node_id` | string | `NODE_ID` | non-empty; used in `pyra/node/<id>/...` topics |
| `mqtt_user` | string | `MQTT_USER` | empty = anonymous |
| `mqtt_pass` | string, secret | `MQTT_PASS` | |
| `pc_mac` | string | `PC_MAC` | `AA:BB:CC:DD:EE:FF`, validated, stored upper-case |
| `pc_broadcast` | string | `255.255.255.255` | IPv4, validated; WOL target |
| `ntp` | string | `NTP_SERVER` (`""`) | v0.4. NTP server tried first, max 63 chars, no spaces. Empty = use `broker` (the Pi runs chrony after `install.sh`). Fallbacks: `pool.ntp.org`, `time.google.com` |
| `usb_pins` | string | `USB_PINS` (`""`) | v0.4. Comma-separated GPIOs, one per switched USB 5 V channel (channel 0 = first), max 4, e.g. `"27,32,33"`. Empty = no channels. Allowed: 4, 13, 18, 19, 21, 22, 23, 25, 27, 32, 33 (not the pulse pin). Anything else is `bad_value:usb_pins` |
| `wake_token` | string, secret | `""` | v0.4. Optional bearer accepted by HTTP `POST /wake` only, for the phone app's **Direct ESP32** mode. Min 16 chars (empty = off). It **cannot** sign pulses or USB-off, so the phone never needs `hub_token`. `install.sh` generates `NODE_WAKE_TOKEN` |
| `hub_token` | string, secret | `HUB_TOKEN` (`""`) | shared HMAC key for signed `power_pulse` and bearer token for HTTP `/wake` (protocol v0.2). Must equal hub `[node] hub_token`. Empty = pulses and `/wake` refused |

Values in NVS (Preferences namespace `fleet`) override `config.h` defaults.
The firmware also keeps `usb_state` (a bitmask of which USB channels are on) in the same
namespace; it is not a provisioning key and `factory_reset` clears it (all channels on).

### Which GPIOs are safe for `usb_pins`
| GPIO | Use for a USB channel? | Why |
|---|---|---|
| 27, 32, 33, 25, 13, 4 | **yes** (27/32/33 recommended) | plain outputs, no boot-time role |
| 18, 19, 21, 22, 23 | yes | fine unless you use SPI (18/19/23) or I2C (21/22) |
| 26 | no | the PC817 power-pulse pin |
| 0, 2, 5, 12, 15 | **no** | strapping pins: a load on them can stop the board booting or flashing (12 sets flash voltage) |
| 6–11 | **no** | wired to the flash chip |
| 1, 3 | no | USB serial (provisioning) |
| 14 | no | outputs a PWM signal during boot, could flicker a line |
| 16, 17 | no | used by PSRAM on WROVER modules |
| 34–39 | **no** | input only |
Changes are saved immediately but take effect after `reboot`.

## Commands
### hello
```
> {"v":1,"cmd":"hello"}
< {"v":1,"ok":true,"project":"pyra-claw","fw":"0.4.0","chip":"esp32","keys":["ssid","pass","broker","broker_port","node_id","mqtt_user","mqtt_pass","pc_mac","pc_broadcast","hub_token","ntp","usb_pins","wake_token"]}
```
### get
Secrets (`pass`, `mqtt_pass`, `hub_token`, `wake_token`) are always returned as `"***"`.
```
> {"v":1,"cmd":"get"}
< {"v":1,"ok":true,"cfg":{"ssid":"home","pass":"***","broker":"192.168.4.1","broker_port":1883,"node_id":"esp32-wake-01","mqtt_user":"","mqtt_pass":"***","pc_mac":"AA:BB:CC:DD:EE:FF","pc_broadcast":"255.255.255.255","hub_token":"***","ntp":"","usb_pins":"27,32,33","wake_token":"***"}}
```
### provision
Partial updates are allowed. The whole object is validated first; if any key is
invalid nothing is written.
```
> {"v":1,"cmd":"provision","cfg":{"ssid":"home","pass":"hunter22","pc_mac":"aa:bb:cc:dd:ee:01"}}
< {"v":1,"ok":true}
> {"v":1,"cmd":"provision","cfg":{"broker_port":"1883"}}
< {"v":1,"ok":false,"err":"bad_value:broker_port"}
```
Errors: `cfg_not_object`, `unknown_key:<k>`, `bad_type:<k>`, `bad_value:<k>`, `nvs_open_failed`.

### reboot / factory_reset
Reply is sent before acting. `factory_reset` clears the `fleet` namespace, then reboots
(the node falls back to `config.h` defaults).
```
> {"v":1,"cmd":"reboot"}
< {"v":1,"ok":true}
> {"v":1,"cmd":"factory_reset"}
< {"v":1,"ok":true}
```
### unknown command
```
> {"v":1,"cmd":"dance"}
< {"v":1,"ok":false,"err":"unknown_cmd"}
```

## Where the hub token and MQTT credentials come from
`sudo ./hub/install.sh` generates them once and never overwrites them:
- `/etc/pyra-claw/node-credentials` (root only): `NODE_MQTT_USER`, `NODE_MQTT_PASS`, `NODE_HUB_TOKEN`, `NODE_WAKE_TOKEN` (v0.4, added on re-run if missing), plus the hub's own MQTT login. Only `NODE_WAKE_TOKEN` belongs on a phone.
- The hub's copy of the token is `[node] hub_token` in `/etc/pyra-claw/config.toml`.

Provision the node with them:
```
sudo bash -c 'source /etc/pyra-claw/node-credentials; python3 tools/provision.py /dev/ttyUSB0 provision \
  mqtt_user="$NODE_MQTT_USER" mqtt_pass="$NODE_MQTT_PASS" hub_token="$NODE_HUB_TOKEN" wake_token="$NODE_WAKE_TOKEN"'
python3 tools/provision.py /dev/ttyUSB0 reboot
```
To rotate the token, change it in both places (config.toml then provision), and restart `pyra-hub`.

## Host script
`tools/provision.py` (needs `pip install pyserial`):
```
python tools/provision.py /dev/ttyUSB0 hello --wait-boot
python tools/provision.py /dev/ttyUSB0 provision ssid=home pass=hunter22 broker=192.168.4.1 broker_port=1883 pc_mac=AA:BB:CC:DD:EE:01
python tools/provision.py /dev/ttyUSB0 provision usb_pins=27,32,33          # 3 USB channels
python tools/provision.py /dev/ttyUSB0 provision ntp=192.168.4.1           # only if the Pi is not the broker
python tools/provision.py /dev/ttyUSB0 reboot
```
`provision.py` checks `usb_pins` and `ntp` before sending, with the same rules as the firmware.
