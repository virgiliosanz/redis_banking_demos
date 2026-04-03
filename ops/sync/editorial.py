from __future__ import annotations

from pathlib import Path

from ..config import Settings
from .common import ensure_sync_mode, markdown_header, sync_report_path, wait_for_sync_services, wp_eval_json, write_sync_heartbeat


def run(settings: Settings, *, mode: str, report_dir: Path | None = None) -> Path:
    mode = ensure_sync_mode(mode)
    cwd = settings.project_root.resolve()
    excluded_logins = settings.get("SYNC_EXCLUDE_USER_LOGINS", "n9liveadmin,n9archiveadmin")
    target_report_dir = report_dir or settings.get_path("REPORT_DIR", "./runtime/reports/sync")
    if not target_report_dir.is_absolute():
        target_report_dir = cwd / target_report_dir

    wait_for_sync_services(settings)

    source_snapshot = wp_eval_json(
        cwd=cwd,
        context="live",
        script_path="/opt/project/scripts/internal/sync/editorial/source-snapshot.php",
        excluded_logins=excluded_logins,
    )
    sanitized_source_snapshot = wp_eval_json(
        cwd=cwd,
        context="live",
        script_path="/opt/project/scripts/internal/sync/editorial/snapshot.php",
        excluded_logins=excluded_logins,
    )
    plan_json = wp_eval_json(
        cwd=cwd,
        context="archive",
        script_path="/opt/project/scripts/internal/sync/editorial/plan.php",
        snapshot_json=source_snapshot,
        excluded_logins=excluded_logins,
    )

    apply_json = ""
    if mode == "apply":
        apply_json = wp_eval_json(
            cwd=cwd,
            context="archive",
            script_path="/opt/project/scripts/internal/sync/editorial/apply.php",
            snapshot_json=source_snapshot,
            excluded_logins=excluded_logins,
        )
        write_sync_heartbeat(settings, "CRON_JOB_EDITORIAL_SYNC", "sync-editorial-users")

    report_file = sync_report_path(target_report_dir, "editorial-sync", mode)
    parts = [
        markdown_header("Editorial sync", mode=mode, excluded_logins=excluded_logins),
        "## Source snapshot",
        "```json",
        sanitized_source_snapshot,
        "```",
        "",
        "## Plan",
        "```json",
        plan_json,
        "```",
        "",
    ]
    if apply_json:
        parts.extend(
            [
                "## Apply result",
                "```json",
                apply_json,
                "```",
                "",
            ]
        )

    report_file.write_text("\n".join(parts), encoding="utf-8")
    return report_file
