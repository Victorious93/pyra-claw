"""Pluggable charge / power backends for downstream devices (protocol v0.3).

UNTESTED ON HARDWARE. Each configured device maps to a backend for charging and,
optionally, a separate backend for power on/off. See docs/MANUAL.md "Timers".

Backends:
- ``uhubctl``: per-port VBUS switching on an external hub that really supports
  per-port power switching (PPPS). The Pi 5 onboard ports are ganged (one VBUS
  switch for all four), so pointing this at the Pi's own hubs cuts every port.
- ``adb``: talks to the phone over adb (USB or TCP) from the Pi. Charging is toggled
  through a root-only sysfs/debugfs switch chosen by ``profile``; power-off is
  ``reboot -p``. Power-on is not possible over adb (the phone is off).
- ``esp32_relay``: ESP32 GPIO driving a relay/MOSFET on each USB 5V line, relayed
  over MQTT as ``{"action":"usb_power","channel":N,"on":bool}`` (firmware >= 0.4,
  protocol v0.4). ``on:false`` carries an HMAC signature, see signing.py.
- ``mock``: records calls in memory, for tests and dry runs.
"""

from __future__ import annotations

import subprocess
import uuid
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from typing import Any

from .signing import signed_usb_off

Runner = Callable[[Sequence[str], float], subprocess.CompletedProcess]


def default_runner(argv: Sequence[str], timeout: float) -> subprocess.CompletedProcess:
    return subprocess.run(list(argv), capture_output=True, text=True, timeout=timeout, check=False)


class BackendError(RuntimeError):
    pass


# Root sysfs charge switches per phone model. Sources: Chromium catapult/devil
# battery_utils.py device profiles (Nexus 5/6/9) and community reports for
# battery_charging_enabled. [Likely] for Nexus 5/6 (used by Chromium's perf lab),
# [Guessing] for "generic" (path differs per kernel). Values: (enable, disable).
ADB_CHARGE_PROFILES: dict[str, tuple[str, str]] = {
    "nexus5": (
        "echo 0x4A > /sys/kernel/debug/bq24192/INPUT_SRC_CONT && "
        "chmod 644 /sys/class/power_supply/usb/online && "
        "echo 1 > /sys/class/power_supply/usb/online && dumpsys battery reset",
        "echo 0xCA > /sys/kernel/debug/bq24192/INPUT_SRC_CONT && "
        "chmod 644 /sys/class/power_supply/usb/online && "
        "echo 0 > /sys/class/power_supply/usb/online && "
        "dumpsys battery set ac 0 && dumpsys battery set usb 0",
    ),
    "nexus6": (
        "echo 1 > /sys/class/power_supply/battery/charging_enabled && dumpsys battery reset",
        "echo 0 > /sys/class/power_supply/battery/charging_enabled && "
        "dumpsys battery set ac 0 && dumpsys battery set usb 0",
    ),
    "generic": (
        "echo 1 > /sys/class/power_supply/battery/battery_charging_enabled",
        "echo 0 > /sys/class/power_supply/battery/battery_charging_enabled",
    ),
}


class Backend:
    name = "base"
    supports_power_on = False

    def charge(self, device_id: str, on: bool) -> dict[str, Any]:
        raise BackendError(f"{self.name}: charge control not supported")

    def power_on(self, device_id: str) -> dict[str, Any]:
        raise BackendError(f"{self.name}: power_on not supported")

    def power_off(self, device_id: str) -> dict[str, Any]:
        raise BackendError(f"{self.name}: power_off not supported")


@dataclass
class MockBackend(Backend):
    name: str = "mock"
    calls: list[tuple[str, str, Any]] = field(default_factory=list)
    supports_power_on = True

    def charge(self, device_id: str, on: bool) -> dict[str, Any]:
        self.calls.append(("charge", device_id, on))
        return {"backend": "mock", "charging": on}

    def power_on(self, device_id: str) -> dict[str, Any]:
        self.calls.append(("power_on", device_id, None))
        return {"backend": "mock", "power": "on"}

    def power_off(self, device_id: str) -> dict[str, Any]:
        self.calls.append(("power_off", device_id, None))
        return {"backend": "mock", "power": "off"}


class UhubctlBackend(Backend):
    """``uhubctl -l <location> -p <port> -a on|off`` (needs root or udev rules)."""

    name = "uhubctl"
    supports_power_on = True

    def __init__(
        self,
        location: str,
        port: int | str,
        binary: str = "uhubctl",
        runner: Runner = default_runner,
        timeout: float = 15.0,
    ) -> None:
        self.location, self.port, self.binary = str(location), str(port), binary
        self.runner, self.timeout = runner, timeout

    def _run(self, action: str) -> dict[str, Any]:
        argv = [self.binary, "-l", self.location, "-p", self.port, "-a", action]
        try:
            r = self.runner(argv, self.timeout)
        except (OSError, subprocess.TimeoutExpired) as e:
            raise BackendError(f"uhubctl failed: {e}") from e
        if r.returncode != 0:
            raise BackendError(f"uhubctl exit {r.returncode}: {(r.stderr or r.stdout).strip()}")
        return {"backend": "uhubctl", "argv": argv, "action": action}

    def charge(self, device_id: str, on: bool) -> dict[str, Any]:
        return {**self._run("on" if on else "off"), "charging": on}

    def power_on(self, device_id: str) -> dict[str, Any]:
        # [Guessing] Many Nexus phones boot (or show the charging screen) when USB power
        # returns while off; some only show the battery animation. Cycle to force an edge.
        self._run("off")
        res = self._run("on")
        return {**res, "power": "on_requested", "reliability": "guessing"}


class AdbBackend(Backend):
    name = "adb"

    def __init__(
        self,
        serial: str,
        profile: str = "generic",
        binary: str = "adb",
        runner: Runner = default_runner,
        timeout: float = 20.0,
    ) -> None:
        if profile not in ADB_CHARGE_PROFILES:
            raise ValueError(f"unknown adb charge profile {profile!r}")
        self.serial, self.profile, self.binary = serial, profile, binary
        self.runner, self.timeout = runner, timeout

    def _adb(self, *args: str) -> subprocess.CompletedProcess:
        argv = [self.binary, "-s", self.serial, *args]
        try:
            r = self.runner(argv, self.timeout)
        except (OSError, subprocess.TimeoutExpired) as e:
            raise BackendError(f"adb failed: {e}") from e
        if r.returncode != 0:
            raise BackendError(f"adb exit {r.returncode}: {(r.stderr or r.stdout).strip()}")
        return r

    def charge(self, device_id: str, on: bool) -> dict[str, Any]:
        enable, disable = ADB_CHARGE_PROFILES[self.profile]
        script = enable if on else disable
        self._adb("shell", "su", "-c", script)
        return {"backend": "adb", "profile": self.profile, "charging": on}

    def battery(self) -> str:
        return self._adb("shell", "dumpsys", "battery").stdout

    def power_off(self, device_id: str) -> dict[str, Any]:
        # `reboot -p` is a normal shutdown request; on some builds it needs root.
        self._adb("shell", "reboot", "-p")
        return {"backend": "adb", "power": "off_requested"}


class Esp32RelayBackend(Backend):
    """MQTT relay to an ESP32 driving a relay/high-side MOSFET per USB 5V line (fw >= 0.4).

    ``on`` is sent unsigned. ``off`` is HMAC-signed with ``hub_token`` (same scheme as
    power_pulse) because only the hub may cut a line. Whether an off needs Don's approval
    is decided by the hub before we get here: ``device.power_off`` is in
    APPROVAL_REQUIRED, ``charge.off`` is not. The wire command is the same either way.
    """

    name = "esp32_relay"
    supports_power_on = True

    def __init__(
        self,
        node: str,
        channel: int,
        publish: Callable[[str, dict], bool],
        hub_token: str = "",
    ) -> None:
        self.node, self.channel, self.publish = node, int(channel), publish
        self.hub_token = hub_token

    def _send(self, on: bool) -> dict[str, Any]:
        cmd_id = f"usb-{uuid.uuid4().hex[:16]}"
        if on:
            payload = {"action": "usb_power", "channel": self.channel, "on": True, "id": cmd_id}
        else:
            if not self.hub_token or self.hub_token.startswith("CHANGE_ME"):
                raise BackendError("esp32_relay: node.hub_token not configured; cannot sign off")
            payload = signed_usb_off(self.hub_token, cmd_id, self.channel)
        relayed = self.publish(self.node, payload)
        return {
            "backend": "esp32_relay",
            "node": self.node,
            "channel": self.channel,
            "id": cmd_id,
            "relayed": relayed,
            "signed": not on,
        }

    def charge(self, device_id: str, on: bool) -> dict[str, Any]:
        return {**self._send(on), "charging": on}

    def power_on(self, device_id: str) -> dict[str, Any]:
        return {**self._send(True), "power": "on_requested", "reliability": "guessing"}

    def power_off(self, device_id: str) -> dict[str, Any]:
        # Cutting USB 5 V does not shut a phone down (it runs on battery); it only stops
        # charging / powers off battery-less devices. [Certain] for phones with batteries.
        return {**self._send(False), "power": "usb_cut"}


@dataclass
class DeviceConfig:
    """One downstream device. Each of charge / power_on / power_off is a backend spec
    (``{"backend": "uhubctl", ...}``). power_on and power_off fall back to ``charge``."""

    id: str
    label: str = ""
    charge: dict[str, Any] = field(default_factory=dict)
    power_on: dict[str, Any] = field(default_factory=dict)
    power_off: dict[str, Any] = field(default_factory=dict)


def build_backend(
    spec: dict[str, Any],
    publish: Callable[[str, dict], bool] | None = None,
    runner: Runner = default_runner,
    hub_token: str = "",
) -> Backend:
    kind = spec.get("backend", "mock")
    if kind == "mock":
        return MockBackend()
    if kind == "uhubctl":
        return UhubctlBackend(
            spec["location"], spec["port"], spec.get("binary", "uhubctl"), runner=runner
        )
    if kind == "adb":
        return AdbBackend(
            spec["serial"], spec.get("profile", "generic"), spec.get("binary", "adb"), runner=runner
        )
    if kind == "esp32_relay":
        return Esp32RelayBackend(
            spec["node"], spec["channel"], publish or (lambda n, p: False), hub_token
        )
    raise ValueError(f"unknown backend {kind!r}")


KINDS = ("charge", "power_on", "power_off")


class PowerController:
    """Routes charge.* / device.power_* to the configured backend per device."""

    def __init__(
        self,
        devices: dict[str, DeviceConfig],
        publish: Callable[[str, dict], bool] | None = None,
        runner: Runner = default_runner,
        hub_token: str = "",
    ) -> None:
        self.devices = devices
        self._b: dict[tuple[str, str], Backend] = {}
        for d in devices.values():
            for kind in KINDS:
                spec = getattr(d, kind) or d.charge
                if spec:
                    self._b[(d.id, kind)] = build_backend(spec, publish, runner, hub_token)

    def spec(self, target: str, kind: str) -> dict[str, Any]:
        d = self.devices[target]
        return getattr(d, kind) or d.charge

    def backend(self, target: str | None, kind: str) -> Backend:
        if not target or target not in self.devices:
            raise BackendError(f"unknown device target {target!r}")
        b = self._b.get((target, kind))
        if b is None:
            raise BackendError(f"{target}: no {kind} backend configured")
        return b

    def charge(self, target: str | None, on: bool) -> dict[str, Any]:
        return self.backend(target, "charge").charge(target, on)

    def power_on(self, target: str | None) -> dict[str, Any]:
        return self.backend(target, "power_on").power_on(target)

    def power_off(self, target: str | None) -> dict[str, Any]:
        return self.backend(target, "power_off").power_off(target)

    def list(self) -> list[dict[str, Any]]:
        return [
            {
                "id": d.id,
                "label": d.label or d.id,
                **{f"{k}_backend": (self.spec(d.id, k) or {}).get("backend") for k in KINDS},
            }
            for d in self.devices.values()
        ]
