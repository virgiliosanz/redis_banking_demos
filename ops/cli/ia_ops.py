from __future__ import annotations

import argparse
from dataclasses import replace
import sys
from pathlib import Path

from ..config import load_settings
from ..util.process import run_command
from ..collectors import host as host_collector
from ..collectors import logs as logs_collector
from ..collectors import metrics as metrics_collector
from ..context import collect_operational_context, load_drift_status
from ..notifications.telegram import load_telegram_config, send_message
from ..rollover import content_year as rollover_content_year
from ..reporting import write_json_report, write_text_report
from ..runtime import nightly as nightly_runtime
from ..runtime import reactive as reactive_runtime
from ..runtime import sentry as sentry_runtime
from ..runtime.heartbeats import write_heartbeat
from ..scheduling.cron import (
    install_cleanup_crontab,
    install_metrics_collector_crontab,
    install_nightly_auditor_crontab,
    install_reactive_watch_crontab,
    install_sync_jobs_crontab,
    remove_cleanup_crontab,
    remove_metrics_collector_crontab,
    remove_nightly_auditor_crontab,
    remove_reactive_watch_crontab,
    remove_sync_jobs_crontab,
    render_cleanup_block,
    render_metrics_collector_block,
    render_nightly_auditor_block,
    render_reactive_watch_block,
    render_sync_jobs_block,
)
from ..sync import editorial as editorial_sync
from ..sync import platform as platform_sync
from ..util.jsonio import dumps_pretty
from ..util.time import report_stamp


def _print_json(payload: object) -> None:
    print(dumps_pretty(payload))


def _send_telegram_preview(message: str) -> None:
    print(message, file=sys.stderr)


def _notify_telegram(settings, message: str, *, explicit: bool, preview: bool, default_enabled: bool) -> None:
    telegram = load_telegram_config(settings)
    should_notify = explicit or (telegram.enabled and default_enabled)

    if preview:
        _send_telegram_preview(message)
        return

    if not should_notify:
        return

    if explicit and not telegram.enabled:
        telegram = replace(telegram, enabled=True)

    result = send_message(telegram, message)
    message_id = result.get("result", {}).get("message_id", "unknown")
    print(f"telegram notification sent with message_id={message_id}", file=sys.stderr)


def _default_notify_allowed(args: argparse.Namespace) -> bool:
    if getattr(args, "no_notify_telegram", False):
        return False
    if getattr(args, "no_write_report", False):
        return False
    return True


def cmd_send_telegram_test(args: argparse.Namespace) -> int:
    settings = load_settings()
    message = args.message or "IA-Ops Telegram test desde nuevecuatrouno"
    if args.preview:
        _send_telegram_preview(message)
        return 0
    result = send_message(load_telegram_config(settings), message)
    message_id = result.get("result", {}).get("message_id", "unknown")
    print(f"telegram test sent with message_id={message_id}")
    return 0


def cmd_sync_editorial(args: argparse.Namespace) -> int:
    settings = load_settings()
    report_dir = Path(args.report_dir).resolve() if args.report_dir else None
    report_file = editorial_sync.run(settings, mode=args.mode, report_dir=report_dir)
    print(f"editorial sync {args.mode} report written to {report_file}")
    return 0


def cmd_sync_platform(args: argparse.Namespace) -> int:
    settings = load_settings()
    report_dir = Path(args.report_dir).resolve() if args.report_dir else None
    report_file = platform_sync.run(settings, mode=args.mode, report_dir=report_dir)
    print(f"platform sync {args.mode} report written to {report_file}")
    return 0


def cmd_rollover_content_year(args: argparse.Namespace) -> int:
    settings = load_settings()
    report_dir = Path(args.report_dir).resolve() if args.report_dir else None
    routing_config = Path(args.routing_config).resolve() if args.routing_config else None
    report_file = rollover_content_year.run(
        settings,
        mode=args.mode,
        target_year=args.year,
        report_dir=report_dir,
        routing_config_file=routing_config,
    )
    print(f"rollover {args.mode} report written to {report_file}")
    return 0


def cmd_collect_host(_: argparse.Namespace) -> int:
    _print_json(host_collector.collect(load_settings()))
    return 0


def cmd_collect_cron(_: argparse.Namespace) -> int:
    _print_json(collect_operational_context(load_settings())["cron"])
    return 0


def cmd_collect_elastic(_: argparse.Namespace) -> int:
    _print_json(collect_operational_context(load_settings())["elastic"])
    return 0


def cmd_collect_runtime(_: argparse.Namespace) -> int:
    _print_json(collect_operational_context(load_settings())["runtime"])
    return 0


def cmd_collect_app(_: argparse.Namespace) -> int:
    _print_json(collect_operational_context(load_settings())["app"])
    return 0


def cmd_collect_mysql(_: argparse.Namespace) -> int:
    _print_json(collect_operational_context(load_settings())["mysql"])
    return 0


def cmd_collect_metrics(_: argparse.Namespace) -> int:
    settings = load_settings()
    from ..metrics.storage import MetricsStore
    store = MetricsStore()
    try:
        result = metrics_collector.collect_and_store(settings, store)
    finally:
        store.close()
    heartbeat_dir = settings.get_path("CRON_HEARTBEAT_DIR", "./runtime/heartbeats")
    if not heartbeat_dir.is_absolute():
        heartbeat_dir = settings.project_root.resolve() / heartbeat_dir
    write_heartbeat(heartbeat_dir, "collect-metrics")
    _print_json(result)
    return 0


def cmd_collect_service_logs(args: argparse.Namespace) -> int:
    settings = load_settings()
    content = logs_collector.collect_service_logs(settings, args.service, args.pattern)
    if content:
        print(content, end="" if content.endswith("\n") else "\n")
    return 0


def cmd_collect_nightly_context(args: argparse.Namespace) -> int:
    settings = load_settings()
    payload = collect_operational_context(settings)
    if args.write_report:
        report_root = settings.get_path("REPORT_ROOT", "./runtime/reports/ia-ops")
        report_file = write_json_report(report_root, f"nightly-context-{report_stamp()}.json", payload)
        print(f"nightly context written to {report_file}", file=sys.stderr)
    _print_json(payload)
    return 0


def cmd_report_drift(_: argparse.Namespace) -> int:
    settings = load_settings()
    drift = load_drift_status(settings)
    print(f"sync drift report written to {drift.report_file}")
    return 0


def cmd_render_nightly_crontab(args: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    content = render_nightly_auditor_block(settings, project_root=project_root, python_bin=args.python_bin)
    print(content, end="")
    return 0


def cmd_render_reactive_crontab(args: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    content = render_reactive_watch_block(settings, project_root=project_root, python_bin=args.python_bin)
    print(content, end="")
    return 0


def cmd_render_sync_crontab(args: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    content = render_sync_jobs_block(settings, project_root=project_root, python_bin=args.python_bin)
    print(content, end="")
    return 0


def cmd_install_nightly_crontab(args: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    backup_file, crontab_file = install_nightly_auditor_crontab(settings, project_root=project_root, python_bin=args.python_bin)
    print(f"nightly auditor cron installed from {crontab_file}")
    print(f"previous crontab backed up to {backup_file}")
    return 0


def cmd_install_reactive_crontab(args: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    backup_file, crontab_file = install_reactive_watch_crontab(settings, project_root=project_root, python_bin=args.python_bin)
    print(f"reactive watch cron installed from {crontab_file}")
    print(f"previous crontab backed up to {backup_file}")
    return 0


def cmd_install_sync_crontab(args: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    backup_file, crontab_file = install_sync_jobs_crontab(settings, project_root=project_root, python_bin=args.python_bin)
    print(f"sync jobs cron installed from {crontab_file}")
    print(f"previous crontab backed up to {backup_file}")
    return 0


def cmd_remove_nightly_crontab(_: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    crontab_file = remove_nightly_auditor_crontab(settings, project_root=project_root)
    print(f"nightly auditor cron block removed using {crontab_file}")
    return 0


def cmd_remove_reactive_crontab(_: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    crontab_file = remove_reactive_watch_crontab(settings, project_root=project_root)
    print(f"reactive watch cron block removed using {crontab_file}")
    return 0


def cmd_remove_sync_crontab(_: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    crontab_file = remove_sync_jobs_crontab(settings, project_root=project_root)
    print(f"sync jobs cron block removed using {crontab_file}")
    return 0


def cmd_render_metrics_crontab(args: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    content = render_metrics_collector_block(settings, project_root=project_root, python_bin=args.python_bin)
    print(content, end="")
    return 0


def cmd_install_metrics_crontab(args: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    backup_file, crontab_file = install_metrics_collector_crontab(settings, project_root=project_root, python_bin=args.python_bin)
    print(f"metrics collector cron installed from {crontab_file}")
    print(f"previous crontab backed up to {backup_file}")
    return 0


def cmd_remove_metrics_crontab(_: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    crontab_file = remove_metrics_collector_crontab(settings, project_root=project_root)
    print(f"metrics collector cron block removed using {crontab_file}")
    return 0


def cmd_cleanup_data(_: argparse.Namespace) -> int:
    settings = load_settings()
    from ..metrics.storage import MetricsStore
    from admin.reports import cleanup_old_reports

    store = MetricsStore()
    try:
        aggregated = store.aggregate()
        purged_metrics = store.purge(max_age_hours=24)
    finally:
        store.close()

    report_result = cleanup_old_reports()

    heartbeat_dir = settings.get_path("CRON_HEARTBEAT_DIR", "./runtime/heartbeats")
    if not heartbeat_dir.is_absolute():
        heartbeat_dir = settings.project_root.resolve() / heartbeat_dir
    write_heartbeat(heartbeat_dir, "cleanup-data")

    payload = {
        "aggregated": aggregated,
        "purged_metrics": purged_metrics,
        "purged_reports": report_result["deleted"],
    }
    _print_json(payload)
    return 0


def cmd_render_cleanup_crontab(args: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    content = render_cleanup_block(settings, project_root=project_root, python_bin=args.python_bin)
    print(content, end="")
    return 0


def cmd_install_cleanup_crontab(args: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    backup_file, crontab_file = install_cleanup_crontab(settings, project_root=project_root, python_bin=args.python_bin)
    print(f"cleanup cron installed from {crontab_file}")
    print(f"previous crontab backed up to {backup_file}")
    return 0


def cmd_remove_cleanup_crontab(_: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    crontab_file = remove_cleanup_crontab(settings, project_root=project_root)
    print(f"cleanup cron block removed using {crontab_file}")
    return 0


def cmd_run_nightly(args: argparse.Namespace) -> int:
    settings = load_settings()
    assessment = nightly_runtime.build_nightly_assessment(settings)
    report = nightly_runtime.render_nightly_report(assessment)

    report_file: str | None = None
    if not args.no_write_report:
        report_root = settings.get_path("REPORT_ROOT", "./runtime/reports/ia-ops")
        report_path = write_text_report(report_root, f"nightly-auditor-{report_stamp()}.md", report)
        report_file = str(report_path)
        print(f"nightly auditor report written to {report_file}", file=sys.stderr)

    _notify_telegram(
        settings,
        nightly_runtime.render_nightly_telegram_message(assessment, report_file=report_file),
        explicit=args.notify_telegram,
        preview=args.telegram_preview,
        default_enabled=_default_notify_allowed(args) and load_telegram_config(settings).notify_on_nightly,
    )

    print(report)
    return 0


def cmd_run_sentry(args: argparse.Namespace) -> int:
    settings = load_settings()
    diagnosis = sentry_runtime.build_sentry_diagnosis(
        settings,
        args.service,
        pattern=args.pattern,
        summary_override=args.summary,
    )
    report = sentry_runtime.render_sentry_report(diagnosis)

    report_file: str | None = None
    if not args.no_write_report:
        report_root = settings.get_path("REPORT_ROOT", "./runtime/reports/ia-ops")
        report_path = write_text_report(report_root, f"sentry-{args.service}-{report_stamp()}.md", report)
        report_file = str(report_path)
        print(f"sentry report written to {report_file}", file=sys.stderr)

    _notify_telegram(
        settings,
        sentry_runtime.render_sentry_telegram_message(diagnosis, report_file=report_file),
        explicit=args.notify_telegram,
        preview=args.telegram_preview,
        default_enabled=_default_notify_allowed(args) and load_telegram_config(settings).notify_on_sentry,
    )
    print(report)
    return 0


def cmd_run_reactive_watch(args: argparse.Namespace) -> int:
    settings = load_settings()
    lock = reactive_runtime.acquire_lock(settings)
    if lock is None:
        print("reactive watch skipped: existing non-stale lock detected", file=sys.stderr)
        return 0

    try:
        evaluation = reactive_runtime.evaluate(settings)
        _incident_fields = {"key", "service", "severity", "summary", "pattern"}
        _required_fields = {"key", "service", "severity", "summary"}
        incidents: list[reactive_runtime.ReactiveIncident] = []
        for row in evaluation["incidents"]:
            if not isinstance(row, dict) or not _required_fields.issubset(row):
                print(f"reactive watch: skipping malformed incident row: {row!r}", file=sys.stderr)
                continue
            incidents.append(
                reactive_runtime.ReactiveIncident(**{k: v for k, v in row.items() if k in _incident_fields})
            )
        state = reactive_runtime.load_state(settings)
        now_epoch = None
        emitted: list[str] = []

        for incident in incidents:
            if not reactive_runtime.should_emit(settings, state, incident, now_epoch=now_epoch):
                continue

            command = [
                sys.executable or "python3",
                "-m",
                "ops.cli.ia_ops",
                "run-sentry-agent",
                "--service",
                incident.service,
                "--summary",
                incident.summary,
                "--notify-telegram",
            ]
            if incident.pattern:
                command.extend(["--pattern", incident.pattern])
            result = subprocess_run_sentry(command)
            if result:
                state = reactive_runtime.mark_emitted(settings, state, incident, now_epoch=now_epoch)
                emitted.append(incident.key)

        payload = {
            "generated_at": evaluation["generated_at"],
            "incidents_seen": evaluation["incidents"],
            "incidents_emitted": emitted,
        }
        if args.write_report:
            report_root = settings.get_path("REPORT_ROOT", "./runtime/reports/ia-ops")
            report_file = write_json_report(report_root, f"reactive-watch-{report_stamp()}.json", payload)
            print(f"reactive watch report written to {report_file}", file=sys.stderr)

        reactive_runtime.save_state(settings, state)
        _print_json(payload)
        return 0
    finally:
        reactive_runtime.release_lock(lock)


def subprocess_run_sentry(command: list[str]) -> bool:
    result = run_command(command, check=False)
    if result.returncode != 0:
        print(result.stderr or result.stdout, file=sys.stderr, end="" if (result.stderr or result.stdout).endswith("\n") else "\n")
        return False
    return True


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m ops.cli.ia_ops")
    subparsers = parser.add_subparsers(dest="command", required=True)
    python_bin_default = sys.executable or "python3"

    subparsers.add_parser("collect-host-health").set_defaults(func=cmd_collect_host)
    subparsers.add_parser("collect-cron-health").set_defaults(func=cmd_collect_cron)
    subparsers.add_parser("collect-elastic-health").set_defaults(func=cmd_collect_elastic)
    subparsers.add_parser("collect-runtime-health").set_defaults(func=cmd_collect_runtime)
    subparsers.add_parser("collect-app-health").set_defaults(func=cmd_collect_app)
    subparsers.add_parser("collect-mysql-health").set_defaults(func=cmd_collect_mysql)

    subparsers.add_parser("collect-metrics").set_defaults(func=cmd_collect_metrics)

    collect_logs = subparsers.add_parser("collect-service-logs")
    collect_logs.add_argument("service")
    collect_logs.add_argument("pattern", nargs="?")
    collect_logs.set_defaults(func=cmd_collect_service_logs)

    collect_nightly = subparsers.add_parser("collect-nightly-context")
    collect_nightly.add_argument("--write-report", action="store_true")
    collect_nightly.set_defaults(func=cmd_collect_nightly_context)

    drift = subparsers.add_parser("report-live-archive-sync-drift")
    drift.set_defaults(func=cmd_report_drift)

    render_cron = subparsers.add_parser("render-nightly-crontab")
    render_cron.add_argument("--python-bin", default=python_bin_default)
    render_cron.set_defaults(func=cmd_render_nightly_crontab)

    render_reactive_cron = subparsers.add_parser("render-reactive-crontab")
    render_reactive_cron.add_argument("--python-bin", default=python_bin_default)
    render_reactive_cron.set_defaults(func=cmd_render_reactive_crontab)

    render_sync_cron = subparsers.add_parser("render-sync-crontab")
    render_sync_cron.add_argument("--python-bin", default=python_bin_default)
    render_sync_cron.set_defaults(func=cmd_render_sync_crontab)

    install_cron = subparsers.add_parser("install-nightly-crontab")
    install_cron.add_argument("--python-bin", default=python_bin_default)
    install_cron.set_defaults(func=cmd_install_nightly_crontab)

    install_reactive = subparsers.add_parser("install-reactive-crontab")
    install_reactive.add_argument("--python-bin", default=python_bin_default)
    install_reactive.set_defaults(func=cmd_install_reactive_crontab)

    install_sync = subparsers.add_parser("install-sync-crontab")
    install_sync.add_argument("--python-bin", default=python_bin_default)
    install_sync.set_defaults(func=cmd_install_sync_crontab)

    remove_cron = subparsers.add_parser("remove-nightly-crontab")
    remove_cron.set_defaults(func=cmd_remove_nightly_crontab)

    remove_reactive = subparsers.add_parser("remove-reactive-crontab")
    remove_reactive.set_defaults(func=cmd_remove_reactive_crontab)

    remove_sync = subparsers.add_parser("remove-sync-crontab")
    remove_sync.set_defaults(func=cmd_remove_sync_crontab)

    render_metrics_cron = subparsers.add_parser("render-metrics-crontab")
    render_metrics_cron.add_argument("--python-bin", default=python_bin_default)
    render_metrics_cron.set_defaults(func=cmd_render_metrics_crontab)

    install_metrics = subparsers.add_parser("install-metrics-crontab")
    install_metrics.add_argument("--python-bin", default=python_bin_default)
    install_metrics.set_defaults(func=cmd_install_metrics_crontab)

    remove_metrics = subparsers.add_parser("remove-metrics-crontab")
    remove_metrics.set_defaults(func=cmd_remove_metrics_crontab)

    subparsers.add_parser("cleanup-data").set_defaults(func=cmd_cleanup_data)

    render_cleanup_cron = subparsers.add_parser("render-cleanup-crontab")
    render_cleanup_cron.add_argument("--python-bin", default=python_bin_default)
    render_cleanup_cron.set_defaults(func=cmd_render_cleanup_crontab)

    install_cleanup = subparsers.add_parser("install-cleanup-crontab")
    install_cleanup.add_argument("--python-bin", default=python_bin_default)
    install_cleanup.set_defaults(func=cmd_install_cleanup_crontab)

    remove_cleanup = subparsers.add_parser("remove-cleanup-crontab")
    remove_cleanup.set_defaults(func=cmd_remove_cleanup_crontab)

    telegram_test = subparsers.add_parser("send-telegram-test")
    telegram_test.add_argument("--message")
    telegram_test.add_argument("--preview", action="store_true")
    telegram_test.set_defaults(func=cmd_send_telegram_test)

    sync_editorial = subparsers.add_parser("sync-editorial-users")
    sync_editorial.add_argument("--mode", required=True, choices=["report-only", "dry-run", "apply"])
    sync_editorial.add_argument("--report-dir")
    sync_editorial.set_defaults(func=cmd_sync_editorial)

    sync_platform = subparsers.add_parser("sync-platform-config")
    sync_platform.add_argument("--mode", required=True, choices=["report-only", "dry-run", "apply"])
    sync_platform.add_argument("--report-dir")
    sync_platform.set_defaults(func=cmd_sync_platform)

    rollover = subparsers.add_parser("rollover-content-year")
    rollover.add_argument("--mode", required=True, choices=["dry-run", "report-only", "execute"])
    rollover.add_argument("--year", required=True, type=int)
    rollover.add_argument("--report-dir")
    rollover.add_argument("--routing-config")
    rollover.set_defaults(func=cmd_rollover_content_year)

    nightly = subparsers.add_parser("run-nightly-auditor")
    nightly.add_argument("--no-write-report", action="store_true")
    nightly.add_argument("--notify-telegram", action="store_true")
    nightly.add_argument("--no-notify-telegram", action="store_true")
    nightly.add_argument("--telegram-preview", action="store_true")
    nightly.set_defaults(func=cmd_run_nightly)

    sentry = subparsers.add_parser("run-sentry-agent")
    sentry.add_argument("--service", required=True)
    sentry.add_argument("--pattern")
    sentry.add_argument("--summary")
    sentry.add_argument("--no-write-report", action="store_true")
    sentry.add_argument("--notify-telegram", action="store_true")
    sentry.add_argument("--no-notify-telegram", action="store_true")
    sentry.add_argument("--telegram-preview", action="store_true")
    sentry.set_defaults(func=cmd_run_sentry)

    reactive = subparsers.add_parser("run-reactive-watch")
    reactive.add_argument("--write-report", action="store_true")
    reactive.set_defaults(func=cmd_run_reactive_watch)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
