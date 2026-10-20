"""paho-mqtt bridge to the local Mosquitto broker."""

from __future__ import annotations

import json
import logging
from typing import Any

from .config import MqttConfig
from .registry import Registry

log = logging.getLogger(__name__)


class MqttBridge:
    def __init__(self, cfg: MqttConfig, registry: Registry) -> None:
        self.cfg = cfg
        self.registry = registry
        self.client = None

    def start(self) -> None:
        import paho.mqtt.client as mqtt

        c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="pyra-hub")
        if self.cfg.username:
            c.username_pw_set(self.cfg.username, self.cfg.password)
        c.on_connect = self._on_connect
        c.on_message = self._on_message
        c.connect_async(self.cfg.host, self.cfg.port, keepalive=30)
        c.loop_start()
        self.client = c

    def stop(self) -> None:
        if self.client:
            self.client.loop_stop()
            self.client.disconnect()

    def _on_connect(self, client, userdata, flags, reason_code, properties=None):
        p = self.cfg.topic_prefix
        client.subscribe([(f"{p}/+/status", 1), (f"{p}/+/heartbeat", 0)])
        log.info("mqtt connected: %s", reason_code)

    def _on_message(self, client, userdata, msg):
        parts = msg.topic.split("/")
        if len(parts) < 4:
            return
        node_id, kind = parts[-2], parts[-1]
        try:
            payload: dict[str, Any] = json.loads(msg.payload or b"{}")
        except json.JSONDecodeError:
            payload = {"raw": msg.payload.decode(errors="replace")}
        online = not (kind == "status" and payload.get("state") == "offline")
        self.registry.touch(node_id, payload, online=online)

    def publish_cmd(self, node_id: str, payload: dict[str, Any]) -> bool:
        if not self.client:
            return False
        topic = f"{self.cfg.topic_prefix}/{node_id}/cmd"
        self.client.publish(topic, json.dumps(payload), qos=1)
        return True
