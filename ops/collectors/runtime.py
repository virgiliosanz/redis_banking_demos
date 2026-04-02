from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import re
from pathlib import Path

from ..config import DEFAULT_ARCHIVE_URL, DEFAULT_BASE_URL, Settings
from ..services import container_name, service_keys
from ..util.docker import service_logs
from ..util.http import get_status_code
from ..util.process import run_command
from ..util.time import utc_timestamp


def _inspect_container(container: str) -> dict[str, object]:
    payload = run_command(["docker", "inspect", container]).json()[0]
    state = payload["State"]
    health = state.get("Health", {})
    return {
        "container_name": payload["Name"],
        "running": state["Running"],
        "status": state["Status"],
        "health_status": health.get("Status", state["Status"]),
        "started_at": state["StartedAt"],
        "finished_at": state["FinishedAt"],
    }


def _nginx_status_count(log_tail: str, *, status_prefix: str, window_minutes: int, now: datetime | None = None) -> int:
    current = now or datetime.now(timezone.utc)
    cutoff = current - timedelta(minutes=window_minutes)
    count = 0

    for line in log_tail.splitlines():
        timestamp_match = re.search(r"\[(\d{2}/[A-Za-z]{3}/\d{4}:\d{2}:\d{2}:\d{2} [+-]\d{4})\]", line)
        status_match = re.search(r'"[^"]+" (\d{3}) ', line)
        if timestamp_match is None or status_match is None:
            continue
        if not status_match.group(1).startswith(status_prefix):
            continue

        try:
            timestamp = datetime.strptime(timestamp_match.group(1), "%d/%b/%Y:%H:%M:%S %z")
        except ValueError:
            continue

        if timestamp >= cutoff:
            count += 1

    return count


def collect(settings: Settings) -> dict[str, object]:
    project_root = settings.project_root.resolve()
    containers = [container_name(settings, service_key) for service_key in service_keys()]

    with ThreadPoolExecutor(max_workers=len(containers)) as pool:
        rows = list(pool.map(_inspect_container, containers))
    base_url = settings.get("BASE_URL", DEFAULT_BASE_URL)
    archive_url = settings.get("ARCHIVE_URL", DEFAULT_ARCHIVE_URL)

    live_health_code = get_status_code(f"{base_url}/healthz")
    archive_health_code = get_status_code(f"{archive_url}/healthz")
    log_tail = service_logs("lb-nginx", tail_lines=settings.get_int("LOG_TAIL_LINES", 500), cwd=project_root)
    log_window_minutes = settings.get_int("LB_NGINX_STATUS_WINDOW_MINUTES", 15)
    recent_4xx_count = _nginx_status_count(log_tail, status_prefix="4", window_minutes=log_window_minutes)
    recent_5xx_count = _nginx_status_count(log_tail, status_prefix="5", window_minutes=log_window_minutes)
    warning_4xx = settings.get_int("LB_NGINX_4XX_WARNING_COUNT", 20)
    critical_4xx = settings.get_int("LB_NGINX_4XX_CRITICAL_COUNT", 50)
    warning_5xx = settings.get_int("LB_NGINX_5XX_WARNING_COUNT", 1)
    critical_5xx = settings.get_int("LB_NGINX_5XX_CRITICAL_COUNT", 10)

    return {
        "generated_at": utc_timestamp(),
        "containers": rows,
        "checks": {
            "live_healthz": {
                "url": f"{base_url}/healthz",
                "http_code": live_health_code,
                "expected_http_code": 200,
                "status": "ok" if live_health_code == 200 else "critical",
            },
            "archive_healthz": {
                "url": f"{archive_url}/healthz",
                "http_code": archive_health_code,
                "expected_http_code": 200,
                "status": "ok" if archive_health_code == 200 else "critical",
            },
            "lb_nginx_recent_4xx": {
                "count": recent_4xx_count,
                "window_minutes": log_window_minutes,
                "warning_threshold": warning_4xx,
                "critical_threshold": critical_4xx,
                "source": "lb-nginx logs",
                "status": "critical" if recent_4xx_count >= critical_4xx else "warning" if recent_4xx_count >= warning_4xx else "ok",
            },
            "lb_nginx_recent_5xx": {
                "count": recent_5xx_count,
                "window_minutes": log_window_minutes,
                "warning_threshold": warning_5xx,
                "critical_threshold": critical_5xx,
                "source": "lb-nginx logs",
                "status": "critical" if recent_5xx_count >= critical_5xx else "warning" if recent_5xx_count >= warning_5xx else "ok",
            },
        },
    }
