import subprocess

import pytest

from pyra_hub.power import (
    AdbBackend,
    BackendError,
    DeviceConfig,
    MockBackend,
    PowerController,
    UhubctlBackend,
)


class FakeRunner:
    def __init__(self, rc=0, stderr=""):
        self.calls, self.rc, self.stderr = [], rc, stderr

    def __call__(self, argv, timeout):
        self.calls.append(list(argv))
        return subprocess.CompletedProcess(argv, self.rc, stdout="", stderr=self.stderr)


def test_uhubctl_argv():
    r = FakeRunner()
    b = UhubctlBackend("1-1", 3, runner=r)
    assert b.charge("phone1", False)["charging"] is False
    assert r.calls == [["uhubctl", "-l", "1-1", "-p", "3", "-a", "off"]]


def test_uhubctl_power_on_cycles_and_marks_guessing():
    r = FakeRunner()
    res = UhubctlBackend("2", 1, runner=r).power_on("phone1")
    assert [c[-1] for c in r.calls] == ["off", "on"]
    assert res["reliability"] == "guessing"


def test_uhubctl_error_raises():
    with pytest.raises(BackendError, match="exit 1"):
        UhubctlBackend("1-1", 1, runner=FakeRunner(rc=1, stderr="no hub")).charge("x", True)


def test_adb_charge_uses_profile_and_su():
    r = FakeRunner()
    AdbBackend("SER1", "nexus6", runner=r).charge("phone1", False)
    argv = r.calls[0]
    assert argv[:6] == ["adb", "-s", "SER1", "shell", "su", "-c"]
    assert "echo 0 > /sys/class/power_supply/battery/charging_enabled" in argv[6]


def test_adb_power_off_and_no_power_on():
    r = FakeRunner()
    b = AdbBackend("SER1", runner=r)
    b.power_off("phone1")
    assert r.calls[0] == ["adb", "-s", "SER1", "shell", "reboot", "-p"]
    with pytest.raises(BackendError):
        b.power_on("phone1")


def test_adb_unknown_profile():
    with pytest.raises(ValueError):
        AdbBackend("S", "nexus99")


def test_controller_routes_and_falls_back_to_charge_backend():
    r = FakeRunner()
    pc = PowerController(
        {
            "phone1": DeviceConfig(
                id="phone1",
                charge={"backend": "uhubctl", "location": "1-1", "port": 1},
                power_off={"backend": "adb", "serial": "S1"},
            )
        },
        runner=r,
    )
    pc.charge("phone1", True)
    pc.power_on("phone1")  # falls back to uhubctl
    pc.power_off("phone1")  # adb
    assert r.calls[0][0] == "uhubctl" and r.calls[-1][0] == "adb"
    with pytest.raises(BackendError):
        pc.charge("nope", True)
    assert pc.list()[0]["power_off_backend"] == "adb"


def test_mock_records():
    m = MockBackend()
    m.charge("a", True)
    m.power_off("a")
    assert m.calls == [("charge", "a", True), ("power_off", "a", None)]
