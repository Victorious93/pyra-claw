"""esp32_relay backend: usb_power over MQTT (protocol v0.4)."""

import pytest
from fastapi.testclient import TestClient

from pyra_hub.api import create_app
from pyra_hub.config import HubConfig, NodeConfig
from pyra_hub.power import BackendError, DeviceConfig, Esp32RelayBackend
from pyra_hub.registry import Registry
from pyra_hub.signing import canonical, signed_usb_off, verify

KEY = "node-secret"
H = {"Authorization": "Bearer t"}


class FakeBridge:
    def __init__(self):
        self.registry = Registry()
        self.client = object()
        self.sent = []

    def publish_cmd(self, node, payload):
        self.sent.append((node, payload))
        return True


def _relay(sent, token=KEY, ch=2):
    return Esp32RelayBackend("esp32-wake-01", ch, lambda n, p: sent.append((n, p)) or True, token)


def test_on_is_unsigned():
    sent = []
    res = _relay(sent).charge("phone3", True)
    node, p = sent[0]
    assert node == "esp32-wake-01"
    assert p["action"] == "usb_power" and p["channel"] == 2 and p["on"] is True
    assert "sig" not in p and res["signed"] is False and res["relayed"] is True


def test_off_is_signed_and_bound_to_channel():
    sent = []
    _relay(sent).charge("phone3", False)
    p = sent[0][1]
    assert p["on"] is False
    assert verify(KEY, p["id"], "usb_power_off", 2, p["ts"], p["sig"])
    assert not verify(KEY, p["id"], "usb_power_off", 1, p["ts"], p["sig"])  # other channel
    assert not verify(KEY, p["id"], "power_pulse", 2, p["ts"], p["sig"])  # other action


def test_off_without_token_refuses():
    with pytest.raises(BackendError, match="hub_token"):
        _relay([], token="").charge("phone3", False)
    with pytest.raises(BackendError, match="hub_token"):
        _relay([], token="CHANGE_ME_NODE").power_off("phone3")


def test_signed_usb_off_canonical_string():
    p = signed_usb_off(KEY, "abc", 1, ts=1_800_000_000)
    assert canonical("abc", "usb_power_off", 1, 1_800_000_000) == "abc|usb_power_off|1|1800000000"
    assert verify(KEY, "abc", "usb_power_off", 1, 1_800_000_000, p["sig"])


def _app(bridge):
    dev = DeviceConfig(
        id="phone1", charge={"backend": "esp32_relay", "node": "esp32-wake-01", "channel": 0}
    )
    cfg = HubConfig(auth_token="t", audit_log="", node=NodeConfig(KEY), devices={"phone1": dev})
    return TestClient(create_app(cfg, bridge, start_scheduler=False))


def test_api_charge_off_signed_without_approval():
    # Hub semantics: charge.off is not approval-gated; the wire command is still signed.
    b = FakeBridge()
    c = _app(b)
    body = {"type": "remote.command", "command": "charge.off", "target": "phone1"}
    r = c.post("/api/command", json=body, headers=H)
    assert r.status_code == 200, r.text
    p = b.sent[0][1]
    assert p["on"] is False and verify(KEY, p["id"], "usb_power_off", 0, p["ts"], p["sig"])


def test_api_power_off_needs_approval_then_signs():
    b = FakeBridge()
    c = _app(b)
    body = {"type": "remote.command", "command": "device.power_off", "target": "phone1"}
    assert c.post("/api/command", json=body, headers=H).status_code == 403
    assert b.sent == []
    tok = c.post("/api/approve/device.power_off", headers=H).json()["approval_token"]
    r = c.post("/api/command", json={**body, "approval_token": tok}, headers=H)
    assert r.status_code == 200, r.text
    assert r.json()["result"]["power"] == "usb_cut"
    assert b.sent[0][1]["sig"]
