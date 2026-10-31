"""Entrypoint: `pyra-hub` console script."""

from __future__ import annotations

import logging

import uvicorn

from .api import create_app
from .config import load_config
from .mqtt_bridge import MqttBridge
from .registry import Registry


def run() -> None:
    logging.basicConfig(level=logging.INFO)
    cfg = load_config()
    if cfg.auth_token == "CHANGE_ME":
        raise SystemExit("Set hub.auth_token in config.toml before starting")
    if not cfg.node.hub_token or cfg.node.hub_token.startswith("CHANGE_ME"):
        logging.warning("node.hub_token not set: pc.power_pulse will be refused (503)")
    if not cfg.timers_file:
        logging.warning("hub.timers_file not set: timers are kept in memory only")
    bridge = MqttBridge(cfg.mqtt, Registry())
    bridge.start()
    try:
        uvicorn.run(create_app(cfg, bridge), host=cfg.host, port=cfg.port)
    finally:
        bridge.stop()


if __name__ == "__main__":
    run()
