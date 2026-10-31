"""FastAPI app: REST endpoints + WebSocket /ws status stream."""

from __future__ import annotations

import asyncio
import time
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, WebSocket, WebSocketDisconnect

from . import __version__
from .auth import make_dependency, token_matches
from .config import HubConfig
from .envelope import (
    APPROVAL_REQUIRED,
    DEVICE_COMMANDS,
    KNOWN_COMMANDS,
    RemoteCommand,
    parse_envelope,
    unwrap,
)
from .mqtt_bridge import MqttBridge
from .power import BackendError, PowerController, Runner, default_runner
from .queue import ApprovalError, ApprovalGate, AuditLog, CommandQueue
from .registry import Registry
from .signing import signed_pulse
from .timers import Scheduler, Step, Timer, TimerIn, TimerStore
from .wol import send_wol

POWER_PULSE_MAX_MS = 300
PROTOCOL_VERSION = "0.3"
TIMER_ARM = "timer.arm"


def create_app(
    cfg: HubConfig,
    bridge: MqttBridge | None = None,
    runner: Runner = default_runner,
    start_scheduler: bool = True,
    sleep=time.sleep,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        if start_scheduler:
            _app.state.scheduler.start()
        yield
        _app.state.scheduler.stop()

    app = FastAPI(title="Pyra Claw Hub", version=__version__, lifespan=lifespan)
    registry = bridge.registry if bridge else Registry()
    gate = ApprovalGate(cfg.approval_ttl_s)
    audit = AuditLog(cfg.audit_log)
    queue = CommandQueue(gate, audit)
    auth = Depends(make_dependency(cfg.auth_token))
    started = time.time()
    publish = bridge.publish_cmd if bridge else (lambda node, payload: False)
    power = PowerController(cfg.devices, publish=publish, runner=runner)
    store = TimerStore(cfg.timers_file or None)
    app.state.registry, app.state.gate, app.state.audit = registry, gate, audit
    app.state.power, app.state.store = power, store

    def status() -> dict:
        return {
            "version": __version__,
            "protocol": PROTOCOL_VERSION,
            "uptime_s": round(time.time() - started, 1),
            "mqtt": bool(bridge and bridge.client),
            "nodes": registry.list(),
        }

    def execute(cmd) -> dict:
        if cmd.command == "hub.status":
            return status()
        if cmd.command == "node.list":
            return {"nodes": registry.list()}
        if cmd.command == "pc.wake":
            sent = send_wol(cfg.pc.mac, cfg.pc.broadcast)
            relayed = bool(bridge) and bridge.publish_cmd(
                cfg.pc.wake_node, {"action": "wake_pc", "id": cmd.id}
            )
            return {"wol_bytes": sent, "relayed": relayed}
        if cmd.command == "pc.power_pulse":
            ms = min(int(cmd.args.get("ms", POWER_PULSE_MAX_MS)), POWER_PULSE_MAX_MS)
            if not cfg.node.hub_token or cfg.node.hub_token.startswith("CHANGE_ME"):
                raise HTTPException(503, "node.hub_token not configured; cannot sign power_pulse")
            payload = signed_pulse(cfg.node.hub_token, cmd.id, ms)
            relayed = bool(bridge) and bridge.publish_cmd(cmd.target or cfg.pc.wake_node, payload)
            return {"ms": ms, "relayed": relayed, "signed": True}
        if cmd.command in DEVICE_COMMANDS:
            try:
                if cmd.command == "charge.on":
                    return power.charge(cmd.target, True)
                if cmd.command == "charge.off":
                    return power.charge(cmd.target, False)
                if cmd.command == "device.power_on":
                    return power.power_on(cmd.target)
                return power.power_off(cmd.target)
            except BackendError as e:
                raise HTTPException(502, str(e)) from e
        raise HTTPException(400, "unsupported")

    # ---- timers ----------------------------------------------------------------------
    def run_step(t: Timer, i: int, step: Step) -> dict:
        cmd = RemoteCommand(
            id=f"timer-{t.id}-{i}-{int(time.time())}",
            command=step.command,
            target=step.target,
            args=step.args,
            source=f"timer:{t.id}",
        )
        if cmd.command not in KNOWN_COMMANDS:
            raise ValueError(f"unknown command {cmd.command}")
        if cmd.command in APPROVAL_REQUIRED and not t.preapproved:
            audit.write(
                "rejected", id=cmd.id, command=cmd.command, reason="no_approval", timer_id=t.id
            )
            raise ApprovalError(f"{cmd.command} needs a pre-approved timer")
        audit.write(
            "accepted",
            id=cmd.id,
            command=cmd.command,
            source=cmd.source,
            preapproved=cmd.command in APPROVAL_REQUIRED,
        )
        try:
            result = execute(cmd)
        except HTTPException as e:
            audit.write("failed", id=cmd.id, command=cmd.command, error=str(e.detail))
            raise RuntimeError(str(e.detail)) from e
        audit.write("executed", id=cmd.id, command=cmd.command, target=cmd.target, timer_id=t.id)
        return result

    sched = Scheduler(store, run_step, sleep=sleep)
    app.state.scheduler = sched

    def needs_approval(steps: list[Step]) -> list[str]:
        return sorted({s.command for s in steps if s.command in APPROVAL_REQUIRED})

    def validate_steps(steps: list[Step]) -> None:
        for i, st in enumerate(steps):
            if st.command not in KNOWN_COMMANDS:
                raise HTTPException(422, f"step {i}: unknown command {st.command}")
            if st.command in DEVICE_COMMANDS and st.target not in cfg.devices:
                raise HTTPException(422, f"step {i}: unknown device {st.target!r}")

    def arm(body: TimerIn, timer_id: str, prev: Timer | None) -> tuple[bool, float | None]:
        """Pre-approval for unattended destructive steps. Returns (preapproved, at)."""
        gated = needs_approval(body.steps)
        if not gated:
            return False, None
        if prev and prev.preapproved and prev.steps == body.steps and not body.approval_token:
            return True, prev.preapproved_at
        if not gate.consume(TIMER_ARM, body.approval_token):
            audit.write("timer_rejected", timer_id=timer_id, reason="no_approval", gated=gated)
            raise HTTPException(
                403,
                f"timer has approval-required steps {gated}: POST /api/approve/{TIMER_ARM}"
                " and pass approval_token",
            )
        audit.write(
            "timer_preapproved",
            timer_id=timer_id,
            name=body.name,
            gated=gated,
            steps=[s.model_dump() for s in body.steps],
        )
        return True, time.time()

    @app.get("/api/devices", dependencies=[auth])
    def api_devices() -> dict:
        return {"devices": power.list()}

    @app.get("/api/timers", dependencies=[auth])
    def api_timers() -> dict:
        return sched.snapshot()

    @app.post("/api/timers", dependencies=[auth], status_code=201)
    def api_timer_create(body: TimerIn) -> dict:
        validate_steps(body.steps)
        t = Timer(name=body.name, enabled=body.enabled, schedule=body.schedule, steps=body.steps)
        t.preapproved, t.preapproved_at = arm(body, t.id, None)
        with store.lock:
            sched.recompute(t)
            store.timers[t.id] = t
            store.save()
        audit.write("timer_created", timer_id=t.id, name=t.name)
        return t.model_dump()

    def get_timer(timer_id: str) -> Timer:
        t = store.timers.get(timer_id)
        if t is None:
            raise HTTPException(404, "no such timer")
        return t

    @app.get("/api/timers/{timer_id}", dependencies=[auth])
    def api_timer_get(timer_id: str) -> dict:
        return get_timer(timer_id).model_dump()

    @app.put("/api/timers/{timer_id}", dependencies=[auth])
    def api_timer_update(timer_id: str, body: TimerIn) -> dict:
        prev = get_timer(timer_id)
        if prev.running:
            raise HTTPException(409, "timer is running")
        validate_steps(body.steps)
        pre, pre_at = arm(body, timer_id, prev)
        with store.lock:
            t = prev.model_copy(
                update={
                    "name": body.name,
                    "enabled": body.enabled,
                    "schedule": body.schedule,
                    "steps": body.steps,
                    "preapproved": pre,
                    "preapproved_at": pre_at,
                    "updated_at": time.time(),
                }
            )
            sched.recompute(t)
            store.timers[timer_id] = t
            store.save()
        audit.write("timer_updated", timer_id=timer_id)
        return t.model_dump()

    @app.delete("/api/timers/{timer_id}", dependencies=[auth])
    def api_timer_delete(timer_id: str) -> dict:
        get_timer(timer_id)
        with store.lock:
            store.timers.pop(timer_id, None)
            store.save()
        audit.write("timer_deleted", timer_id=timer_id)
        return {"deleted": timer_id}

    def set_enabled(timer_id: str, enabled: bool) -> dict:
        t = get_timer(timer_id)
        with store.lock:
            t.enabled, t.updated_at = enabled, time.time()
            sched.recompute(t)
            store.save()
        audit.write("timer_enabled" if enabled else "timer_disabled", timer_id=timer_id)
        return t.model_dump()

    @app.post("/api/timers/{timer_id}/enable", dependencies=[auth])
    def api_timer_enable(timer_id: str) -> dict:
        return set_enabled(timer_id, True)

    @app.post("/api/timers/{timer_id}/disable", dependencies=[auth])
    def api_timer_disable(timer_id: str) -> dict:
        return set_enabled(timer_id, False)

    @app.post("/api/timers/{timer_id}/run", dependencies=[auth], status_code=202)
    def api_timer_run(timer_id: str) -> dict:
        get_timer(timer_id)
        started_run = sched.run_now(timer_id, reason="run_now")
        if not started_run:
            raise HTTPException(409, "timer is already running")
        audit.write("timer_run_now", timer_id=timer_id)
        return {"id": timer_id, "started": True}

    @app.get("/health")
    def health() -> dict:
        return {"ok": True}

    @app.get("/api/status", dependencies=[auth])
    def api_status() -> dict:
        return status()

    @app.get("/api/nodes", dependencies=[auth])
    def api_nodes() -> dict:
        return {"nodes": registry.list()}

    @app.get("/api/audit", dependencies=[auth])
    def api_audit() -> dict:
        return {"events": list(audit.recent)}

    @app.post("/api/approve/{command}", dependencies=[auth])
    def api_approve(command: str) -> dict:
        token = gate.issue(command)
        audit.write("approval_issued", command=command)
        return {"command": command, "approval_token": token, "ttl_s": gate.ttl_s}

    @app.post("/api/command", dependencies=[auth])
    def api_command(body: dict) -> dict:
        try:
            cmd = unwrap(parse_envelope(body))
        except ValueError as e:
            raise HTTPException(422, str(e)) from e
        approval = cmd.approval_token
        try:
            queue.submit(cmd)
        except ApprovalError as e:
            raise HTTPException(403, str(e)) from e
        except ValueError as e:
            raise HTTPException(400, str(e)) from e
        cmd.approval_token = approval
        result = execute(cmd)
        audit.write("executed", id=cmd.id, command=cmd.command)
        return {"id": cmd.id, "ok": True, "result": result}

    @app.websocket("/ws")
    async def ws(websocket: WebSocket, token: str | None = None):
        if not token_matches(cfg.auth_token, token):
            await websocket.close(code=4401)
            return
        await websocket.accept()
        try:
            while True:
                await websocket.send_json({**status(), "timers": sched.snapshot()})
                await asyncio.sleep(2)
        except WebSocketDisconnect:
            return

    return app
