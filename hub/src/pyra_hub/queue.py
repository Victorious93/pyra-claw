"""Command queue, approval gate and append-only audit log."""

from __future__ import annotations

import json
import secrets
import threading
import time
from collections import deque
from pathlib import Path
from typing import Any

from .envelope import APPROVAL_REQUIRED, KNOWN_COMMANDS, RemoteCommand


class ApprovalError(PermissionError):
    pass


class ApprovalGate:
    """Issues single-use, short-lived approval tokens for dangerous commands."""

    def __init__(self, ttl_s: int = 30) -> None:
        self.ttl_s = ttl_s
        self._tokens: dict[str, tuple[str, float]] = {}
        self._lock = threading.Lock()

    def issue(self, command: str) -> str:
        tok = secrets.token_urlsafe(16)
        with self._lock:
            self._tokens[tok] = (command, time.time() + self.ttl_s)
        return tok

    def consume(self, command: str, token: str | None) -> bool:
        if not token:
            return False
        with self._lock:
            entry = self._tokens.pop(token, None)
        if entry is None:
            return False
        cmd, expires = entry
        return cmd == command and time.time() <= expires

    def check(self, cmd: RemoteCommand) -> None:
        if cmd.command in APPROVAL_REQUIRED and not self.consume(cmd.command, cmd.approval_token):
            raise ApprovalError(f"{cmd.command} requires a valid approval token")


class AuditLog:
    def __init__(self, path: str | None) -> None:
        self.path = Path(path) if path else None
        self.recent: deque[dict[str, Any]] = deque(maxlen=200)
        self._lock = threading.Lock()

    def write(self, event: str, **fields: Any) -> dict[str, Any]:
        rec = {"ts": time.time(), "event": event, **fields}
        with self._lock:
            self.recent.append(rec)
            if self.path:
                self.path.parent.mkdir(parents=True, exist_ok=True)
                with self.path.open("a") as f:
                    f.write(json.dumps(rec) + "\n")
        return rec


class CommandQueue:
    def __init__(self, gate: ApprovalGate, audit: AuditLog) -> None:
        self.gate = gate
        self.audit = audit
        self.pending: deque[RemoteCommand] = deque(maxlen=500)

    def submit(self, cmd: RemoteCommand) -> RemoteCommand:
        if cmd.command not in KNOWN_COMMANDS:
            self.audit.write("rejected", id=cmd.id, command=cmd.command, reason="unknown")
            raise ValueError(f"unknown command: {cmd.command}")
        try:
            self.gate.check(cmd)
        except ApprovalError:
            self.audit.write("rejected", id=cmd.id, command=cmd.command, reason="no_approval")
            raise
        self.pending.append(cmd)
        self.audit.write("accepted", id=cmd.id, command=cmd.command, source=cmd.source)
        return cmd
