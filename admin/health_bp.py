"""Health summary API blueprint.

Aggregates service states, recent incidents and cron heartbeat ages
into a single JSON response consumed by the dashboard landing page.
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path

from flask import Blueprint, jsonify

from .runner import run_cli
from .containers import get_compose_root

import logging

logger = logging.getLogger(__name__)

bp = Blueprint("health", __name__)

_REPORT_DIR_IAOPS = Path(__file__).parent.parent / "runtime" / "reports" / "ia-ops"

# Services we expect to see in docker compose
_EXPECTED_SERVICES = [
    "lb-nginx",
    "fe-live",
    "fe-archive",
    "be-admin",
    "db-live",
    "db-archive",
    "elastic",
    "cron-master",
]

# Cron jobs we monitor and their thresholds (warning_min, critical_min)
_CRON_JOBS = {
    "nightly": ("nightly-auditor", 1440, 2880),
    "reactive": ("reactive-watch", 10, 30),
    "sync": ("sync-editorial-users", 1440, 2880),
    "metrics": ("collect-metrics", 5, 15),
    "cleanup": ("cleanup-data", 2880, 4320),
}


def _collect_service_states() -> list[dict[str, str]]:
    """Query docker compose for container states."""
    compose_root = get_compose_root()
    result = run_cli(
        ["docker", "compose", "ps", "--format", "json"],
        timeout=10,
        cwd=str(compose_root),
    )
    container_map: dict[str, dict] = {}
    if result.success:
        for line in result.stdout.strip().split("\n"):
            if not line:
                continue
            try:
                svc = json.loads(line)
            except json.JSONDecodeError:
                continue
            name = svc.get("Service") or svc.get("Name", "unknown")
            state = svc.get("State", "unknown")
            health = svc.get("Health", "")
            container_map[name] = {"state": state, "health": health}

    services = []
    for name in _EXPECTED_SERVICES:
        info = container_map.get(name, {})
        state = info.get("state", "unknown")
        health = info.get("health", "")
        if state == "running" and health in ("", "healthy"):
            status = "ok"
        elif state == "running" and health == "unhealthy":
            status = "warning"
        elif state in ("unknown",):
            status = "unknown"
        else:
            status = "critical"
        services.append({"name": name, "status": status, "state": state, "health": health})
    return services


def _collect_recent_incidents() -> list[dict]:
    """Read reactive-watch-state.json for recent incidents."""
    state_path = _REPORT_DIR_IAOPS / "reactive-watch-state.json"
    if not state_path.is_file():
        return []
    try:
        data = json.loads(state_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return []
    incidents_dict = data.get("incidents", {})
    if not isinstance(incidents_dict, dict):
        return []
    incidents = []
    for key, info in incidents_dict.items():
        if not isinstance(info, dict):
            continue
        incidents.append({
            "key": key,
            "service": info.get("service", "unknown"),
            "severity": info.get("severity", "unknown"),
            "summary": info.get("summary", ""),
            "last_sent_at": info.get("last_sent_at", ""),
        })
    # Sort newest first
    incidents.sort(key=lambda x: x.get("last_sent_at", ""), reverse=True)
    return incidents[:20]


def _collect_cron_health() -> list[dict]:
    """Read heartbeat files and compute cron job health."""
    heartbeat_dir = Path(__file__).parent.parent / "runtime" / "heartbeats"
    now = datetime.now(timezone.utc).timestamp()
    jobs = []
    for label, (job_name, warn_min, crit_min) in _CRON_JOBS.items():
        hb_file = heartbeat_dir / f"{job_name}.success"
        age_minutes = None
        if hb_file.is_file():
            try:
                raw = hb_file.read_text(encoding="utf-8").strip()
                if raw.isdigit():
                    last_epoch = int(raw)
                    if last_epoch > 0:
                        age_minutes = round((now - last_epoch) / 60, 1)
            except OSError:
                pass

        if age_minutes is None:
            status = "unknown"
        elif age_minutes >= crit_min:
            status = "critical"
        elif age_minutes >= warn_min:
            status = "warning"
        else:
            status = "ok"

        jobs.append({
            "label": label,
            "job_name": job_name,
            "age_minutes": age_minutes,
            "warning_minutes": warn_min,
            "critical_minutes": crit_min,
            "status": status,
        })
    return jobs


def _wp_indicator(value: int | float, thresholds: tuple[int, int]) -> str:
    """Return 'ok', 'warning' or 'critical' for a numeric value."""
    warn, crit = thresholds
    if value > crit:
        return "critical"
    if value >= warn:
        return "warning"
    return "ok"


def _collect_wordpress_health() -> dict | None:
    """Collect WordPress health indicators from the WordPress collector.

    Returns a dict with cron_overdue, updates_pending, php_errors and
    db_autoload indicators, each with value and status.  Returns *None*
    when the collector is unavailable or data cannot be gathered.
    """
    try:
        from ops.config import load_settings
        from ops.collectors import wordpress as wordpress_collector

        settings = load_settings()
        raw = wordpress_collector.collect(settings)
    except Exception:
        logger.debug("WordPress collector unavailable", exc_info=True)
        return None

    live = raw.get("live")
    if not isinstance(live, dict) or "error" in live:
        return None

    cron = live.get("cron") or {}
    updates = live.get("updates") or {}
    errors = live.get("errors") or {}
    database = live.get("database") or {}

    cron_overdue = int(cron.get("overdue", 0))
    updates_pending = int(updates.get("plugins", 0)) + int(updates.get("themes", 0))
    php_errors = int(errors.get("php_error_count", 0))
    db_autoload = int(database.get("autoload_count", 0))

    return {
        "cron_overdue": {
            "value": cron_overdue,
            "status": _wp_indicator(cron_overdue, (1, 5)),
        },
        "updates_pending": {
            "value": updates_pending,
            "status": _wp_indicator(updates_pending, (1, 3)),
        },
        "php_errors": {
            "value": php_errors,
            "status": _wp_indicator(php_errors, (1, 10)),
        },
        "db_autoload": {
            "value": db_autoload,
            "status": _wp_indicator(db_autoload, (500, 1000)),
        },
    }


@bp.route("/api/health-summary")
def api_health_summary():
    """Aggregated health summary for the dashboard landing page."""
    timestamp = datetime.now(timezone.utc).isoformat()
    try:
        services = _collect_service_states()
    except Exception:
        services = None
    incidents = _collect_recent_incidents()
    cron_jobs = _collect_cron_health()

    try:
        wordpress = _collect_wordpress_health()
    except Exception:
        wordpress = None

    return jsonify({
        "timestamp": timestamp,
        "services": services,
        "incidents": incidents,
        "cron_jobs": cron_jobs,
        "wordpress": wordpress,
    })
