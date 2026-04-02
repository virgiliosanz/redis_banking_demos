from __future__ import annotations

import argparse
from dataclasses import replace
import json
import sys
from pathlib import Path

from ..config import load_settings
from ..collectors import app as app_collector
from ..collectors import cron as cron_collector
from ..collectors import elastic as elastic_collector
from ..collectors import host as host_collector
from ..collectors import logs as logs_collector
from ..collectors import runtime as runtime_collector
from ..notifications.telegram import load_telegram_config, send_message
from ..rollover import content_year as rollover_content_year
from ..reporting import write_json_report, write_text_report
from ..runtime.drift import build_drift_report
from ..scheduling.cron import install_nightly_auditor_crontab, remove_nightly_auditor_crontab, render_nightly_auditor_block
from ..sync import editorial as editorial_sync
from ..sync import platform as platform_sync
from ..util.jsonio import dumps_pretty
from ..util.time import report_stamp, utc_timestamp


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


def _nightly_telegram_message(
    *,
    severity: str,
    summary: str,
    host_memory_status: str,
    docker_status: str,
    recent_5xx: int,
    elastic_alias_status: str,
    cron_warning_jobs: list[str],
    editorial_drift: str,
    platform_drift: str,
    report_file: str | None,
) -> str:
    delayed_jobs = ", ".join(cron_warning_jobs) if cron_warning_jobs else "none"
    report_line = f"report: {report_file}" if report_file else "report: no generado"
    return "\n".join(
        [
            f"[Nightly Auditor][{severity.upper()}]",
            summary,
            f"host_memory: {host_memory_status}",
            f"docker: {docker_status}",
            f"lb_nginx_recent_5xx: {recent_5xx}",
            f"elastic_alias: {elastic_alias_status}",
            f"cron_delayed_jobs: {delayed_jobs}",
            f"editorial_drift: {editorial_drift}",
            f"platform_drift: {platform_drift}",
            report_line,
        ]
    )


def _sentry_telegram_message(
    *,
    severity: str,
    service: str,
    summary: str,
    cause: str,
    risk: str,
    report_file: str | None,
) -> str:
    report_line = f"report: {report_file}" if report_file else "report: no generado"
    return "\n".join(
        [
            f"[Sentry Agent][{severity.upper()}]",
            f"service: {service}",
            summary,
            f"cause: {cause}",
            f"risk: {risk}",
            report_line,
        ]
    )


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
    _print_json(cron_collector.collect(load_settings()))
    return 0


def cmd_collect_elastic(_: argparse.Namespace) -> int:
    _print_json(elastic_collector.collect(load_settings()))
    return 0


def cmd_collect_runtime(_: argparse.Namespace) -> int:
    _print_json(runtime_collector.collect(load_settings()))
    return 0


def cmd_collect_app(_: argparse.Namespace) -> int:
    _print_json(app_collector.collect(load_settings()))
    return 0


def cmd_collect_service_logs(args: argparse.Namespace) -> int:
    settings = load_settings()
    content = logs_collector.collect_service_logs(settings, args.service, args.pattern)
    if content:
        print(content, end="" if content.endswith("\n") else "\n")
    return 0


def cmd_collect_nightly_context(args: argparse.Namespace) -> int:
    settings = load_settings()
    payload = {
        "generated_at": utc_timestamp(),
        "host": host_collector.collect(settings),
        "runtime": runtime_collector.collect(settings),
        "app": app_collector.collect(settings),
        "elastic": elastic_collector.collect(settings),
        "cron": cron_collector.collect(settings),
    }
    if args.write_report:
        report_root = settings.get_path("REPORT_ROOT", "./runtime/reports/ia-ops")
        report_file = write_json_report(report_root, f"nightly-context-{report_stamp()}.json", payload)
        print(f"nightly context written to {report_file}", file=sys.stderr)
    _print_json(payload)
    return 0


def cmd_report_drift(_: argparse.Namespace) -> int:
    settings = load_settings()
    report_file, _ = build_drift_report(settings)
    print(f"sync drift report written to {report_file}")
    return 0


def cmd_render_nightly_crontab(args: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    content = render_nightly_auditor_block(settings, project_root=project_root, python_bin=args.python_bin)
    print(content, end="")
    return 0


def cmd_install_nightly_crontab(args: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    backup_file, crontab_file = install_nightly_auditor_crontab(settings, project_root=project_root, python_bin=args.python_bin)
    print(f"nightly auditor cron installed from {crontab_file}")
    print(f"previous crontab backed up to {backup_file}")
    return 0


def cmd_remove_nightly_crontab(_: argparse.Namespace) -> int:
    settings = load_settings()
    project_root = settings.project_root.resolve()
    crontab_file = remove_nightly_auditor_crontab(settings, project_root=project_root)
    print(f"nightly auditor cron block removed using {crontab_file}")
    return 0


def cmd_run_nightly(args: argparse.Namespace) -> int:
    settings = load_settings()
    context = {
        "generated_at": utc_timestamp(),
        "host": host_collector.collect(settings),
        "runtime": runtime_collector.collect(settings),
        "app": app_collector.collect(settings),
        "elastic": elastic_collector.collect(settings),
        "cron": cron_collector.collect(settings),
    }
    drift_report_file, _ = build_drift_report(settings)
    drift_lines = Path(drift_report_file).read_text(encoding="utf-8").splitlines()
    editorial_drift = next((line.split(": ", 1)[1] for line in drift_lines if line.startswith("- editorial_drift:")), "unknown")
    platform_drift = next((line.split(": ", 1)[1] for line in drift_lines if line.startswith("- platform_drift:")), "unknown")

    statuses: list[str] = []

    def collect_statuses(node: object) -> None:
        if isinstance(node, dict):
            status = node.get("status")
            if isinstance(status, str):
                statuses.append(status)
            for value in node.values():
                collect_statuses(value)
        elif isinstance(node, list):
            for value in node:
                collect_statuses(value)

    collect_statuses(context)
    critical_count = sum(1 for status in statuses if status == "critical")
    warning_count = sum(1 for status in statuses if status == "warning")

    global_severity = "critical" if critical_count else "warning" if warning_count else "info"
    summary = "plataforma degradada con checks criticos" if critical_count else "plataforma sana con warnings operativos" if warning_count else "plataforma sana sin hallazgos relevantes"

    host_memory_status = context["host"]["checks"]["memory"]["status"]
    docker_status = context["host"]["checks"]["docker_daemon"]["status"]
    recent_5xx = context["runtime"]["checks"]["lb_nginx_recent_5xx"]["count"]
    elastic_alias_status = context["elastic"]["alias"]["status"]
    smoke_failures = [row["name"] for row in context["app"]["checks"]["smoke_scripts"] if row["status"] != "ok"]
    cron_warning_jobs = [row["job_name"] for row in context["cron"]["jobs"] if row["status"] in {"warning", "critical"}]

    risks: list[str] = []
    actions: list[str] = []
    if host_memory_status == "critical":
        risks.append("- Memoria del host en umbral critico; el laboratorio puede falsear otros sintomas por presion local.")
        actions.append("- Revisar consumo de memoria del host y cerrar procesos locales ajenos al stack antes de diagnosticar degradaciones de aplicacion.")
    if docker_status != "ok":
        risks.append("- Docker no responde; el contexto de runtime deja de ser fiable.")
        actions.append("- Recuperar el daemon Docker y repetir el ciclo completo de colectores.")
    if recent_5xx > 0:
        risks.append("- Existen respuestas 5xx recientes en lb-nginx.")
        actions.append("- Revisar logs recientes de lb-nginx y correlacionarlos con request_id y upstream.")
    if elastic_alias_status != "ok":
        risks.append("- El alias de lectura de Elasticsearch no esta sano.")
        actions.append("- Confirmar indices live/archive y republicar el alias antes de dar por buena la busqueda.")
    if smoke_failures:
        joined = ", ".join(smoke_failures)
        risks.append(f"- Hay smokes fallidos: {joined}.")
        actions.append("- Repetir los smokes fallidos y revisar el servicio afectado antes de cerrar la auditoria.")
    if cron_warning_jobs:
        joined = ", ".join(cron_warning_jobs)
        risks.append(f"- Hay jobs de cron fuera de ventana: {joined}.")
        actions.append("- Confirmar los heartbeats y revisar logs recientes de cron-master para los jobs retrasados.")
    if editorial_drift == "yes" or platform_drift == "yes":
        risks.append("- Existe drift entre live y archive que puede invalidar operaciones anuales o tareas editoriales.")
        actions.append("- Revisar el ultimo drift report y ejecutar la sync correspondiente antes de aceptar divergencia.")
    if not risks:
        risks.append("- Sin riesgos adicionales fuera de los checks ya reflejados.")
    if not actions:
        actions.append("- Sin accion inmediata; mantener la observacion diaria y repetir smokes tras cambios de runtime.")

    report = f"""# Nightly Auditor

- generated_at: {utc_timestamp()}
- resumen: {summary}
- severidad_global: {global_severity}

## Host
```json
{dumps_pretty(context["host"])}
```

## Servicios
```json
{dumps_pretty(context["runtime"])}
```

## Aplicacion
```json
{dumps_pretty(context["app"])}
```

## Cron
```json
{dumps_pretty(context["cron"])}
```

## Drift detectado
- editorial_drift: {editorial_drift}
- platform_drift: {platform_drift}
- drift_report: {drift_report_file}

## Riesgos
{chr(10).join(risks)}

## Acciones recomendadas
{chr(10).join(actions)}

## Elasticsearch
```json
{dumps_pretty(context["elastic"])}
```
"""

    report_file: str | None = None
    if not args.no_write_report:
        report_root = settings.get_path("REPORT_ROOT", "./runtime/reports/ia-ops")
        report_path = write_text_report(report_root, f"nightly-auditor-{report_stamp()}.md", report)
        report_file = str(report_path)
        print(f"nightly auditor report written to {report_file}", file=sys.stderr)

    _notify_telegram(
        settings,
        _nightly_telegram_message(
            severity=global_severity,
            summary=summary,
            host_memory_status=host_memory_status,
            docker_status=docker_status,
            recent_5xx=recent_5xx,
            elastic_alias_status=elastic_alias_status,
            cron_warning_jobs=cron_warning_jobs,
            editorial_drift=editorial_drift,
            platform_drift=platform_drift,
            report_file=report_file,
        ),
        explicit=args.notify_telegram,
        preview=args.telegram_preview,
        default_enabled=load_telegram_config(settings).notify_on_nightly,
    )

    print(report)
    return 0


def cmd_run_sentry(args: argparse.Namespace) -> int:
    settings = load_settings()
    host = host_collector.collect(settings)
    runtime = runtime_collector.collect(settings)
    app = app_collector.collect(settings)
    elastic = elastic_collector.collect(settings)
    cron = cron_collector.collect(settings)
    service_logs = logs_collector.collect_service_logs(settings, args.service, args.pattern)

    name_map = {
        "lb-nginx": "/n9-lb-nginx",
        "fe-live": "/n9-fe-live",
        "fe-archive": "/n9-fe-archive",
        "be-admin": "/n9-be-admin",
        "db-live": "/n9-db-live",
        "db-archive": "/n9-db-archive",
        "elastic": "/n9-elastic",
        "cron-master": "/n9-cron-master",
    }
    container_name = name_map.get(args.service, "")
    container_health = next((row["health_status"] for row in runtime["containers"] if row["container_name"] == container_name), "unknown")

    severity = "info"
    summary = args.summary or "incidencia sin hallazgo concluyente"
    cause = "sin causa probable cerrada con el contexto actual"
    evidence = [f"- health_status del servicio: {container_health}"]
    validations: list[str] = []
    actions: list[str] = []

    if service_logs:
        evidence.append("- logs acotados del servicio contienen coincidencias con el patron seleccionado")

    if args.service == "lb-nginx":
        recent_5xx = runtime["checks"]["lb_nginx_recent_5xx"]["count"]
        evidence.append(f"- lb_nginx_recent_5xx: {recent_5xx}")
        if container_health != "healthy":
            severity = "critical"
            summary = args.summary or "lb-nginx no esta sano"
            cause = "caida o degradacion directa del balanceador"
        elif recent_5xx > 0 or service_logs:
            severity = "warning"
            summary = args.summary or "lb-nginx muestra errores recientes"
            cause = "errores recientes en frontend o upstream degradado"
        else:
            summary = args.summary or "lb-nginx sano sin errores recientes"
            cause = "sin evidencia actual de fallo en lb-nginx"
        validations.extend([
            "- revisar request_id, host y php_upstream de las peticiones afectadas",
            "- repetir smoke-routing y verificar /healthz en ambos hosts",
        ])
        actions.append("- inspeccionar logs recientes de lb-nginx y del upstream implicado")
    elif args.service == "elastic":
        alias_status = elastic["alias"]["status"]
        cluster_status = elastic["cluster_health"]["status"]
        evidence.append(f"- elastic alias status: {alias_status}")
        evidence.append(f"- elastic cluster status: {cluster_status}")
        if container_health != "healthy" or alias_status != "ok":
            severity = "critical"
            summary = args.summary or "elastic o el alias de lectura no estan sanos"
            cause = "busqueda degradada por caida de elastic o alias ausente"
        elif cluster_status not in {"green", "yellow"}:
            severity = "warning"
            summary = args.summary or "elastic reporta estado no nominal"
            cause = "salud de cluster distinta del baseline de laboratorio"
        else:
            summary = args.summary or "elastic sano en el baseline del laboratorio"
            cause = "sin evidencia actual de fallo de busqueda"
        validations.extend([
            "- confirmar _cluster/health, indices live/archive y alias n9-search-posts",
            "- repetir smoke-search para validar la capa publica",
        ])
        actions.append("- revisar el ultimo reindexado y republicar alias si falta")
    elif args.service == "cron-master":
        delayed_jobs = [row["job_name"] for row in cron["jobs"] if row["status"] in {"warning", "critical"}]
        evidence.append(f"- delayed_jobs: {', '.join(delayed_jobs) if delayed_jobs else 'none'}")
        if container_health != "healthy":
            severity = "critical"
            summary = args.summary or "cron-master no esta sano"
            cause = "caida del runtime que ejecuta jobs criticos"
        elif delayed_jobs or service_logs:
            severity = "warning"
            summary = args.summary or "cron-master presenta retrasos o errores recientes"
            cause = "jobs fuera de ventana o errores en logs del cron"
        else:
            summary = args.summary or "cron-master sano sin retrasos visibles"
            cause = "sin evidencia actual de fallo en cron-master"
        validations.append("- confirmar heartbeats de sync editorial, sync de plataforma y rollover")
        actions.append("- revisar los logs recientes y reejecutar manualmente solo el job afectado si procede")
    else:
        if container_health != "healthy":
            severity = "critical"
            summary = args.summary or f"{args.service} no esta sano"
            cause = "contenedor degradado o caido"
        elif service_logs:
            severity = "warning"
            summary = args.summary or f"{args.service} contiene errores recientes"
            cause = "errores del servicio detectados en logs acotados"
        else:
            summary = args.summary or f"{args.service} sano sin errores recientes"
            cause = "sin evidencia actual de fallo en el servicio"
        validations.append(f"- revisar healthcheck y logs recientes del servicio {args.service}")
        actions.append("- repetir el smoke funcional relacionado con el servicio afectado")

    risk = (
        "el servicio puede quedar caido o degradar rutas base del sitio"
        if severity == "critical"
        else "el problema puede escalar a degradacion visible si persiste"
        if severity == "warning"
        else "sin impacto inmediato confirmado"
    )

    report = f"""# Sentry Agent

- generated_at: {utc_timestamp()}
- resumen: {summary}
- severidad: {severity}
- servicio_afectado: {args.service}

## Evidencias
{chr(10).join(evidence)}

## Causa probable
{cause}

## Validaciones recomendadas
{chr(10).join(validations)}

## Acciones manuales
{chr(10).join(actions)}

## Playbook ansible sugerido
- revisar y traducir el diagnostico a un playbook especifico del servicio antes de automatizar cualquier remediacion

## Riesgo si no se actua
{risk}

## Contexto adicional
```json
{dumps_pretty({"host": host, "runtime": runtime, "app": app, "elastic": elastic, "cron": cron})}
```

## Logs acotados
```
{service_logs or "sin coincidencias"}
```
"""

    report_file: str | None = None
    if not args.no_write_report:
        report_root = settings.get_path("REPORT_ROOT", "./runtime/reports/ia-ops")
        report_path = write_text_report(report_root, f"sentry-{args.service}-{report_stamp()}.md", report)
        report_file = str(report_path)
        print(f"sentry report written to {report_file}", file=sys.stderr)

    _notify_telegram(
        settings,
        _sentry_telegram_message(
            severity=severity,
            service=args.service,
            summary=summary,
            cause=cause,
            risk=risk,
            report_file=report_file,
        ),
        explicit=args.notify_telegram,
        preview=args.telegram_preview,
        default_enabled=load_telegram_config(settings).notify_on_sentry,
    )
    print(report)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m ops.cli.ia_ops")
    subparsers = parser.add_subparsers(dest="command", required=True)
    python_bin_default = sys.executable or "python3"

    subparsers.add_parser("collect-host-health").set_defaults(func=cmd_collect_host)
    subparsers.add_parser("collect-cron-health").set_defaults(func=cmd_collect_cron)
    subparsers.add_parser("collect-elastic-health").set_defaults(func=cmd_collect_elastic)
    subparsers.add_parser("collect-runtime-health").set_defaults(func=cmd_collect_runtime)
    subparsers.add_parser("collect-app-health").set_defaults(func=cmd_collect_app)

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

    install_cron = subparsers.add_parser("install-nightly-crontab")
    install_cron.add_argument("--python-bin", default=python_bin_default)
    install_cron.set_defaults(func=cmd_install_nightly_crontab)

    remove_cron = subparsers.add_parser("remove-nightly-crontab")
    remove_cron.set_defaults(func=cmd_remove_nightly_crontab)

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
    nightly.add_argument("--telegram-preview", action="store_true")
    nightly.set_defaults(func=cmd_run_nightly)

    sentry = subparsers.add_parser("run-sentry-agent")
    sentry.add_argument("--service", required=True)
    sentry.add_argument("--pattern")
    sentry.add_argument("--summary")
    sentry.add_argument("--no-write-report", action="store_true")
    sentry.add_argument("--notify-telegram", action="store_true")
    sentry.add_argument("--telegram-preview", action="store_true")
    sentry.set_defaults(func=cmd_run_sentry)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
