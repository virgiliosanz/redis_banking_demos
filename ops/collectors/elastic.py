from __future__ import annotations

from pathlib import Path

from ..config import Settings
from ..util.docker import compose_exec
from ..util.time import utc_timestamp


def _curl_json(path: str) -> object:
    command = ["sh", "-lc", f"curl -fsS http://127.0.0.1:9200{path}"]
    return compose_exec("elastic", command, cwd=Path.cwd()).json()


def collect(settings: Settings) -> dict[str, object]:
    alias_name = settings.get("EP_SEARCH_ALIAS", "n9-search-posts") or "n9-search-posts"
    cluster_health = _curl_json("/_cluster/health")
    indices = _curl_json("/_cat/indices?format=json")

    alias_result = compose_exec(
        "elastic",
        ["sh", "-lc", f"curl -fsS http://127.0.0.1:9200/_cat/aliases/{alias_name}?format=json"],
        cwd=Path.cwd(),
        check=False,
    )
    alias_rows = alias_result.json() if alias_result.returncode == 0 and alias_result.stdout.strip() else []
    alias_present = bool(alias_rows)

    return {
        "generated_at": utc_timestamp(),
        "cluster_health": cluster_health,
        "indices": indices,
        "alias": {
            "present": alias_present,
            "rows": alias_rows,
            "status": "ok" if alias_present else "critical",
        },
    }
