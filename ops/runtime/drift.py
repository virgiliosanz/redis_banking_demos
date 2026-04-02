from __future__ import annotations

from pathlib import Path

from ..config import Settings
from ..reporting import write_text_report
from ..util.docker import compose_exec, wait_for_container_health
from ..util.time import report_stamp, utc_timestamp


def _wp_eval_json(path: str, script_path: str, excluded_logins: str, *, cwd: Path) -> str:
    result = compose_exec(
        "cron-master",
        [
            "env",
            f"SYNC_EXCLUDE_USER_LOGINS={excluded_logins}",
            "wp",
            "--allow-root",
            "eval-file",
            script_path,
            f"--path={path}",
        ],
        cwd=cwd,
        exec_args=["--user", "root"],
    )
    return result.stdout.strip()


def build_drift_report(settings: Settings) -> tuple[str, str]:
    excluded_logins = settings.get("SYNC_EXCLUDE_USER_LOGINS", "n9liveadmin,n9archiveadmin") or "n9liveadmin,n9archiveadmin"
    report_dir = settings.get_path("REPORT_DIR", "./runtime/reports/sync")
    project_root = settings.project_root.resolve()

    for container in ("n9-db-live", "n9-db-archive", "n9-cron-master"):
        wait_for_container_health(container)

    live_editorial = _wp_eval_json(
        "/srv/wp/live",
        "/opt/project/scripts/sync-editorial-snapshot.php",
        excluded_logins,
        cwd=project_root,
    )
    archive_editorial = _wp_eval_json(
        "/srv/wp/archive",
        "/opt/project/scripts/sync-editorial-snapshot.php",
        excluded_logins,
        cwd=project_root,
    )
    live_platform = _wp_eval_json(
        "/srv/wp/live",
        "/opt/project/scripts/sync-platform-snapshot.php",
        excluded_logins,
        cwd=project_root,
    )
    archive_platform = _wp_eval_json(
        "/srv/wp/archive",
        "/opt/project/scripts/sync-platform-snapshot.php",
        excluded_logins,
        cwd=project_root,
    )

    editorial_drift = "no" if live_editorial == archive_editorial else "yes"
    platform_drift = "no" if live_platform == archive_platform else "yes"

    content = f"""# Drift report live/archive

- generated_at: {utc_timestamp()}
- excluded_bootstrap_logins: {excluded_logins}
- editorial_drift: {editorial_drift}
- platform_drift: {platform_drift}

## Live editorial snapshot
```json
{live_editorial}
```

## Archive editorial snapshot
```json
{archive_editorial}
```

## Live platform snapshot
```json
{live_platform}
```

## Archive platform snapshot
```json
{archive_platform}
```
"""
    report_file = write_text_report(report_dir, f"live-archive-sync-{report_stamp()}.md", content)
    return str(report_file), content
