from __future__ import annotations

from dataclasses import dataclass
import json
import os
from pathlib import Path

from ..config import Settings
from ..context import collect_operational_context, load_drift_status
from ..reporting import ensure_directory
from ..services import inspect_name_map
from .drift import format_drift_summary
from .incidents import ReactiveIncident, build_reactive_incidents
from ..util.time import epoch_now, utc_timestamp


def state_file(settings: Settings) -> Path:
    path = settings.get_path("REACTIVE_WATCH_STATE_FILE", "./runtime/reports/ia-ops/reactive-watch-state.json")
    return (settings.project_root / path).resolve() if not path.is_absolute() else path


def lock_file(settings: Settings) -> Path:
    path = settings.get_path("REACTIVE_WATCH_LOCK_FILE", "./runtime/reports/ia-ops/reactive-watch.lock")
    return (settings.project_root / path).resolve() if not path.is_absolute() else path


def load_state(settings: Settings) -> dict[str, object]:
    target = state_file(settings)
    if not target.exists():
        return {"incidents": {}}
    try:
        return json.loads(target.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        return {"incidents": {}}


def save_state(settings: Settings, payload: dict[str, object]) -> Path:
    target = state_file(settings)
    ensure_directory(target.parent)
    target.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return target


def should_emit(settings: Settings, state: dict[str, object], incident: ReactiveIncident, *, now_epoch: int | None = None) -> bool:
    incidents = state.get("incidents", {})
    if not isinstance(incidents, dict):
        return True
    row = incidents.get(incident.key)
    if not isinstance(row, dict):
        return True
    last_sent_epoch = row.get("last_sent_epoch")
    if not isinstance(last_sent_epoch, int):
        return True
    cooldown_minutes = settings.get_int("REACTIVE_ALERT_COOLDOWN_MINUTES", 30)
    current_epoch = now_epoch if now_epoch is not None else epoch_now()
    return current_epoch - last_sent_epoch >= cooldown_minutes * 60


def mark_emitted(settings: Settings, state: dict[str, object], incident: ReactiveIncident, *, now_epoch: int | None = None) -> dict[str, object]:
    payload = dict(state)
    incidents = payload.get("incidents")
    if not isinstance(incidents, dict):
        incidents = {}
    incidents = dict(incidents)
    current_epoch = now_epoch if now_epoch is not None else epoch_now()
    incidents[incident.key] = {
        "service": incident.service,
        "severity": incident.severity,
        "summary": incident.summary,
        "last_sent_epoch": current_epoch,
        "last_sent_at": utc_timestamp(),
    }
    payload["incidents"] = incidents
    return payload


def acquire_lock(settings: Settings) -> Path | None:
    target = lock_file(settings)
    ensure_directory(target.parent)
    stale_seconds = settings.get_int("REACTIVE_WATCH_LOCK_STALE_SECONDS", 900)
    try:
        fd = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    except FileExistsError:
        age_seconds = epoch_now() - int(target.stat().st_mtime)
        if age_seconds <= stale_seconds:
            return None
        target.unlink(missing_ok=True)
        fd = os.open(target, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(f"{os.getpid()}\n")
    return target


def release_lock(target: Path | None) -> None:
    if target is None:
        return
    target.unlink(missing_ok=True)


def evaluate(settings: Settings) -> dict[str, object]:
    context = collect_operational_context(settings)
    drift = load_drift_status(settings)
    context["service_map"] = inspect_name_map(settings)
    incidents = build_reactive_incidents(
        context,
        editorial_drift=drift.editorial.status,
        platform_drift=drift.platform.status,
    )

    return {
        "generated_at": context["generated_at"],
        "host": context["host"],
        "runtime": context["runtime"],
        "app": context["app"],
        "mysql": context["mysql"],
        "elastic": context["elastic"],
        "cron": context["cron"],
        "drift": {
            "report_file": drift.report_file,
            "editorial_drift": drift.editorial.status,
            "platform_drift": drift.platform.status,
            "editorial_summary": drift.editorial.summary,
            "platform_summary": drift.platform.summary,
            "editorial_brief": format_drift_summary(drift.editorial),
            "platform_brief": format_drift_summary(drift.platform),
        },
        "incidents": [
            {
                "key": incident.key,
                "service": incident.service,
                "severity": incident.severity,
                "summary": incident.summary,
                "pattern": incident.pattern,
            }
            for incident in incidents
        ],
    }
