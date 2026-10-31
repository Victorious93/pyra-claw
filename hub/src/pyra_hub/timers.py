"""Hub-side timers and sequences (protocol v0.3).

The hub is the authoritative scheduler: timers live in a JSON file on the Pi and fire
from a background thread, so they run whether or not any phone is awake.

Schedules:
- ``once``: fire at ``at`` (ISO 8601; a naive value is the hub's local time).
- ``daily``: every day at ``time`` ("HH:MM", hub local time).
- ``weekly``: at ``time`` on ``days`` (0 = Monday ... 6 = Sunday).
- ``manual``: never fires on its own; use run-now.

A timer runs its ``steps`` in order. Each step waits ``delay_s`` seconds *before* it
runs, so ``[charge.on phone1, delay 30 → charge.on phone2]`` staggers two phones.
Missed runs (hub down at fire time) are skipped, not replayed.
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
import uuid
from collections.abc import Callable
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Literal

from pydantic import BaseModel, Field, field_validator, model_validator

log = logging.getLogger(__name__)

MAX_STEPS = 32
MAX_DELAY_S = 6 * 3600


class Step(BaseModel):
    command: str
    target: str | None = None
    args: dict[str, Any] = Field(default_factory=dict)
    delay_s: float = Field(default=0, ge=0, le=MAX_DELAY_S)


class Schedule(BaseModel):
    kind: Literal["once", "daily", "weekly", "manual"] = "manual"
    at: str | None = None
    time: str | None = None
    days: list[int] = Field(default_factory=list)

    @field_validator("time")
    @classmethod
    def _hhmm(cls, v: str | None) -> str | None:
        if v is None:
            return v
        hh, mm = v.split(":")
        if not (0 <= int(hh) < 24 and 0 <= int(mm) < 60):
            raise ValueError("time must be HH:MM")
        return f"{int(hh):02d}:{int(mm):02d}"

    @field_validator("days")
    @classmethod
    def _days(cls, v: list[int]) -> list[int]:
        if any(d < 0 or d > 6 for d in v):
            raise ValueError("days are 0 (Mon) .. 6 (Sun)")
        return sorted(set(v))

    @model_validator(mode="after")
    def _complete(self) -> Schedule:
        if self.kind == "once":
            if not self.at:
                raise ValueError("once needs 'at'")
            datetime.fromisoformat(self.at)
        if self.kind in ("daily", "weekly") and not self.time:
            raise ValueError(f"{self.kind} needs 'time'")
        if self.kind == "weekly" and not self.days:
            raise ValueError("weekly needs 'days'")
        return self

    def next_after(self, after: datetime) -> datetime | None:
        """Next fire time strictly after ``after`` (an aware datetime)."""
        tz = after.tzinfo
        if self.kind == "manual":
            return None
        if self.kind == "once":
            at = datetime.fromisoformat(self.at)  # type: ignore[arg-type]
            if at.tzinfo is None:
                at = at.replace(tzinfo=tz)
            return at if at > after else None
        hh, mm = (int(x) for x in self.time.split(":"))  # type: ignore[union-attr]
        for add in range(0, 8):
            day = (after + timedelta(days=add)).date()
            cand = datetime(day.year, day.month, day.day, hh, mm, tzinfo=tz)
            if cand <= after:
                continue
            if self.kind == "weekly" and cand.weekday() not in self.days:
                continue
            return cand
        return None


class TimerIn(BaseModel):
    name: str = Field(min_length=1, max_length=80)
    enabled: bool = True
    schedule: Schedule = Field(default_factory=Schedule)
    steps: list[Step] = Field(min_length=1, max_length=MAX_STEPS)
    approval_token: str | None = None  # only for timers with approval-required steps


class Timer(BaseModel):
    id: str = Field(default_factory=lambda: uuid.uuid4().hex[:12])
    name: str
    enabled: bool = True
    schedule: Schedule
    steps: list[Step]
    preapproved: bool = False
    preapproved_at: float | None = None
    created_at: float = Field(default_factory=time.time)
    updated_at: float = Field(default_factory=time.time)
    next_run: float | None = None
    last_run: float | None = None
    last_result: str | None = None
    running: bool = False
    current_step: int | None = None


class TimerStore:
    """Timers persisted as one JSON document, written atomically (tmp + rename)."""

    def __init__(self, path: str | None) -> None:
        self.path = Path(path) if path else None
        self.lock = threading.RLock()
        self.timers: dict[str, Timer] = {}
        if self.path and self.path.exists():
            data = json.loads(self.path.read_text() or "{}")
            for raw in data.get("timers", []):
                t = Timer.model_validate(raw)
                t.running, t.current_step = False, None
                self.timers[t.id] = t

    def save(self) -> None:
        if not self.path:
            return
        with self.lock:
            doc = {"version": 1, "timers": [t.model_dump() for t in self.timers.values()]}
            self.path.parent.mkdir(parents=True, exist_ok=True)
            tmp = self.path.with_suffix(self.path.suffix + ".tmp")
            tmp.write_text(json.dumps(doc, indent=2))
            os.replace(tmp, self.path)


StepRunner = Callable[[Timer, int, Step], dict[str, Any]]
Notify = Callable[[dict[str, Any]], None]


def local_now() -> datetime:
    return datetime.now().astimezone()


class Scheduler:
    def __init__(
        self,
        store: TimerStore,
        run_step: StepRunner,
        notify: Notify | None = None,
        clock: Callable[[], datetime] = local_now,
        sleep: Callable[[float], None] = time.sleep,
        tick_s: float = 1.0,
    ) -> None:
        self.store, self.run_step = store, run_step
        self.notify = notify or (lambda ev: None)
        self.clock, self.sleep, self.tick_s = clock, sleep, tick_s
        self.events: list[dict[str, Any]] = []
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.recompute_all()

    # ---- state -----------------------------------------------------------------------
    def _event(self, kind: str, timer: Timer, **fields: Any) -> None:
        ev = {"ts": time.time(), "event": kind, "timer_id": timer.id, "name": timer.name, **fields}
        self.events.append(ev)
        del self.events[:-100]
        self.notify(ev)

    def recompute(self, t: Timer) -> None:
        nxt = t.schedule.next_after(self.clock()) if t.enabled else None
        t.next_run = nxt.timestamp() if nxt else None

    def recompute_all(self) -> None:
        with self.store.lock:
            for t in self.store.timers.values():
                self.recompute(t)
            self.store.save()

    def snapshot(self) -> dict[str, Any]:
        with self.store.lock:
            return {
                "timers": [t.model_dump() for t in self.store.timers.values()],
                "events": self.events[-20:],
            }

    # ---- execution -------------------------------------------------------------------
    def run_now(self, timer_id: str, reason: str = "manual") -> bool:
        with self.store.lock:
            t = self.store.timers.get(timer_id)
            if t is None:
                raise KeyError(timer_id)
            if t.running:
                return False
            t.running, t.current_step = True, 0
        th = threading.Thread(target=self._run, args=(timer_id, reason), daemon=True)
        th.start()
        return True

    def _run(self, timer_id: str, reason: str) -> None:
        t = self.store.timers[timer_id]
        self._event("timer_started", t, reason=reason)
        result = "ok"
        for i, step in enumerate(list(t.steps)):
            if self._stop.is_set():
                result = "stopped"
                break
            if step.delay_s:
                self.sleep(step.delay_s)
            with self.store.lock:
                t.current_step = i
            try:
                out = self.run_step(t, i, step)
                self._event(
                    "step_ok", t, step=i, command=step.command, target=step.target, result=out
                )
            except Exception as e:  # noqa: BLE001 - report and stop the sequence
                result = f"failed at step {i}: {e}"
                self._event(
                    "step_failed", t, step=i, command=step.command, target=step.target, error=str(e)
                )
                break
        with self.store.lock:
            t.last_run, t.last_result = time.time(), result
            self._event("timer_finished", t, result=result)
            t.running, t.current_step = False, None
            self.recompute(t)
            self.store.save()

    def tick(self) -> list[str]:
        now = self.clock().timestamp()
        due: list[str] = []
        with self.store.lock:
            for t in self.store.timers.values():
                if t.enabled and t.next_run is not None and t.next_run <= now and not t.running:
                    due.append(t.id)
                    if t.schedule.kind == "once":
                        t.enabled = False
                    t.next_run = None
            if due:
                self.store.save()
        for tid in due:
            self.run_now(tid, reason="schedule")
        return due

    def _loop(self) -> None:
        while not self._stop.wait(self.tick_s):
            try:
                self.tick()
            except Exception:  # noqa: BLE001
                log.exception("scheduler tick failed")

    def start(self) -> None:
        if self._thread is None:
            self._thread = threading.Thread(target=self._loop, name="pyra-timers", daemon=True)
            self._thread.start()

    def stop(self) -> None:
        self._stop.set()
