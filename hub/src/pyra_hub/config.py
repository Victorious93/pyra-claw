"""Configuration loading (TOML)."""

from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class MqttConfig:
    host: str = "127.0.0.1"
    port: int = 1883
    username: str = ""
    password: str = ""
    topic_prefix: str = "pyra/node"


@dataclass
class PcConfig:
    mac: str = "AA:BB:CC:DD:EE:FF"
    broadcast: str = "255.255.255.255"
    wake_node: str = "esp32-wake-01"


@dataclass
class HubConfig:
    host: str = "0.0.0.0"
    port: int = 8787
    auth_token: str = "CHANGE_ME"
    audit_log: str = "audit.jsonl"
    approval_ttl_s: int = 30
    mqtt: MqttConfig = field(default_factory=MqttConfig)
    pc: PcConfig = field(default_factory=PcConfig)


def load_config(path: str | os.PathLike[str] | None = None) -> HubConfig:
    path = path or os.environ.get("PYRA_CONFIG", "/etc/pyra-claw/config.toml")
    p = Path(path)
    if not p.exists():
        return HubConfig()
    data = tomllib.loads(p.read_text())
    hub = data.get("hub", {})
    return HubConfig(
        **{k: v for k, v in hub.items() if k in HubConfig.__dataclass_fields__},
        mqtt=MqttConfig(**data.get("mqtt", {})),
        pc=PcConfig(**data.get("pc", {})),
    )
