"""In-memory device registry fed by MQTT heartbeats/status."""

from __future__ import annotations

import threading
import time
from dataclasses import asdict, dataclass, field
from typing import Any


@dataclass
class Node:
    id: str
    online: bool = False
    last_seen: float = 0.0
    status: dict[str, Any] = field(default_factory=dict)


class Registry:
    def __init__(self) -> None:
        self._nodes: dict[str, Node] = {}
        self._lock = threading.Lock()

    def touch(self, node_id: str, status: dict[str, Any] | None = None, online: bool = True):
        with self._lock:
            node = self._nodes.setdefault(node_id, Node(id=node_id))
            node.online = online
            node.last_seen = time.time()
            if status:
                node.status.update(status)
            return node

    def list(self) -> list[dict[str, Any]]:
        with self._lock:
            return [asdict(n) for n in self._nodes.values()]
