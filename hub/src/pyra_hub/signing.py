"""HMAC-SHA256 signing of node commands (protocol v0.2).

sig = hex(HMAC-SHA256(hub_token, f"{id}|{action}|{ms}|{ts}"))
`ts` is integer Unix seconds. The ESP32 node verifies the same string.
"""

from __future__ import annotations

import hashlib
import hmac
import time


def canonical(cmd_id: str, action: str, ms: int, ts: int) -> str:
    for part in (cmd_id, action):
        if "|" in part:
            raise ValueError("'|' not allowed in id/action")
    return f"{cmd_id}|{action}|{int(ms)}|{int(ts)}"


def sign(hub_token: str, cmd_id: str, action: str, ms: int, ts: int) -> str:
    if not hub_token:
        raise ValueError("hub_token is empty")
    msg = canonical(cmd_id, action, ms, ts).encode()
    return hmac.new(hub_token.encode(), msg, hashlib.sha256).hexdigest()


def verify(hub_token: str, cmd_id: str, action: str, ms: int, ts: int, sig: str) -> bool:
    try:
        expected = sign(hub_token, cmd_id, action, ms, ts)
    except ValueError:
        return False
    return hmac.compare_digest(expected, (sig or "").lower())


def signed_pulse(hub_token: str, cmd_id: str, ms: int, ts: int | None = None) -> dict:
    """Build the MQTT/HTTP body for a signed power_pulse."""
    ts = int(time.time()) if ts is None else int(ts)
    return {
        "action": "power_pulse",
        "id": cmd_id,
        "ms": int(ms),
        "ts": ts,
        "sig": sign(hub_token, cmd_id, "power_pulse", ms, ts),
    }
