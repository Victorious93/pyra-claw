#!/usr/bin/env python3
"""Manual USB serial provisioning for Pyra Claw ESP32 nodes (contract v1).

See docs/PROVISIONING.md. Requires pyserial: pip install pyserial

Examples:
  python tools/provision.py /dev/ttyUSB0 hello
  python tools/provision.py /dev/ttyUSB0 get
  python tools/provision.py /dev/ttyUSB0 provision ssid=MyWifi pass=secret broker_port=1883
  python tools/provision.py /dev/ttyUSB0 provision mqtt_user=pyra-node mqtt_pass=... hub_token=...
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


def parse_kv(pairs: list[str]) -> dict:
    cfg: dict = {}
    for p in pairs:
        if "=" not in p:
            raise SystemExit(f"expected key=value, got {p!r}")
        k, v = p.split("=", 1)
        cfg[k] = int(v) if k in INT_KEYS else v
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
