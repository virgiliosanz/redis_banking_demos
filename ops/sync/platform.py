from __future__ import annotations

from pathlib import Path

from ..config import Settings
from .common import ensure_sync_mode, markdown_header, sync_report_path, wait_for_sync_services, wp_eval_json, write_sync_heartbeat


def run(settings: Settings, *, mode: str, report_dir: Path | None = None) -> Path:
    mode = ensure_sync_mode(mode)
    cwd = settings.project_root.resolve()
    target_report_dir = report_dir or settings.get_path("REPORT_DIR", "./runtime/reports/sync")
    if not target_report_dir.is_absolute():
        target_report_dir = cwd / target_report_dir

    wait_for_sync_services(settings)

    source_snapshot = wp_eval_json(
        cwd=cwd,
        path="/srv/wp/live",
        script_path="/opt/project/scripts/internal/sync/platform/source-snapshot.php",
    )
    sanitized_live_snapshot = wp_eval_json(
        cwd=cwd,
        path="/srv/wp/live",
        script_path="/opt/project/scripts/internal/sync/platform/snapshot.php",
    )
    sanitized_archive_snapshot_before = wp_eval_json(
        cwd=cwd,
        path="/srv/wp/archive",
        script_path="/opt/project/scripts/internal/sync/platform/snapshot.php",
    )
    plan_json = wp_eval_json(
        cwd=cwd,
        path="/srv/wp/archive",
        script_path="/opt/project/scripts/internal/sync/platform/plan.php",
        snapshot_json=source_snapshot,
    )

    apply_json = ""
    sanitized_archive_snapshot_after = ""
    if mode == "apply":
        apply_json = wp_eval_json(
            cwd=cwd,
            path="/srv/wp/archive",
            script_path="/opt/project/scripts/internal/sync/platform/apply.php",
            snapshot_json=source_snapshot,
        )
        sanitized_archive_snapshot_after = wp_eval_json(
            cwd=cwd,
            path="/srv/wp/archive",
            script_path="/opt/project/scripts/internal/sync/platform/snapshot.php",
        )
        write_sync_heartbeat(settings, "CRON_JOB_PLATFORM_SYNC", "sync-platform-config")

    report_file = sync_report_path(target_report_dir, "platform-sync", mode)
    parts = [
        markdown_header("Platform sync", mode=mode),
        "## Live platform snapshot",
        "```json",
        sanitized_live_snapshot,
        "```",
        "",
        "## Archive platform snapshot before",
        "```json",
        sanitized_archive_snapshot_before,
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
                "## Archive platform snapshot after",
                "```json",
                sanitized_archive_snapshot_after,
                "```",
                "",
            ]
        )

    report_file.write_text("\n".join(parts), encoding="utf-8")
    return report_file
