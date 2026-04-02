from __future__ import annotations

from pathlib import Path

from ..config import Settings
from ..services import compose_service_name
from ..util.docker import compose_exec
from ..util.time import utc_timestamp


def _curl_json(service: str, path: str, *, cwd: Path) -> object:
    # 127.0.0.1 refers to the loopback inside the container (via docker compose exec)
    command = ["sh", "-lc", f"curl -fsS http://127.0.0.1:9200{path}"]
    return compose_exec(service, command, cwd=cwd).json()


def _cluster_health_status(raw_status: object) -> str:
    if raw_status == "green":
        return "ok"
    if raw_status == "yellow":
        return "warning"
    return "critical"


def collect(settings: Settings) -> dict[str, object]:
    alias_name = settings.get("EP_SEARCH_ALIAS", "n9-search-posts")
    cwd = settings.project_root.resolve()
    elastic_service = compose_service_name("elastic")
    cluster_health = _curl_json(elastic_service, "/_cluster/health", cwd=cwd)
    indices = _curl_json(elastic_service, "/_cat/indices?format=json", cwd=cwd)

    alias_result = compose_exec(
        elastic_service,
        ["sh", "-lc", f"curl -fsS http://127.0.0.1:9200/_cat/aliases/{alias_name}?format=json"],
        cwd=cwd,
        check=False,
    )
    alias_rows = alias_result.json() if alias_result.returncode == 0 and alias_result.stdout.strip() else []
    alias_present = bool(alias_rows)
    cluster_raw_status = cluster_health.get("status", "unknown") if isinstance(cluster_health, dict) else "unknown"

    return {
        "generated_at": utc_timestamp(),
        "cluster_health": {
            **cluster_health,
            "collector_status": _cluster_health_status(cluster_raw_status),
            "source": "/_cluster/health",
        },
        "indices": indices,
        "alias": {
            "alias_name": alias_name,
            "present": alias_present,
            "rows": alias_rows,
            "source": f"/_cat/aliases/{alias_name}?format=json",
            "status": "ok" if alias_present else "critical",
        },
    }
