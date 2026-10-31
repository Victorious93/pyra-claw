"""droidcommand-AI fleet envelopes: remote.command and macro.step."""

from __future__ import annotations

import time
import uuid
from typing import Any, Literal

from pydantic import BaseModel, Field

KNOWN_COMMANDS = {
    "hub.status",
    "node.list",
    "pc.wake",
    "pc.power_pulse",
    # v0.3: downstream device charge / power control (target = device id)
    "charge.on",
    "charge.off",
    "device.power_on",
    "device.power_off",
}
# Destructive hardware actions sit behind approval gates (fleet rule).
APPROVAL_REQUIRED = {"pc.power_pulse", "device.power_off"}
DEVICE_COMMANDS = {"charge.on", "charge.off", "device.power_on", "device.power_off"}


class RemoteCommand(BaseModel):
    type: Literal["remote.command"] = "remote.command"
    id: str = Field(default_factory=lambda: uuid.uuid4().hex)
    command: str
    target: str | None = None
    args: dict[str, Any] = Field(default_factory=dict)
    approval_token: str | None = None
    source: str = "unknown"
    ts: float = Field(default_factory=time.time)


class MacroStep(BaseModel):
    type: Literal["macro.step"] = "macro.step"
    macro_id: str
    step: int = Field(ge=0)
    command: RemoteCommand


Envelope = RemoteCommand | MacroStep


def parse_envelope(data: dict[str, Any]) -> RemoteCommand | MacroStep:
    kind = data.get("type")
    if kind == "remote.command":
        return RemoteCommand.model_validate(data)
    if kind == "macro.step":
        return MacroStep.model_validate(data)
    raise ValueError(f"unknown envelope type: {kind!r}")


def unwrap(env: RemoteCommand | MacroStep) -> RemoteCommand:
    return env.command if isinstance(env, MacroStep) else env
