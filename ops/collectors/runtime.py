from __future__ import annotations

import re
from pathlib import Path

from ..config import Settings
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


def collect(settings: Settings) -> dict[str, object]:
    project_root = settings.project_root.resolve()
    containers = [
        settings.get("CONTAINER_LB_NGINX", "n9-lb-nginx") or "n9-lb-nginx",
        settings.get("CONTAINER_FE_LIVE", "n9-fe-live") or "n9-fe-live",
        settings.get("CONTAINER_FE_ARCHIVE", "n9-fe-archive") or "n9-fe-archive",
        settings.get("CONTAINER_BE_ADMIN", "n9-be-admin") or "n9-be-admin",
        settings.get("CONTAINER_DB_LIVE", "n9-db-live") or "n9-db-live",
        settings.get("CONTAINER_DB_ARCHIVE", "n9-db-archive") or "n9-db-archive",
        settings.get("CONTAINER_ELASTIC", "n9-elastic") or "n9-elastic",
        settings.get("CONTAINER_CRON_MASTER", "n9-cron-master") or "n9-cron-master",
    ]

    rows = [_inspect_container(container) for container in containers]
    base_url = settings.get("BASE_URL", "http://nuevecuatrouno.test") or "http://nuevecuatrouno.test"
    archive_url = settings.get("ARCHIVE_URL", "http://archive.nuevecuatrouno.test") or "http://archive.nuevecuatrouno.test"

    live_health_code = get_status_code(f"{base_url}/healthz")
    archive_health_code = get_status_code(f"{archive_url}/healthz")
    log_tail = service_logs("lb-nginx", tail_lines=settings.get_int("LOG_TAIL_LINES", 500), cwd=project_root)
    recent_5xx_count = len(re.findall(r"(?:^|\s)5\d\d(?:\s|$)", log_tail))

    return {
        "generated_at": utc_timestamp(),
        "containers": rows,
        "checks": {
            "live_healthz": {
                "http_code": live_health_code,
                "status": "ok" if live_health_code == 200 else "critical",
            },
            "archive_healthz": {
                "http_code": archive_health_code,
                "status": "ok" if archive_health_code == 200 else "critical",
            },
            "lb_nginx_recent_5xx": {
                "count": recent_5xx_count,
                "status": "critical" if recent_5xx_count >= 10 else "warning" if recent_5xx_count > 0 else "ok",
            },
        },
    }
