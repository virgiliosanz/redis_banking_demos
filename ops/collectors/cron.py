from __future__ import annotations

import re

from ..config import Settings
from ..runtime.heartbeats import read_heartbeat
from ..services import compose_service_name
from ..util.docker import service_logs
from ..util.thresholds import severity_from_thresholds
from ..util.time import epoch_now, utc_timestamp


def _job_specs(settings: Settings) -> list[tuple[str, int, int, str]]:
    editorial = settings.get("CRON_JOB_EDITORIAL_SYNC", "sync-editorial-users")
    platform = settings.get("CRON_JOB_PLATFORM_SYNC", "sync-platform-config")
    rollover = settings.get("CRON_JOB_ROLLOVER", "rollover-content-year")

    return [
        (
            editorial,
            settings.get_int("CRON_JOB_EDITORIAL_SYNC_WARNING_MINUTES", 1440),
            settings.get_int("CRON_JOB_EDITORIAL_SYNC_CRITICAL_MINUTES", 2880),
            settings.get("CRON_JOB_EDITORIAL_SYNC_MISSING_STATUS", "warning") or "warning",
        ),
        (
            platform,
            settings.get_int("CRON_JOB_PLATFORM_SYNC_WARNING_MINUTES", 1440),
            settings.get_int("CRON_JOB_PLATFORM_SYNC_CRITICAL_MINUTES", 2880),
            settings.get("CRON_JOB_PLATFORM_SYNC_MISSING_STATUS", "warning") or "warning",
        ),
        (
            rollover,
            settings.get_int("CRON_JOB_ROLLOVER_WARNING_MINUTES", 525600),
            settings.get_int("CRON_JOB_ROLLOVER_CRITICAL_MINUTES", 527040),
            settings.get("CRON_JOB_ROLLOVER_MISSING_STATUS", "info") or "info",
        ),
    ]


def collect(settings: Settings) -> dict[str, object]:
    heartbeat_dir = settings.get_path("CRON_HEARTBEAT_DIR", "./runtime/heartbeats")
    if not heartbeat_dir.is_absolute():
        heartbeat_dir = settings.project_root.resolve() / heartbeat_dir
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
    logs = service_logs(compose_service_name("cron-master"), tail_lines=log_lines, cwd=settings.project_root.resolve())
    recent_error_count = len(re.findall(r"ERROR|FATAL|CRITICAL", logs, flags=re.IGNORECASE))
    warning_log_count = settings.get_int("CRON_LOG_ERRORS_WARNING_COUNT", 1)
    critical_log_count = settings.get_int("CRON_LOG_ERRORS_CRITICAL_COUNT", 5)
    log_status = (
        "critical"
        if recent_error_count >= critical_log_count
        else "warning"
        if recent_error_count >= warning_log_count
        else "ok"
    )

    return {
        "generated_at": utc_timestamp(),
        "heartbeat_dir": str(heartbeat_dir),
        "jobs": jobs,
        "recent_log_errors": {
            "count": recent_error_count,
            "status": log_status,
            "pattern": "ERROR|FATAL|CRITICAL",
            "source": compose_service_name("cron-master"),
            "tail_lines": log_lines,
            "warning_threshold": warning_log_count,
            "critical_threshold": critical_log_count,
        },
    }
