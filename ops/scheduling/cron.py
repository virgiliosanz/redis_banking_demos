from __future__ import annotations

from pathlib import Path

from ..config import Settings
from ..reporting import ensure_directory, write_text_report
from ..util.process import run_command
from ..util.time import report_stamp


MANAGED_BLOCK_NAME = "NUEVECUATROUNO_IA_OPS_NIGHTLY"


def _default_path() -> str:
    return ":".join(
        [
        "/opt/homebrew/bin",
        "/usr/local/bin",
        "/usr/bin",
        "/bin",
        "/usr/sbin",
        "/sbin",
        ]
    )


def render_nightly_auditor_block(settings: Settings, *, project_root: Path, python_bin: str = "python3") -> str:
    cron_hour = settings.get_int("NIGHTLY_AUDITOR_CRON_HOUR", 2)
    cron_minute = settings.get_int("NIGHTLY_AUDITOR_CRON_MINUTE", 0)
    config_file = settings.config_file.resolve()
    log_file = settings.get_path("NIGHTLY_AUDITOR_LOG_FILE", "./runtime/reports/ia-ops/nightly-auditor.cron.log")
    log_file = (project_root / log_file).resolve() if not log_file.is_absolute() else log_file

    command = (
        f"cd {project_root} && "
        f"IA_OPS_CONFIG_FILE={config_file} "
        f"{python_bin} -m ops.cli.ia_ops run-nightly-auditor >> {log_file} 2>&1"
    )

    return "\n".join(
        [
            f"# BEGIN {MANAGED_BLOCK_NAME}",
            "SHELL=/bin/sh",
            f"PATH={_default_path()}",
            f"{cron_minute} {cron_hour} * * * {command}",
            f"# END {MANAGED_BLOCK_NAME}",
            "",
        ]
    )


def read_user_crontab() -> str:
    result = run_command(["crontab", "-l"], check=False)
    if result.returncode == 0:
        return result.stdout

    stderr = result.stderr.lower()
    stdout = result.stdout.lower()
    if "no crontab" in stderr or "no crontab" in stdout:
        return ""

    raise RuntimeError(f"Unable to read current crontab: {result.stderr or result.stdout}")


def _strip_managed_block(content: str) -> str:
    lines = content.splitlines()
    kept: list[str] = []
    inside = False
    begin = f"# BEGIN {MANAGED_BLOCK_NAME}"
    end = f"# END {MANAGED_BLOCK_NAME}"

    for line in lines:
        if line.strip() == begin:
            inside = True
            continue
        if line.strip() == end:
            inside = False
            continue
        if not inside:
            kept.append(line)

    stripped = "\n".join(kept).strip()
    return f"{stripped}\n" if stripped else ""


def install_nightly_auditor_crontab(settings: Settings, *, project_root: Path, python_bin: str = "python3") -> tuple[Path, Path]:
    current = read_user_crontab()
    report_root = settings.get_path("REPORT_ROOT", "./runtime/reports/ia-ops")
    report_root = (project_root / report_root).resolve() if not report_root.is_absolute() else report_root
    ensure_directory(report_root)

    backup_file = write_text_report(report_root, f"crontab-backup-{report_stamp()}.txt", current)
    managed_block = render_nightly_auditor_block(settings, project_root=project_root, python_bin=python_bin)
    updated = _strip_managed_block(current).rstrip()
    if updated:
        updated = f"{updated}\n\n{managed_block}"
    else:
        updated = managed_block

    crontab_file = report_root / f"crontab-install-{report_stamp()}.txt"
    crontab_file.write_text(updated, encoding="utf-8")
    run_command(["crontab", str(crontab_file)])
    return backup_file, crontab_file


def remove_nightly_auditor_crontab(settings: Settings, *, project_root: Path) -> Path:
    current = read_user_crontab()
    updated = _strip_managed_block(current)
    report_root = settings.get_path("REPORT_ROOT", "./runtime/reports/ia-ops")
    report_root = (project_root / report_root).resolve() if not report_root.is_absolute() else report_root
    ensure_directory(report_root)

    crontab_file = report_root / f"crontab-remove-{report_stamp()}.txt"
    crontab_file.write_text(updated, encoding="utf-8")
    run_command(["crontab", str(crontab_file)])
    return crontab_file
