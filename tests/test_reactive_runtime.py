from __future__ import annotations

import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from ops.config import Settings
from ops.runtime.incidents import ReactiveIncident
from ops.runtime.reactive import (
    acquire_lock,
    load_state,
    mark_emitted,
    release_lock,
    save_state,
    should_emit,
    state_file,
)


def _settings(tmp: str, **overrides: str) -> Settings:
    base = {
        "PROJECT_ROOT": tmp,
        "REACTIVE_WATCH_STATE_FILE": "./runtime/reports/ia-ops/reactive-watch-state.json",
        "REACTIVE_WATCH_LOCK_FILE": "./runtime/reports/ia-ops/reactive-watch.lock",
    }
    base.update(overrides)
    return Settings(config_file=Path(tmp) / "fake.env", values=base)


def _incident(key: str = "test-incident", severity: str = "warning") -> ReactiveIncident:
    return ReactiveIncident(key=key, service="test-svc", severity=severity, summary="test incident")


class StateFileTests(unittest.TestCase):
    def test_load_state_returns_empty_incidents_when_no_file(self) -> None:
        with TemporaryDirectory() as tmp:
            settings = _settings(tmp)
            state = load_state(settings)
            self.assertEqual(state, {"incidents": {}})

    def test_save_and_load_state_round_trips(self) -> None:
        with TemporaryDirectory() as tmp:
            settings = _settings(tmp)
            payload = {"incidents": {"test-key": {"severity": "warning"}}}
            save_state(settings, payload)
            loaded = load_state(settings)
            self.assertEqual(loaded["incidents"]["test-key"]["severity"], "warning")

    def test_load_state_returns_empty_on_corrupt_json(self) -> None:
        with TemporaryDirectory() as tmp:
            settings = _settings(tmp)
            target = state_file(settings)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("not valid json{{{", encoding="utf-8")
            state = load_state(settings)
            self.assertEqual(state, {"incidents": {}})


class ShouldEmitTests(unittest.TestCase):
    def test_should_emit_true_when_no_prior_state(self) -> None:
        with TemporaryDirectory() as tmp:
            settings = _settings(tmp)
            state: dict[str, object] = {"incidents": {}}
            self.assertTrue(should_emit(settings, state, _incident()))

    def test_should_emit_false_within_cooldown(self) -> None:
        with TemporaryDirectory() as tmp:
            settings = _settings(tmp, REACTIVE_ALERT_COOLDOWN_MINUTES="60")
            incident = _incident()
            state: dict[str, object] = {"incidents": {incident.key: {"last_sent_epoch": 1000000}}}
            self.assertFalse(should_emit(settings, state, incident, now_epoch=1000000 + 1800))

    def test_should_emit_true_after_cooldown(self) -> None:
        with TemporaryDirectory() as tmp:
            settings = _settings(tmp, REACTIVE_ALERT_COOLDOWN_MINUTES="30")
            incident = _incident()
            state: dict[str, object] = {"incidents": {incident.key: {"last_sent_epoch": 1000000}}}
            self.assertTrue(should_emit(settings, state, incident, now_epoch=1000000 + 1801))


class MarkEmittedTests(unittest.TestCase):
    def test_mark_emitted_records_incident(self) -> None:
        with TemporaryDirectory() as tmp:
            settings = _settings(tmp)
            state: dict[str, object] = {"incidents": {}}
            incident = _incident()
            updated = mark_emitted(settings, state, incident, now_epoch=1234567890)
            self.assertIn(incident.key, updated["incidents"])
            self.assertEqual(updated["incidents"][incident.key]["last_sent_epoch"], 1234567890)


class LockTests(unittest.TestCase):
    def test_acquire_and_release_lock(self) -> None:
        with TemporaryDirectory() as tmp:
            settings = _settings(tmp)
            lock = acquire_lock(settings)
            self.assertIsNotNone(lock)
            self.assertTrue(lock.exists())
            release_lock(lock)
            self.assertFalse(lock.exists())

    def test_acquire_fails_when_lock_exists(self) -> None:
        with TemporaryDirectory() as tmp:
            settings = _settings(tmp)
            lock1 = acquire_lock(settings)
            self.assertIsNotNone(lock1)
            lock2 = acquire_lock(settings)
            self.assertIsNone(lock2)
            release_lock(lock1)


if __name__ == "__main__":
    unittest.main()
