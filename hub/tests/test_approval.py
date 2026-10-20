import pytest
from fastapi.testclient import TestClient

from pyra_hub.api import create_app
from pyra_hub.config import HubConfig
from pyra_hub.envelope import RemoteCommand
from pyra_hub.queue import ApprovalError, ApprovalGate, AuditLog, CommandQueue

TOKEN = "test-token"


def _client():
    return TestClient(create_app(HubConfig(auth_token=TOKEN, audit_log="")))


def _h():
    return {"Authorization": f"Bearer {TOKEN}"}


def test_queue_rejects_power_pulse_without_approval():
    q = CommandQueue(ApprovalGate(), AuditLog(None))
    with pytest.raises(ApprovalError):
        q.submit(RemoteCommand(command="pc.power_pulse"))


def test_api_rejects_power_pulse_without_approval():
    r = _client().post(
        "/api/command", json={"type": "remote.command", "command": "pc.power_pulse"}, headers=_h()
    )
    assert r.status_code == 403


def test_api_rejects_bogus_approval():
    body = {"type": "remote.command", "command": "pc.power_pulse", "approval_token": "bogus"}
    assert _client().post("/api/command", json=body, headers=_h()).status_code == 403


def test_api_accepts_power_pulse_with_approval_and_caps_ms():
    c = _client()
    tok = c.post("/api/approve/pc.power_pulse", headers=_h()).json()["approval_token"]
    body = {
        "type": "remote.command",
        "command": "pc.power_pulse",
        "approval_token": tok,
        "args": {"ms": 5000},
    }
    r = c.post("/api/command", json=body, headers=_h())
    assert r.status_code == 200
    assert r.json()["result"]["ms"] == 300
    # single use
    assert c.post("/api/command", json=body, headers=_h()).status_code == 403


def test_auth_required():
    assert _client().get("/api/status").status_code == 401
