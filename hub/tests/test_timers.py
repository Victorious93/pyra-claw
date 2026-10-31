import json
import time
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from pyra_hub.api import create_app
from pyra_hub.config import HubConfig, NodeConfig
from pyra_hub.power import DeviceConfig
from pyra_hub.timers import Schedule, Scheduler, Step, Timer, TimerStore

TOKEN = "test-token"
H = {"Authorization": f"Bearer {TOKEN}"}
TZ = timezone(timedelta(hours=-4))


def test_schedule_daily_next():
    s = Schedule(kind="daily", time="07:30")
    now = datetime(2026, 10, 31, 8, 0, tzinfo=TZ)
    assert s.next_after(now) == datetime(2026, 11, 1, 7, 30, tzinfo=TZ)
    assert s.next_after(now.replace(hour=7)) == datetime(2026, 10, 31, 7, 30, tzinfo=TZ)


def test_schedule_weekly_next():
    s = Schedule(kind="weekly", time="22:00", days=[0, 2])  # Mon, Wed
    sat = datetime(2026, 10, 31, 12, 0, tzinfo=TZ)
    assert s.next_after(sat) == datetime(2026, 11, 2, 22, 0, tzinfo=TZ)


def test_schedule_once_and_manual():
    now = datetime(2026, 10, 31, 12, 0, tzinfo=TZ)
    assert Schedule(kind="once", at="2026-10-31T12:05:00-04:00").next_after(now) is not None
    assert Schedule(kind="once", at="2026-10-31T11:00:00-04:00").next_after(now) is None
    assert Schedule(kind="manual").next_after(now) is None


@pytest.mark.parametrize(
    "bad",
    [
        {"kind": "daily"},
        {"kind": "weekly", "time": "07:00"},
        {"kind": "once"},
        {"kind": "daily", "time": "25:00"},
    ],
)
def test_schedule_validation(bad):
    with pytest.raises(ValueError):
        Schedule(**bad)


def _store_with(tmp_path, steps, kind="manual", **sched):
    store = TimerStore(str(tmp_path / "timers.json"))
    t = Timer(name="t", schedule=Schedule(kind=kind, **sched), steps=steps)
    store.timers[t.id] = t
    return store, t


def _wait_idle(store, tid, timeout=5):
    end = time.time() + timeout
    while store.timers[tid].running and time.time() < end:
        time.sleep(0.01)


def test_sequence_runs_in_order_with_delays(tmp_path):
    steps = [
        Step(command="charge.on", target="p1"),
        Step(command="charge.on", target="p2", delay_s=30),
    ]
    store, t = _store_with(tmp_path, steps)
    ran, slept = [], []
    sched = Scheduler(store, lambda tm, i, s: ran.append(s.target) or {}, sleep=slept.append)
    assert sched.run_now(t.id)
    _wait_idle(store, t.id)
    assert ran == ["p1", "p2"] and slept == [30]
    assert store.timers[t.id].last_result == "ok"
    assert json.loads((tmp_path / "timers.json").read_text())["timers"][0]["id"] == t.id


def test_sequence_stops_on_failure(tmp_path):
    steps = [Step(command="charge.on", target="p1"), Step(command="charge.on", target="p2")]
    store, t = _store_with(tmp_path, steps)

    def boom(tm, i, s):
        raise RuntimeError("hub down")

    sched = Scheduler(store, boom, sleep=lambda s: None)
    sched.run_now(t.id)
    _wait_idle(store, t.id)
    assert store.timers[t.id].last_result.startswith("failed at step 0")
    assert [e["event"] for e in sched.events][-1] == "timer_finished"


def test_tick_fires_due_once_timer_and_disables(tmp_path):
    now = [datetime(2026, 10, 31, 12, 0, tzinfo=TZ)]
    store, t = _store_with(
        tmp_path, [Step(command="pc.wake")], kind="once", at="2026-10-31T12:00:30-04:00"
    )
    ran = []
    sched = Scheduler(
        store, lambda tm, i, s: ran.append(s) or {}, clock=lambda: now[0], sleep=lambda s: None
    )
    assert sched.tick() == []
    now[0] += timedelta(seconds=31)
    assert sched.tick() == [t.id]
    _wait_idle(store, t.id)
    assert len(ran) == 1 and store.timers[t.id].enabled is False
    assert store.timers[t.id].next_run is None


def test_store_reload(tmp_path):
    store, t = _store_with(tmp_path, [Step(command="pc.wake")])
    store.save()
    again = TimerStore(str(tmp_path / "timers.json"))
    assert again.timers[t.id].name == "t"


# ---- API --------------------------------------------------------------------------
def _client(tmp_path):
    devices = {
        f"phone{i}": DeviceConfig(id=f"phone{i}", charge={"backend": "mock"}) for i in (1, 2, 3)
    }
    cfg = HubConfig(
        auth_token=TOKEN,
        audit_log=str(tmp_path / "audit.jsonl"),
        timers_file=str(tmp_path / "timers.json"),
        devices=devices,
        node=NodeConfig(hub_token="node-secret"),
    )
    app = create_app(cfg, start_scheduler=False, sleep=lambda s: None)
    return TestClient(app), app


def _seq(**kw):
    return {
        "name": "rack charge",
        "schedule": {"kind": "daily", "time": "07:00"},
        "steps": [
            {"command": "charge.on", "target": "phone1"},
            {"command": "charge.on", "target": "phone2", "delay_s": 30},
            {"command": "charge.on", "target": "phone3", "delay_s": 30},
        ],
        **kw,
    }


def test_api_timer_crud_and_run(tmp_path):
    c, app = _client(tmp_path)
    r = c.post("/api/timers", json=_seq(), headers=H)
    assert r.status_code == 201, r.text
    tid = r.json()["id"]
    assert r.json()["next_run"] is not None
    assert c.get("/api/timers", headers=H).json()["timers"][0]["id"] == tid
    assert c.post(f"/api/timers/{tid}/disable", headers=H).json()["next_run"] is None
    assert c.post(f"/api/timers/{tid}/enable", headers=H).json()["next_run"] is not None
    assert c.post(f"/api/timers/{tid}/run", headers=H).status_code == 202
    _wait_idle(app.state.store, tid)
    assert app.state.store.timers[tid].last_result == "ok"
    mock = app.state.power.backend("phone2", "charge")
    assert mock.calls == [("charge", "phone2", True)]
    events = [e["event"] for e in app.state.audit.recent]
    assert events.count("executed") == 3
    upd = c.put(f"/api/timers/{tid}", json=_seq(name="renamed"), headers=H)
    assert upd.json()["name"] == "renamed"
    assert c.delete(f"/api/timers/{tid}", headers=H).status_code == 200
    assert c.get(f"/api/timers/{tid}", headers=H).status_code == 404


def test_api_rejects_unknown_device_and_command(tmp_path):
    c, _ = _client(tmp_path)
    bad = _seq(steps=[{"command": "charge.on", "target": "phone9"}])
    assert c.post("/api/timers", json=bad, headers=H).status_code == 422
    bad = _seq(steps=[{"command": "rm.rf"}])
    assert c.post("/api/timers", json=bad, headers=H).status_code == 422


def test_api_gated_timer_requires_preapproval(tmp_path):
    c, app = _client(tmp_path)
    body = _seq(steps=[{"command": "device.power_off", "target": "phone1"}])
    assert c.post("/api/timers", json=body, headers=H).status_code == 403
    # a pc.power_pulse approval token must not arm a timer
    wrong = c.post("/api/approve/pc.power_pulse", headers=H).json()["approval_token"]
    assert (
        c.post("/api/timers", json={**body, "approval_token": wrong}, headers=H).status_code == 403
    )
    tok = c.post("/api/approve/timer.arm", headers=H).json()["approval_token"]
    r = c.post("/api/timers", json={**body, "approval_token": tok}, headers=H)
    assert r.status_code == 201 and r.json()["preapproved"] is True
    tid = r.json()["id"]
    pre = [e for e in app.state.audit.recent if e["event"] == "timer_preapproved"]
    assert pre and pre[0]["gated"] == ["device.power_off"]
    # same steps, no new token: stays armed
    r2 = c.put(f"/api/timers/{tid}", json={**body, "name": "x"}, headers=H)
    assert r2.status_code == 200 and r2.json()["preapproved"] is True
    # changed gated steps need a fresh approval
    changed = _seq(steps=[{"command": "device.power_off", "target": "phone2"}])
    assert c.put(f"/api/timers/{tid}", json=changed, headers=H).status_code == 403
    c.post(f"/api/timers/{tid}/run", headers=H)
    _wait_idle(app.state.store, tid)
    assert app.state.store.timers[tid].last_result == "ok"


def test_unapproved_gated_step_blocked_at_run(tmp_path):
    c, app = _client(tmp_path)
    store = app.state.store
    t = Timer(name="sneaky", schedule=Schedule(), steps=[Step(command="pc.power_pulse")])
    store.timers[t.id] = t  # e.g. hand-edited timers.json
    c.post(f"/api/timers/{t.id}/run", headers=H)
    _wait_idle(store, t.id)
    assert "pre-approved" in store.timers[t.id].last_result


def test_direct_charge_command_and_devices(tmp_path):
    c, app = _client(tmp_path)
    body = {"type": "remote.command", "command": "charge.off", "target": "phone3"}
    r = c.post("/api/command", json=body, headers=H)
    assert r.status_code == 200 and r.json()["result"]["charging"] is False
    off = {"type": "remote.command", "command": "device.power_off", "target": "phone3"}
    assert c.post("/api/command", json=off, headers=H).status_code == 403
    assert len(c.get("/api/devices", headers=H).json()["devices"]) == 3


def test_pc_wake_target_null_still_works(tmp_path):
    c, _ = _client(tmp_path)
    r = c.post(
        "/api/command",
        json={"type": "remote.command", "command": "pc.wake", "target": None},
        headers=H,
    )
    assert r.status_code == 200 and "wol_bytes" in r.json()["result"]
    assert c.get("/api/status", headers=H).json()["protocol"] == "0.3"


def test_ws_includes_timers(tmp_path):
    c, _ = _client(tmp_path)
    c.post("/api/timers", json=_seq(), headers=H)
    with c.websocket_connect(f"/ws?token={TOKEN}") as ws:
        msg = ws.receive_json()
    assert msg["timers"]["timers"][0]["name"] == "rack charge"
