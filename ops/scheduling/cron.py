from __future__ import annotations

import os
from pathlib import Path

from ..config import Settings
from ..reporting import ensure_directory, write_text_report
from ..util.process import run_command
from ..util.time import report_stamp


MANAGED_BLOCK_NAME = "NUEVECUATROUNO_IA_OPS_NIGHTLY"
REACTIVE_MANAGED_BLOCK_NAME = "NUEVECUATROUNO_IA_OPS_REACTIVE"
SYNC_MANAGED_BLOCK_NAME = "NUEVECUATROUNO_IA_OPS_SYNC"
METRICS_MANAGED_BLOCK_NAME = "NUEVECUATROUNO_IA_OPS_METRICS"


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
    cron_hour = settings.get_int("NIGHTLY_AUDITOR_CRON_HOUR", 5)
    cron_minute = settings.get_int("NIGHTLY_AUDITOR_CRON_MINUTE", 15)
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


def render_reactive_watch_block(settings: Settings, *, project_root: Path, python_bin: str = "python3") -> str:
    interval_minutes = settings.get_int("REACTIVE_WATCH_CRON_INTERVAL_MINUTES", 5)
    config_file = settings.config_file.resolve()
    log_file = settings.get_path("REACTIVE_WATCH_LOG_FILE", "./runtime/reports/ia-ops/reactive-watch.cron.log")
    log_file = (project_root / log_file).resolve() if not log_file.is_absolute() else log_file

    command = (
        f"cd {project_root} && "
        f"IA_OPS_CONFIG_FILE={config_file} "
        f"{python_bin} -m ops.cli.ia_ops run-reactive-watch >> {log_file} 2>&1"
    )

    return "\n".join(
        [
            f"# BEGIN {REACTIVE_MANAGED_BLOCK_NAME}",
            "SHELL=/bin/sh",
            f"PATH={_default_path()}",
            f"*/{interval_minutes} * * * * {command}",
            f"# END {REACTIVE_MANAGED_BLOCK_NAME}",
            "",
        ]
    )


def render_sync_jobs_block(settings: Settings, *, project_root: Path, python_bin: str = "python3") -> str:
    editorial_hour = settings.get_int("SYNC_EDITORIAL_CRON_HOUR", 4)
    editorial_minute = settings.get_int("SYNC_EDITORIAL_CRON_MINUTE", 15)
    platform_hour = settings.get_int("SYNC_PLATFORM_CRON_HOUR", 4)
    platform_minute = settings.get_int("SYNC_PLATFORM_CRON_MINUTE", 45)
    editorial_mode = settings.get("SYNC_EDITORIAL_CRON_MODE", "apply")
    platform_mode = settings.get("SYNC_PLATFORM_CRON_MODE", "apply")
    config_file = settings.config_file.resolve()
    editorial_log_file = settings.get_path("SYNC_EDITORIAL_LOG_FILE", "./runtime/reports/sync/editorial-sync.cron.log")
    platform_log_file = settings.get_path("SYNC_PLATFORM_LOG_FILE", "./runtime/reports/sync/platform-sync.cron.log")
    editorial_log_file = (project_root / editorial_log_file).resolve() if not editorial_log_file.is_absolute() else editorial_log_file
    platform_log_file = (project_root / platform_log_file).resolve() if not platform_log_file.is_absolute() else platform_log_file

    editorial_command = (
        f"cd {project_root} && "
        f"IA_OPS_CONFIG_FILE={config_file} "
        f"{python_bin} -m ops.cli.ia_ops sync-editorial-users --mode {editorial_mode} >> {editorial_log_file} 2>&1"
    )
    platform_command = (
        f"cd {project_root} && "
        f"IA_OPS_CONFIG_FILE={config_file} "
        f"{python_bin} -m ops.cli.ia_ops sync-platform-config --mode {platform_mode} >> {platform_log_file} 2>&1"
    )

    return "\n".join(
        [
            f"# BEGIN {SYNC_MANAGED_BLOCK_NAME}",
            "SHELL=/bin/sh",
            f"PATH={_default_path()}",
            f"{editorial_minute} {editorial_hour} * * * {editorial_command}",
            f"{platform_minute} {platform_hour} * * * {platform_command}",
            f"# END {SYNC_MANAGED_BLOCK_NAME}",
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


def _strip_named_block(content: str, *, block_name: str) -> str:
    lines = content.splitlines()
    kept: list[str] = []
    inside = False
    begin = f"# BEGIN {block_name}"
    end = f"# END {block_name}"

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


def _install_crontab_file(crontab_file: Path, *, project_root: Path) -> None:
    try:
        relative = os.path.relpath(crontab_file, project_root)
    except ValueError:
        relative = str(crontab_file)

    if relative.startswith(".."):
        target = str(crontab_file)
        cwd = None
    else:
        target = relative
        cwd = project_root

    run_command(["crontab", target], cwd=cwd)


def _remove_user_crontab() -> None:
    result = run_command(["crontab", "-r"], check=False)
    stderr = result.stderr.lower()
    stdout = result.stdout.lower()
    if result.returncode == 0:
        return
    if "no crontab" in stderr or "no crontab" in stdout:
        return
    raise RuntimeError(f"Unable to remove current crontab: {result.stderr or result.stdout}")


def _resolve_report_root(settings: Settings, *, project_root: Path) -> Path:
    report_root = settings.get_path("REPORT_ROOT", "./runtime/reports/ia-ops")
    report_root = (project_root / report_root).resolve() if not report_root.is_absolute() else report_root
    ensure_directory(report_root)
    return report_root


def _install_managed_crontab(
    settings: Settings,
    *,
    project_root: Path,
    block_name: str,
    managed_block: str,
    file_prefix: str,
) -> tuple[Path, Path]:
    current = read_user_crontab()
    report_root = _resolve_report_root(settings, project_root=project_root)

    backup_file = write_text_report(report_root, f"crontab-backup-{report_stamp()}.txt", current)
    updated = _strip_named_block(current, block_name=block_name).rstrip()
    if updated:
        updated = f"{updated}\n\n{managed_block}"
    else:
        updated = managed_block

    crontab_file = report_root / f"crontab-{file_prefix}-{report_stamp()}.txt"
    crontab_file.write_text(updated, encoding="utf-8")
    _install_crontab_file(crontab_file, project_root=project_root)
    return backup_file, crontab_file


def _remove_managed_crontab(
    settings: Settings,
    *,
    project_root: Path,
    block_name: str,
    file_prefix: str,
) -> Path:
    current = read_user_crontab()
    updated = _strip_named_block(current, block_name=block_name)
    report_root = _resolve_report_root(settings, project_root=project_root)

    crontab_file = report_root / f"crontab-{file_prefix}-{report_stamp()}.txt"
    crontab_file.write_text(updated, encoding="utf-8")
    if updated.strip():
        _install_crontab_file(crontab_file, project_root=project_root)
    else:
        _remove_user_crontab()
    return crontab_file


def install_nightly_auditor_crontab(settings: Settings, *, project_root: Path, python_bin: str = "python3") -> tuple[Path, Path]:
    managed_block = render_nightly_auditor_block(settings, project_root=project_root, python_bin=python_bin)
    return _install_managed_crontab(
        settings, project_root=project_root, block_name=MANAGED_BLOCK_NAME, managed_block=managed_block, file_prefix="install",
    )


def install_reactive_watch_crontab(settings: Settings, *, project_root: Path, python_bin: str = "python3") -> tuple[Path, Path]:
    managed_block = render_reactive_watch_block(settings, project_root=project_root, python_bin=python_bin)
    return _install_managed_crontab(
        settings, project_root=project_root, block_name=REACTIVE_MANAGED_BLOCK_NAME, managed_block=managed_block, file_prefix="reactive-install",
    )


def install_sync_jobs_crontab(settings: Settings, *, project_root: Path, python_bin: str = "python3") -> tuple[Path, Path]:
    managed_block = render_sync_jobs_block(settings, project_root=project_root, python_bin=python_bin)
    return _install_managed_crontab(
        settings, project_root=project_root, block_name=SYNC_MANAGED_BLOCK_NAME, managed_block=managed_block, file_prefix="sync-install",
    )


def remove_nightly_auditor_crontab(settings: Settings, *, project_root: Path) -> Path:
    return _remove_managed_crontab(settings, project_root=project_root, block_name=MANAGED_BLOCK_NAME, file_prefix="remove")


def remove_reactive_watch_crontab(settings: Settings, *, project_root: Path) -> Path:
    return _remove_managed_crontab(settings, project_root=project_root, block_name=REACTIVE_MANAGED_BLOCK_NAME, file_prefix="reactive-remove")


def remove_sync_jobs_crontab(settings: Settings, *, project_root: Path) -> Path:
    return _remove_managed_crontab(settings, project_root=project_root, block_name=SYNC_MANAGED_BLOCK_NAME, file_prefix="sync-remove")


def render_metrics_collector_block(settings: Settings, *, project_root: Path, python_bin: str = "python3") -> str:
    interval_minutes = settings.get_int("METRICS_COLLECTOR_CRON_INTERVAL_MINUTES", 1)
    config_file = settings.config_file.resolve()
    log_file = settings.get_path("METRICS_COLLECTOR_LOG_FILE", "./runtime/reports/ia-ops/metrics-collector.cron.log")
    log_file = (project_root / log_file).resolve() if not log_file.is_absolute() else log_file

    command = (
        f"cd {project_root} && "
        f"IA_OPS_CONFIG_FILE={config_file} "
        f"{python_bin} -m ops.cli.ia_ops collect-metrics >> {log_file} 2>&1"
    )

    return "\n".join(
        [
            f"# BEGIN {METRICS_MANAGED_BLOCK_NAME}",
            "SHELL=/bin/sh",
            f"PATH={_default_path()}",
            f"*/{interval_minutes} * * * * {command}",
            f"# END {METRICS_MANAGED_BLOCK_NAME}",
            "",
        ]
    )


def install_metrics_collector_crontab(settings: Settings, *, project_root: Path, python_bin: str = "python3") -> tuple[Path, Path]:
    managed_block = render_metrics_collector_block(settings, project_root=project_root, python_bin=python_bin)
    return _install_managed_crontab(
        settings, project_root=project_root, block_name=METRICS_MANAGED_BLOCK_NAME, managed_block=managed_block, file_prefix="metrics-install",
    )


def remove_metrics_collector_crontab(settings: Settings, *, project_root: Path) -> Path:
    return _remove_managed_crontab(settings, project_root=project_root, block_name=METRICS_MANAGED_BLOCK_NAME, file_prefix="metrics-remove")
