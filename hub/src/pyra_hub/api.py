"""FastAPI app: REST endpoints + WebSocket /ws status stream."""

from __future__ import annotations

import asyncio
import time

from fastapi import Depends, FastAPI, HTTPException, WebSocket, WebSocketDisconnect

from . import __version__
from .auth import make_dependency, token_matches
from .config import HubConfig
from .envelope import parse_envelope, unwrap
from .mqtt_bridge import MqttBridge
from .queue import ApprovalError, ApprovalGate, AuditLog, CommandQueue
from .registry import Registry
from .signing import signed_pulse
from .wol import send_wol

POWER_PULSE_MAX_MS = 300
PROTOCOL_VERSION = "0.2"


def create_app(cfg: HubConfig, bridge: MqttBridge | None = None) -> FastAPI:
    app = FastAPI(title="Pyra Claw Hub", version=__version__)
    registry = bridge.registry if bridge else Registry()
    gate = ApprovalGate(cfg.approval_ttl_s)
    audit = AuditLog(cfg.audit_log)
    queue = CommandQueue(gate, audit)
    auth = Depends(make_dependency(cfg.auth_token))
    started = time.time()
    app.state.registry, app.state.gate, app.state.audit = registry, gate, audit

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
        raise HTTPException(400, "unsupported")

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
                await websocket.send_json(status())
                await asyncio.sleep(2)
        except WebSocketDisconnect:
            return

    return app
