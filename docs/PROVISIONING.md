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
| `hub_token` | string, secret | `""` | stored for fleet use; not consumed by firmware yet |

Values in NVS (Preferences namespace `fleet`) override `config.h` defaults.
Changes are saved immediately but take effect after `reboot`.

## Commands
### hello
```
> {"v":1,"cmd":"hello"}
< {"v":1,"ok":true,"project":"pyra-claw","fw":"0.2.0","chip":"esp32","keys":["ssid","pass","broker","broker_port","node_id","mqtt_user","mqtt_pass","pc_mac","pc_broadcast","hub_token"]}
```
### get
Secrets (`pass`, `mqtt_pass`, `hub_token`) are always returned as `"***"`.
```
> {"v":1,"cmd":"get"}
< {"v":1,"ok":true,"cfg":{"ssid":"home","pass":"***","broker":"192.168.4.1","broker_port":1883,"node_id":"esp32-wake-01","mqtt_user":"","mqtt_pass":"***","pc_mac":"AA:BB:CC:DD:EE:FF","pc_broadcast":"255.255.255.255","hub_token":"***"}}
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

## Host script
`tools/provision.py` (needs `pip install pyserial`):
```
python tools/provision.py /dev/ttyUSB0 hello --wait-boot
python tools/provision.py /dev/ttyUSB0 provision ssid=home pass=hunter22 broker=192.168.4.1 broker_port=1883 pc_mac=AA:BB:CC:DD:EE:01
python tools/provision.py /dev/ttyUSB0 reboot
```
