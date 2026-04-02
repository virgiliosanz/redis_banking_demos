from __future__ import annotations

from pathlib import Path

from ..config import Settings
from ..runtime.heartbeats import write_heartbeat
from ..util.docker import compose_exec, wait_for_container_health
from ..util.time import report_stamp, utc_timestamp


VALID_SYNC_MODES = {"report-only", "dry-run", "apply"}


def ensure_sync_mode(mode: str) -> str:
    if mode not in VALID_SYNC_MODES:
        raise ValueError(f"Unsupported mode: {mode}")
    return mode


def wait_for_sync_services() -> None:
    wait_for_container_health("n9-db-live")
    wait_for_container_health("n9-db-archive")
    wait_for_container_health("n9-cron-master")


def wp_eval_json(
    *,
    cwd: Path,
    path: str,
    script_path: str,
    snapshot_json: str | None = None,
    excluded_logins: str | None = None,
) -> str:
    env_args = []
    if excluded_logins:
        env_args.append(f"SYNC_EXCLUDE_USER_LOGINS={excluded_logins}")
    if snapshot_json:
        env_args.append(f"SYNC_SOURCE_SNAPSHOT_JSON={snapshot_json}")

    command = ["env", *env_args, "wp", "--allow-root", "eval-file", script_path, f"--path={path}"]
    result = compose_exec("cron-master", command, cwd=cwd, exec_args=["--user", "root"])
    return result.stdout.strip()


def sync_report_path(report_dir: Path, prefix: str, mode: str) -> Path:
    report_dir.mkdir(parents=True, exist_ok=True)
    return report_dir / f"{prefix}-{mode}-{report_stamp()}.md"


def write_sync_heartbeat(settings: Settings, job_name_key: str, default_job_name: str) -> Path:
    heartbeat_dir = settings.get_path("CRON_HEARTBEAT_DIR", "./runtime/heartbeats")
    heartbeat_dir = settings.project_root.resolve() / heartbeat_dir if not heartbeat_dir.is_absolute() else heartbeat_dir
    job_name = settings.get(job_name_key, default_job_name) or default_job_name
    return write_heartbeat(heartbeat_dir, job_name)


def markdown_header(title: str, *, mode: str, excluded_logins: str | None = None) -> str:
    lines = [
        f"# {title} {mode}",
        "",
        f"- generated_at: {utc_timestamp()}",
        f"- mode: {mode}",
    ]
    if excluded_logins is not None:
        lines.append(f"- excluded_bootstrap_logins: {excluded_logins}")
    lines.append("")
    return "\n".join(lines)
