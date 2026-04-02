from __future__ import annotations

from dataclasses import dataclass
import json
import os
from pathlib import Path

from ..config import Settings
from ..collectors import cron as cron_collector
from ..collectors import elastic as elastic_collector
from ..collectors import runtime as runtime_collector
from ..reporting import ensure_directory
from ..util.time import epoch_now, utc_timestamp


@dataclass(frozen=True)
class ReactiveIncident:
    key: str
    service: str
    severity: str
    summary: str
    pattern: str | None = None


def _reports_root(settings: Settings) -> Path:
    report_root = settings.get_path("REPORT_ROOT", "./runtime/reports/ia-ops")
    return (settings.project_root / report_root).resolve() if not report_root.is_absolute() else report_root


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
    runtime = runtime_collector.collect(settings)
    elastic = elastic_collector.collect(settings)
    cron = cron_collector.collect(settings)

    incidents: list[ReactiveIncident] = []
    service_map = {
        "/n9-lb-nginx": "lb-nginx",
        "/n9-fe-live": "fe-live",
        "/n9-fe-archive": "fe-archive",
        "/n9-be-admin": "be-admin",
        "/n9-db-live": "db-live",
        "/n9-db-archive": "db-archive",
        "/n9-elastic": "elastic",
        "/n9-cron-master": "cron-master",
    }

    for row in runtime["containers"]:
        health_status = row["health_status"]
        container_name = row["container_name"]
        service = service_map.get(container_name)
        if service is None:
            continue
        if health_status in {"unhealthy", "exited", "dead"}:
            severity = "critical" if health_status != "unhealthy" else "warning"
            incidents.append(
                ReactiveIncident(
                    key=f"{service}:health:{health_status}",
                    service=service,
                    severity=severity,
                    summary=f"{service} con estado {health_status}",
                )
            )

    recent_4xx = runtime["checks"]["lb_nginx_recent_4xx"]
    if recent_4xx["status"] != "ok":
        incidents.append(
            ReactiveIncident(
                key=f"lb-nginx:4xx:{recent_4xx['status']}",
                service="lb-nginx",
                severity=recent_4xx["status"],
                summary=f"lb-nginx acumula {recent_4xx['count']} respuestas 4xx recientes",
                pattern=" 4\\d\\d ",
            )
        )

    recent_5xx = runtime["checks"]["lb_nginx_recent_5xx"]
    if recent_5xx["status"] != "ok":
        incidents.append(
            ReactiveIncident(
                key=f"lb-nginx:5xx:{recent_5xx['status']}",
                service="lb-nginx",
                severity=recent_5xx["status"],
                summary=f"lb-nginx acumula {recent_5xx['count']} respuestas 5xx recientes",
                pattern=" 5\\d\\d ",
            )
        )

    if elastic["alias"]["status"] != "ok":
        incidents.append(
            ReactiveIncident(
                key="elastic:alias:missing",
                service="elastic",
                severity="critical",
                summary="alias de lectura de Elasticsearch ausente",
            )
        )

    delayed_jobs = [row["job_name"] for row in cron["jobs"] if row["status"] in {"warning", "critical"}]
    if delayed_jobs:
        severity = "critical" if any(row["status"] == "critical" for row in cron["jobs"]) else "warning"
        incidents.append(
            ReactiveIncident(
                key=f"cron-master:delayed:{severity}",
                service="cron-master",
                severity=severity,
                summary=f"cron-master tiene jobs fuera de ventana: {', '.join(delayed_jobs)}",
                pattern="ERROR|FATAL|CRITICAL",
            )
        )

    return {
        "generated_at": utc_timestamp(),
        "runtime": runtime,
        "elastic": elastic,
        "cron": cron,
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
