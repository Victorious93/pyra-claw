#!/usr/bin/env python3
"""Manual USB serial provisioning for Pyra Claw ESP32 nodes (contract v1).

See docs/PROVISIONING.md. Requires pyserial: pip install pyserial

Examples:
  python tools/provision.py /dev/ttyUSB0 hello
  python tools/provision.py /dev/ttyUSB0 get
  python tools/provision.py /dev/ttyUSB0 provision ssid=MyWifi pass=secret broker_port=1883
  python tools/provision.py /dev/ttyUSB0 provision mqtt_user=pyra-node mqtt_pass=... hub_token=...
  python tools/provision.py /dev/ttyUSB0 provision usb_pins=27,32,33 ntp=192.168.4.1
  python tools/provision.py /dev/ttyUSB0 reboot
  python tools/provision.py /dev/ttyUSB0 factory_reset
"""

from __future__ import annotations

import argparse
import json
import sys
import time

import serial  # pyserial

INT_KEYS = {"broker_port"}
# Must match USB_SAFE_PINS / PULSE_PIN in firmware/esp32-wake/src/main.cpp.
USB_SAFE_PINS = {4, 13, 18, 19, 21, 22, 23, 25, 27, 32, 33}
USB_MAX_CH = 4


def check_usb_pins(v: str) -> str | None:
    """Return an error string, or None if `v` is a valid usb_pins value ("" = no channels)."""
    if v == "":
        return None
    parts = v.rstrip(",").split(",")
    try:
        pins = [int(p) for p in parts]
    except ValueError:
        return f"usb_pins must be comma-separated GPIO numbers, got {v!r}"
    if len(pins) > USB_MAX_CH:
        return f"usb_pins: at most {USB_MAX_CH} channels"
    if len(set(pins)) != len(pins):
        return "usb_pins: duplicate GPIO"
    bad = [p for p in pins if p not in USB_SAFE_PINS]
    if bad:
        return f"usb_pins: GPIO {bad} not allowed; use {sorted(USB_SAFE_PINS)} (see PROVISIONING.md)"
    return None


def parse_kv(pairs: list[str]) -> dict:
    cfg: dict = {}
    for p in pairs:
        if "=" not in p:
            raise SystemExit(f"expected key=value, got {p!r}")
        k, v = p.split("=", 1)
        cfg[k] = int(v) if k in INT_KEYS else v
    err = check_usb_pins(cfg["usb_pins"]) if "usb_pins" in cfg else None
    if "wake_token" in cfg and 0 < len(cfg["wake_token"]) < 16:
        err = "wake_token: at least 16 characters (or empty to disable)"
    if "ntp" in cfg and (len(cfg["ntp"]) > 63 or " " in cfg["ntp"]):
        err = "ntp: host or IP, max 63 chars, no spaces"
    if err:
        raise SystemExit(err)
    return cfg


def request(port: serial.Serial, msg: dict, timeout: float) -> dict | None:
    port.reset_input_buffer()
    port.write((json.dumps(msg, separators=(",", ":")) + "\n").encode())
    port.flush()
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        raw = port.readline().decode("utf-8", "replace").strip()
        if not raw:
            continue
        try:
            obj = json.loads(raw)
        except json.JSONDecodeError:
            print(f"[log] {raw}", file=sys.stderr)  # non-JSON log line
            continue
        if isinstance(obj, dict) and obj.get("v") == 1 and "ok" in obj:
            return obj
    return None


def main() -> int:
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawTextHelpFormatter
    )
    ap.add_argument("port")
    ap.add_argument(
        "cmd", choices=["hello", "get", "provision", "reboot", "factory_reset"]
    )
    ap.add_argument("kv", nargs="*", help="key=value pairs for provision")
    ap.add_argument("--baud", type=int, default=115200)
    ap.add_argument("--timeout", type=float, default=3.0)
    ap.add_argument(
        "--wait-boot",
        action="store_true",
        help="opening the port usually resets the board; wait 1.5 s for boot",
    )
    a = ap.parse_args()

    msg: dict = {"v": 1, "cmd": a.cmd}
    if a.cmd == "provision":
        msg["cfg"] = parse_kv(a.kv)
        if not msg["cfg"]:
            raise SystemExit("provision needs at least one key=value")

    with serial.Serial(a.port, a.baud, timeout=0.2) as port:
        if a.wait_boot:
            time.sleep(1.5)
        resp = request(port, msg, a.timeout)
    if resp is None:
        print(
            "no reply (provisioning window is the first 10 s after boot, or while no ssid is set)"
        )
        return 2
    print(json.dumps(resp, indent=2))
    return 0 if resp.get("ok") else 1


if __name__ == "__main__":
    sys.exit(main())
