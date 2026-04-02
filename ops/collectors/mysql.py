from __future__ import annotations

from pathlib import Path

from ..config import Settings
from ..util.docker import compose_exec
from ..util.time import utc_timestamp


def _mysql_shell(service: str, secret_path: str, sql: str, *, cwd: Path) -> str:
    escaped_sql = sql.replace("\\", "\\\\").replace('"', '\\"')
    command = [
        "sh",
        "-lc",
        (
            f'MYSQL_PWD="$(cat {secret_path})" '
            # 127.0.0.1 refers to the loopback inside the container (via docker compose exec)
            f'mysql -h 127.0.0.1 -uroot --batch --raw --skip-column-names -e "{escaped_sql}"'
        ),
    ]
    return compose_exec(service, command, cwd=cwd).stdout


def _mysql_ping(service: str, secret_path: str, *, cwd: Path) -> tuple[str, str]:
    command = [
        "sh",
        "-lc",
        f'MYSQL_PWD="$(cat {secret_path})" mysqladmin ping -h 127.0.0.1 -uroot --silent',
    ]
    result = compose_exec(service, command, cwd=cwd, check=False)
    output = (result.stdout or result.stderr).strip()
    return ("ok" if result.returncode == 0 else "critical", output or "mysql ping failed")


def _processlist_queries(service: str, secret_path: str, *, cwd: Path, warning_seconds: int, limit: int) -> list[dict[str, object]]:
    sql = f"""
SELECT
  ID,
  USER,
  IFNULL(DB, ''),
  COMMAND,
  TIME,
  IFNULL(STATE, ''),
  REPLACE(REPLACE(LEFT(IFNULL(INFO, ''), 240), CHAR(10), ' '), CHAR(9), ' ')
FROM information_schema.PROCESSLIST
WHERE
  ID <> CONNECTION_ID()
  AND COMMAND NOT IN ('Sleep', 'Daemon')
  AND TIME >= {warning_seconds}
ORDER BY TIME DESC
LIMIT {limit};
""".strip()
    raw = _mysql_shell(service, secret_path, sql, cwd=cwd)
    rows: list[dict[str, object]] = []
    for line in raw.splitlines():
        parts = line.split("\t")
        if len(parts) != 7:
            continue
        query_id, user, db_name, command, time_s, state, info = parts
        rows.append(
            {
                "id": int(query_id),
                "user": user,
                "db": db_name,
                "command": command,
                "time_seconds": int(time_s),
                "state": state,
                "query_excerpt": info,
            }
        )
    return rows


def _database_snapshot(settings: Settings, *, service: str, secret_path: str) -> dict[str, object]:
    cwd = settings.project_root.resolve()
    warning_seconds = settings.get_int("MYSQL_PROCESSLIST_WARNING_SECONDS", 30)
    critical_seconds = settings.get_int("MYSQL_PROCESSLIST_CRITICAL_SECONDS", 120)
    query_limit = settings.get_int("MYSQL_PROCESSLIST_LIMIT", 10)

    ping_status, ping_output = _mysql_ping(service, secret_path, cwd=cwd)
    queries: list[dict[str, object]] = []
    if ping_status == "ok":
        queries = _processlist_queries(service, secret_path, cwd=cwd, warning_seconds=warning_seconds, limit=query_limit)

    critical_count = sum(1 for row in queries if row["time_seconds"] >= critical_seconds)
    status = (
        "critical"
        if ping_status != "ok" or critical_count > 0
        else "warning"
        if queries
        else "ok"
    )

    return {
        "service": service,
        "ping": {
            "status": ping_status,
            "output": ping_output,
        },
        "processlist": {
            "status": status,
            "warning_seconds": warning_seconds,
            "critical_seconds": critical_seconds,
            "query_limit": query_limit,
            "warning_count": len(queries),
            "critical_count": critical_count,
            "queries": queries,
        },
    }


def collect(settings: Settings) -> dict[str, object]:
    live_secret = settings.get("DB_LIVE_ROOT_SECRET_PATH", "/run/secrets/db_live_mysql_root_password")
    archive_secret = settings.get("DB_ARCHIVE_ROOT_SECRET_PATH", "/run/secrets/db_archive_mysql_root_password")

    return {
        "generated_at": utc_timestamp(),
        "databases": [
            _database_snapshot(settings, service="db-live", secret_path=live_secret),
            _database_snapshot(settings, service="db-archive", secret_path=archive_secret),
        ],
    }
