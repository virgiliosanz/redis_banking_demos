from __future__ import annotations

import re
from pathlib import Path

from ..config import Settings
from ..runtime.heartbeats import read_heartbeat
from ..util.docker import service_logs
from ..util.thresholds import severity_from_thresholds
from ..util.time import epoch_now, utc_timestamp


def _job_specs(settings: Settings) -> list[tuple[str, int, int, str]]:
    editorial = settings.get("CRON_JOB_EDITORIAL_SYNC", "sync-editorial-users")
    platform = settings.get("CRON_JOB_PLATFORM_SYNC", "sync-platform-config")
    rollover = settings.get("CRON_JOB_ROLLOVER", "rollover-content-year")

    default_warning = settings.get_int("CRON_WARNING_DELAY_MINUTES", 30)
    default_critical = settings.get_int("CRON_CRITICAL_DELAY_MINUTES", 120)

    return [
        (
            editorial,
            settings.get_int("CRON_JOB_EDITORIAL_SYNC_WARNING_MINUTES", default_warning),
            settings.get_int("CRON_JOB_EDITORIAL_SYNC_CRITICAL_MINUTES", default_critical),
            settings.get("CRON_JOB_EDITORIAL_SYNC_MISSING_STATUS", "warning") or "warning",
        ),
        (
            platform,
            settings.get_int("CRON_JOB_PLATFORM_SYNC_WARNING_MINUTES", default_warning),
            settings.get_int("CRON_JOB_PLATFORM_SYNC_CRITICAL_MINUTES", default_critical),
            settings.get("CRON_JOB_PLATFORM_SYNC_MISSING_STATUS", "warning") or "warning",
        ),
        (
            rollover,
            settings.get_int("CRON_JOB_ROLLOVER_WARNING_MINUTES", default_warning),
            settings.get_int("CRON_JOB_ROLLOVER_CRITICAL_MINUTES", default_critical),
            settings.get("CRON_JOB_ROLLOVER_MISSING_STATUS", "info") or "info",
        ),
    ]


def collect(settings: Settings) -> dict[str, object]:
    heartbeat_dir = settings.get_path("CRON_HEARTBEAT_DIR", "./runtime/heartbeats")
    now_epoch = epoch_now()
    jobs: list[dict[str, object]] = []

    for job_name, warning_threshold, critical_threshold, missing_status in _job_specs(settings):
        heartbeat = read_heartbeat(heartbeat_dir, job_name)
        delay = heartbeat.age_minutes(now_epoch=now_epoch)
        if delay is None:
            status = missing_status
            source = "heartbeat_missing"
            last_success_epoch = None
        else:
            status = severity_from_thresholds(delay, warning=warning_threshold, critical=critical_threshold)
            source = "heartbeat"
            last_success_epoch = heartbeat.last_success_epoch

        jobs.append(
            {
                "job_name": job_name,
                "source": source,
                "last_success_epoch": last_success_epoch,
                "delay_minutes": delay,
                "warning_minutes": warning_threshold,
                "critical_minutes": critical_threshold,
                "status": status,
            }
        )

    log_lines = settings.get_int("LOG_TAIL_LINES", 500)
    logs = service_logs("cron-master", tail_lines=log_lines, cwd=Path.cwd())
    recent_error_count = len(re.findall(r"ERROR|FATAL|CRITICAL", logs, flags=re.IGNORECASE))
    log_status = "critical" if recent_error_count >= 5 else "warning" if recent_error_count > 0 else "ok"

    return {
        "generated_at": utc_timestamp(),
        "heartbeat_dir": str(heartbeat_dir),
        "jobs": jobs,
        "recent_log_errors": {
            "count": recent_error_count,
            "status": log_status,
        },
    }
