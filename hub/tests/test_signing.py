import hashlib
import hmac

import pytest
from fastapi.testclient import TestClient

from pyra_hub.api import create_app
from pyra_hub.config import HubConfig, NodeConfig, load_config
from pyra_hub.signing import canonical, sign, signed_pulse, verify

KEY = "node-secret"


def test_canonical_string():
    assert canonical("abc", "power_pulse", 300, 1700000000) == "abc|power_pulse|300|1700000000"


def test_sign_matches_reference_hmac():
    ref = hmac.new(b"k", b"id1|power_pulse|250|42", hashlib.sha256).hexdigest()
    assert sign("k", "id1", "power_pulse", 250, 42) == ref
    assert len(ref) == 64


def test_known_vector():
    # Fixed vector, also usable to check the firmware implementation.
    assert (
        sign("test-key", "cmd-1", "power_pulse", 300, 1700000000)
        == hmac.new(b"test-key", b"cmd-1|power_pulse|300|1700000000", hashlib.sha256).hexdigest()
    )


def test_verify_accepts_and_rejects_tampering():
    s = sign(KEY, "a", "power_pulse", 300, 10)
    assert verify(KEY, "a", "power_pulse", 300, 10, s)
    assert verify(KEY, "a", "power_pulse", 300, 10, s.upper())
    assert not verify(KEY, "a", "power_pulse", 299, 10, s)
    assert not verify(KEY, "a", "power_pulse", 300, 11, s)
    assert not verify(KEY, "b", "power_pulse", 300, 10, s)
    assert not verify("other", "a", "power_pulse", 300, 10, s)
    assert not verify(KEY, "a", "power_pulse", 300, 10, "")


def test_empty_key_and_separator_rejected():
    with pytest.raises(ValueError):
        sign("", "a", "power_pulse", 300, 1)
    with pytest.raises(ValueError):
        sign(KEY, "a|b", "power_pulse", 300, 1)
    assert not verify("", "a", "power_pulse", 300, 1, "00")


def test_signed_pulse_payload():
    p = signed_pulse(KEY, "id9", 300, ts=123)
    assert p == {
        "action": "power_pulse",
        "id": "id9",
        "ms": 300,
        "ts": 123,
        "sig": sign(KEY, "id9", "power_pulse", 300, 123),
    }
    assert isinstance(signed_pulse(KEY, "x", 1)["ts"], int)


class FakeBridge:
    def __init__(self):
        from pyra_hub.registry import Registry

        self.registry = Registry()
        self.client = object()
        self.sent = []

    def publish_cmd(self, node, payload):
        self.sent.append((node, payload))
        return True


def _h():
    return {"Authorization": "Bearer t"}


def test_api_publishes_signed_pulse():
    b = FakeBridge()
    c = TestClient(create_app(HubConfig(auth_token="t", audit_log="", node=NodeConfig(KEY)), b))
    tok = c.post("/api/approve/pc.power_pulse", headers=_h()).json()["approval_token"]
    body = {"type": "remote.command", "id": "cid1", "command": "pc.power_pulse"}
    r = c.post("/api/command", json={**body, "approval_token": tok}, headers=_h())
    assert r.status_code == 200
    node, p = b.sent[0]
    assert node == "esp32-wake-01"
    assert p["id"] == "cid1" and p["ms"] == 300 and "approval" not in p
    assert verify(KEY, "cid1", "power_pulse", p["ms"], p["ts"], p["sig"])


def test_api_refuses_pulse_without_hub_token():
    c = TestClient(create_app(HubConfig(auth_token="t", audit_log=""), FakeBridge()))
    tok = c.post("/api/approve/pc.power_pulse", headers=_h()).json()["approval_token"]
    body = {"type": "remote.command", "command": "pc.power_pulse", "approval_token": tok}
    assert c.post("/api/command", json=body, headers=_h()).status_code == 503


def test_pc_wake_target_null_still_works():
    # VictorSuite backward compatibility: pc.wake with target null, no approval.
    b = FakeBridge()
    c = TestClient(create_app(HubConfig(auth_token="t", audit_log=""), b))
    body = {"type": "remote.command", "command": "pc.wake", "target": None}
    r = c.post("/api/command", json=body, headers=_h())
    assert r.status_code == 200
    assert b.sent[0][1]["action"] == "wake_pc"


def test_config_loads_node_section(tmp_path):
    f = tmp_path / "c.toml"
    f.write_text('[hub]\nauth_token = "x"\n[node]\nhub_token = "abc"\n')
    assert load_config(f).node.hub_token == "abc"
