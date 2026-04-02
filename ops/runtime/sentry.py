from __future__ import annotations

from dataclasses import dataclass

from ..collectors import logs as logs_collector
from ..config import Settings
from ..context import collect_operational_context, load_drift_status
from ..services import inspect_name_map, service_keys
from ..util.jsonio import dumps_pretty
from ..util.time import utc_timestamp
from .drift import format_drift_summary


@dataclass(frozen=True)
class SentryDiagnosis:
    generated_at: str
    service: str
    severity: str
    summary: str
    cause: str
    risk: str
    evidence: list[str]
    validations: list[str]
    actions: list[str]
    context: dict[str, object]
    service_logs: str


def build_sentry_diagnosis(
    settings: Settings,
    service: str,
    *,
    pattern: str | None = None,
    summary_override: str | None = None,
) -> SentryDiagnosis:
    context = collect_operational_context(settings)
    service_logs = logs_collector.collect_service_logs(settings, service, pattern) if service in service_keys() else ""
    drift = load_drift_status(settings)
    name_map = inspect_name_map(settings)
    container_name = next((inspect_name for inspect_name, service_key in name_map.items() if service_key == service), "")
    container_health = next((row["health_status"] for row in context["runtime"]["containers"] if row["container_name"] == container_name), "unknown")
    return diagnose_sentry_service(
        service,
        context,
        service_logs=service_logs,
        container_health=container_health,
        summary_override=summary_override,
        drift_report_file=drift.report_file,
        editorial_drift=drift.editorial.status,
        platform_drift=drift.platform.status,
        editorial_drift_summary=drift.editorial.summary,
        platform_drift_summary=drift.platform.summary,
        editorial_drift_brief=format_drift_summary(drift.editorial),
        platform_drift_brief=format_drift_summary(drift.platform),
    )


def diagnose_sentry_service(
    service: str,
    context: dict[str, object],
    *,
    service_logs: str = "",
    container_health: str = "unknown",
    summary_override: str | None = None,
    drift_report_file: str = "unknown",
    editorial_drift: str = "unknown",
    platform_drift: str = "unknown",
    editorial_drift_summary: list[str] | None = None,
    platform_drift_summary: list[str] | None = None,
    editorial_drift_brief: str = "sin diferencias editoriales",
    platform_drift_brief: str = "sin diferencias de plataforma",
    generated_at: str | None = None,
) -> SentryDiagnosis:
    host = context["host"]
    runtime = context["runtime"]
    app = context["app"]
    mysql = context["mysql"]
    elastic = context["elastic"]
    cron = context["cron"]

    severity = "info"
    summary = summary_override or "incidencia sin hallazgo concluyente"
    cause = "sin causa probable cerrada con el contexto actual"
    evidence = ["- servicio no asociado a contenedor Docker"] if service == "host" else [f"- health_status del servicio: {container_health}"]
    validations: list[str] = []
    actions: list[str] = []

    if service_logs:
        evidence.append("- logs acotados del servicio contienen coincidencias con el patron seleccionado")
    editorial_drift_summary = editorial_drift_summary or []
    platform_drift_summary = platform_drift_summary or []

    if service == "host":
        severity, summary, cause = _diagnose_host(host, summary_override)
        evidence.extend(_host_evidence(host))
        validations.extend(
            [
                "- confirmar memoria, disco, carga e iowait con una segunda lectura",
                "- validar que Docker responde antes de diagnosticar incidencias de servicios",
            ]
        )
        actions.append("- liberar presion local o recuperar Docker antes de continuar con diagnostico de plataforma")
    elif service == "lb-nginx":
        severity, summary, cause = _diagnose_lb_nginx(runtime, app, container_health, service_logs, summary_override)
        evidence.extend(_lb_nginx_evidence(runtime, app))
        validations.extend([
            "- revisar request_id, host y php_upstream de las peticiones afectadas",
            "- repetir smoke-routing y verificar /healthz en ambos hosts",
        ])
        actions.append("- inspeccionar logs recientes de lb-nginx y del upstream implicado")
    elif service == "elastic":
        severity, summary, cause = _diagnose_elastic(elastic, app, container_health, summary_override)
        evidence.extend(_elastic_evidence(elastic, app))
        validations.extend([
            "- confirmar _cluster/health, indices live/archive y alias n9-search-posts",
            "- repetir smoke-search para validar la capa publica",
        ])
        actions.append("- revisar el ultimo reindexado y republicar alias si falta")
    elif service == "be-admin":
        severity, summary, cause = _diagnose_be_admin(app, container_health, service_logs, summary_override)
        evidence.extend(_be_admin_evidence(app))
        validations.extend(
            [
                "- comprobar wp-login.php y wp-admin en live y archive",
                "- revisar redirecciones y posibles loops en el flujo de admin",
            ]
        )
        actions.append("- revisar logs de be-admin y repetir smokes de login/admin antes de seguir")
    elif service == "cron-master":
        severity, summary, cause = _diagnose_cron_master(
            cron,
            container_health,
            service_logs,
            summary_override,
            editorial_drift=editorial_drift,
            platform_drift=platform_drift,
        )
        evidence.extend(
            _cron_evidence(
                cron,
                drift_report_file=drift_report_file,
                editorial_drift=editorial_drift,
                platform_drift=platform_drift,
                editorial_drift_summary=editorial_drift_summary,
                platform_drift_summary=platform_drift_summary,
                editorial_drift_brief=editorial_drift_brief,
                platform_drift_brief=platform_drift_brief,
            )
        )
        validations.extend(
            [
                "- confirmar heartbeats de sync editorial, sync de plataforma y rollover",
                "- revisar el drift report si hay divergencias entre live y archive",
            ]
        )
        actions.append("- revisar los logs recientes y reejecutar manualmente solo el job afectado si procede")
    elif service in {"db-live", "db-archive"}:
        db_row = next(row for row in mysql["databases"] if row["service"] == service)
        severity, summary, cause = _diagnose_database(service, db_row, container_health, summary_override)
        evidence.extend(_database_evidence(db_row))
        validations.extend(
            [
                f"- revisar processlist de {service} y confirmar si las queries largas son esperadas",
                "- contrastar con slow query log y con plugins recientes que afecten a SEO o metadatos",
            ]
        )
        actions.append("- documentar manualmente cualquier query candidata a `KILL`, pero no ejecutar corte automatico en esta fase")
    else:
        if container_health != "healthy":
            severity = "critical"
            summary = summary_override or f"{service} no esta sano"
            cause = "contenedor degradado o caido"
        elif service_logs:
            severity = "warning"
            summary = summary_override or f"{service} contiene errores recientes"
            cause = "errores del servicio detectados en logs acotados"
        else:
            summary = summary_override or f"{service} sano sin errores recientes"
            cause = "sin evidencia actual de fallo en el servicio"
        validations.append(f"- revisar healthcheck y logs recientes del servicio {service}")
        actions.append("- repetir el smoke funcional relacionado con el servicio afectado")

    risk = (
        "el servicio puede quedar caido o degradar rutas base del sitio"
        if severity == "critical"
        else "el problema puede escalar a degradacion visible si persiste"
        if severity == "warning"
        else "sin impacto inmediato confirmado"
    )

    return SentryDiagnosis(
        generated_at=generated_at or context.get("generated_at", utc_timestamp()),
        service=service,
        severity=severity,
        summary=summary,
        cause=cause,
        risk=risk,
        evidence=evidence,
        validations=validations,
        actions=actions,
        context=context,
        service_logs=service_logs,
    )


def render_sentry_report(diagnosis: SentryDiagnosis) -> str:
    context_snapshot = {
        "host": diagnosis.context["host"],
        "runtime": diagnosis.context["runtime"],
        "app": diagnosis.context["app"],
        "mysql": diagnosis.context["mysql"],
        "elastic": diagnosis.context["elastic"],
        "cron": diagnosis.context["cron"],
    }
    return f"""# Sentry Agent

- generated_at: {diagnosis.generated_at}
- resumen: {diagnosis.summary}
- severidad: {diagnosis.severity}
- servicio_afectado: {diagnosis.service}

## Evidencias
{chr(10).join(diagnosis.evidence)}

## Causa probable
{diagnosis.cause}

## Validaciones recomendadas
{chr(10).join(diagnosis.validations)}

## Acciones manuales
{chr(10).join(diagnosis.actions)}

## Playbook ansible sugerido
- revisar y traducir el diagnostico a un playbook especifico del servicio antes de automatizar cualquier remediacion

## Riesgo si no se actua
{diagnosis.risk}

## Contexto adicional
```json
{dumps_pretty(context_snapshot)}
```

## Logs acotados
```
{diagnosis.service_logs or "sin coincidencias"}
```
"""


def render_sentry_telegram_message(diagnosis: SentryDiagnosis, *, report_file: str | None) -> str:
    report_line = f"report: {report_file}" if report_file else "report: no generado"
    return "\n".join(
        [
            f"[Sentry Agent][{diagnosis.severity.upper()}]",
            f"service: {diagnosis.service}",
            diagnosis.summary,
            f"cause: {diagnosis.cause}",
            f"risk: {diagnosis.risk}",
            report_line,
        ]
    )


def _host_evidence(host: dict[str, object]) -> list[str]:
    memory = host["checks"]["memory"]
    disk = host["checks"]["disk"]
    load_average = host["checks"]["load_average"]
    docker_daemon = host["checks"]["docker_daemon"]
    iowait = host["checks"]["iowait"]
    return [
        f"- docker_daemon_status: {docker_daemon['status']}",
        f"- memory_used_pct: {memory['used_pct']}",
        f"- disk_used_pct: {disk['used_pct']}",
        f"- load_1: {load_average['load_1']}",
        f"- iowait_pct: {iowait['pct']}",
    ]


def _diagnose_host(host: dict[str, object], summary_override: str | None) -> tuple[str, str, str]:
    memory = host["checks"]["memory"]
    disk = host["checks"]["disk"]
    load_average = host["checks"]["load_average"]
    docker_daemon = host["checks"]["docker_daemon"]
    iowait = host["checks"]["iowait"]
    if docker_daemon["status"] != "ok":
        return (
            "critical",
            summary_override or "host no puede hablar con Docker",
            "el daemon Docker no responde y el contexto de runtime deja de ser fiable",
        )
    if memory["status"] == "critical" or disk["status"] == "critical" or load_average["status"] == "critical" or iowait["status"] == "critical":
        return (
            "critical",
            summary_override or "host con umbrales criticos de recursos",
            "presion critica de recursos o saturacion del host",
        )
    if memory["status"] == "warning" or disk["status"] == "warning" or load_average["status"] == "warning" or iowait["status"] == "warning":
        return (
            "warning",
            summary_override or "host con warning de recursos",
            "presion de recursos o degradacion del host por encima del baseline",
        )
    return ("info", summary_override or "host sano sin sintomas relevantes", "sin evidencia actual de degradacion del host")


def _lb_nginx_evidence(runtime: dict[str, object], app: dict[str, object]) -> list[str]:
    recent_4xx = runtime["checks"]["lb_nginx_recent_4xx"]["count"]
    recent_5xx = runtime["checks"]["lb_nginx_recent_5xx"]["count"]
    routing_smoke_failed = any(row["name"] == "routing" and row["status"] != "ok" for row in app["checks"]["smoke_scripts"])
    return [
        f"- lb_nginx_recent_4xx: {recent_4xx}",
        f"- lb_nginx_recent_5xx: {recent_5xx}",
        f"- smoke_routing_failed: {'yes' if routing_smoke_failed else 'no'}",
    ]


def _diagnose_lb_nginx(
    runtime: dict[str, object],
    app: dict[str, object],
    container_health: str,
    service_logs: str,
    summary_override: str | None,
) -> tuple[str, str, str]:
    recent_4xx = runtime["checks"]["lb_nginx_recent_4xx"]["count"]
    recent_5xx = runtime["checks"]["lb_nginx_recent_5xx"]["count"]
    routing_smoke_failed = any(row["name"] == "routing" and row["status"] != "ok" for row in app["checks"]["smoke_scripts"])
    if container_health != "healthy":
        return ("critical", summary_override or "lb-nginx no esta sano", "caida o degradacion directa del balanceador")
    if routing_smoke_failed:
        return ("critical", summary_override or "smoke-routing falla en la capa publica", "routing roto, upstream incorrecto o regresion de configuracion en Nginx")
    if recent_5xx > 0 or service_logs:
        return ("warning", summary_override or "lb-nginx muestra errores recientes", "errores recientes en frontend o upstream degradado")
    if recent_4xx >= runtime["checks"]["lb_nginx_recent_4xx"]["warning_threshold"]:
        status = runtime["checks"]["lb_nginx_recent_4xx"]["status"]
        return (status, summary_override or "lb-nginx acumula respuestas 4xx repetidas", "clientes, assets o rutas estan generando errores 4xx de forma anomala")
    return ("info", summary_override or "lb-nginx sano sin errores recientes", "sin evidencia actual de fallo en lb-nginx")


def _elastic_evidence(elastic: dict[str, object], app: dict[str, object]) -> list[str]:
    search_smoke_failed = any(row["name"] == "search" and row["status"] != "ok" for row in app["checks"]["smoke_scripts"])
    return [
        f"- elastic alias status: {elastic['alias']['status']}",
        f"- elastic cluster status: {elastic['cluster_health'].get('collector_status', 'unknown')}",
        f"- elastic cluster raw_status: {elastic['cluster_health'].get('status', 'unknown')}",
        f"- unified_search_status: {app['checks']['unified_search_endpoint']['status']}",
        f"- smoke_search_failed: {'yes' if search_smoke_failed else 'no'}",
    ]


def _diagnose_elastic(
    elastic: dict[str, object],
    app: dict[str, object],
    container_health: str,
    summary_override: str | None,
) -> tuple[str, str, str]:
    alias_status = elastic["alias"]["status"]
    cluster_status = elastic["cluster_health"].get("collector_status", "unknown")
    search_endpoint_status = app["checks"]["unified_search_endpoint"]["status"]
    search_smoke_failed = any(row["name"] == "search" and row["status"] != "ok" for row in app["checks"]["smoke_scripts"])
    if container_health != "healthy" or alias_status != "ok":
        return ("critical", summary_override or "elastic o el alias de lectura no estan sanos", "busqueda degradada por caida de elastic o alias ausente")
    if search_endpoint_status != "ok" or search_smoke_failed:
        return (
            "critical",
            summary_override or "la busqueda publica falla aunque el cluster parezca sano",
            "regresion en la capa publica de busqueda, alias, plugin o integracion",
        )
    if cluster_status == "critical":
        return ("warning", summary_override or "elastic reporta estado no nominal", "salud de cluster distinta del baseline de laboratorio")
    return ("info", summary_override or "elastic sano en el baseline del laboratorio", "sin evidencia actual de fallo de busqueda")


def _be_admin_evidence(app: dict[str, object]) -> list[str]:
    live_login = app["checks"]["live_login"]
    archive_login = app["checks"]["archive_login"]
    return [
        f"- live_login_status: {live_login['status']} ({live_login['http_code']})",
        f"- archive_login_status: {archive_login['status']} ({archive_login['http_code']})",
    ]


def _diagnose_be_admin(
    app: dict[str, object],
    container_health: str,
    service_logs: str,
    summary_override: str | None,
) -> tuple[str, str, str]:
    live_login = app["checks"]["live_login"]
    archive_login = app["checks"]["archive_login"]
    if container_health != "healthy":
        return ("critical", summary_override or "be-admin no esta sano", "caida del runtime administrativo")
    if live_login["status"] != "ok" or archive_login["status"] != "ok":
        return ("critical", summary_override or "wp-login o admin no responden correctamente", "regresion del plano administrativo, login o routing hacia be-admin")
    if service_logs:
        return ("warning", summary_override or "be-admin contiene errores recientes", "errores recientes del plano administrativo")
    return ("info", summary_override or "be-admin sano sin errores visibles", "sin evidencia actual de fallo del plano administrativo")


def _cron_evidence(
    cron: dict[str, object],
    *,
    drift_report_file: str,
    editorial_drift: str,
    platform_drift: str,
    editorial_drift_summary: list[str],
    platform_drift_summary: list[str],
    editorial_drift_brief: str,
    platform_drift_brief: str,
) -> list[str]:
    delayed_jobs = [row["job_name"] for row in cron["jobs"] if row["status"] in {"warning", "critical"}]
    recent_log_errors = cron["recent_log_errors"]
    evidence = [
        f"- delayed_jobs: {', '.join(delayed_jobs) if delayed_jobs else 'none'}",
        f"- recent_log_errors: {recent_log_errors['count']} ({recent_log_errors['status']})",
        f"- editorial_drift: {editorial_drift}",
        f"- platform_drift: {platform_drift}",
        f"- editorial_drift_brief: {editorial_drift_brief}",
        f"- platform_drift_brief: {platform_drift_brief}",
        f"- drift_report: {drift_report_file}",
    ]
    if editorial_drift_summary:
        evidence.extend(f"- editorial_drift_detail {line.removeprefix('- ')}" for line in editorial_drift_summary[:3])
    if platform_drift_summary:
        evidence.extend(f"- platform_drift_detail {line.removeprefix('- ')}" for line in platform_drift_summary[:3])
    return evidence


def _diagnose_cron_master(
    cron: dict[str, object],
    container_health: str,
    service_logs: str,
    summary_override: str | None,
    *,
    editorial_drift: str,
    platform_drift: str,
) -> tuple[str, str, str]:
    delayed_jobs = [row["job_name"] for row in cron["jobs"] if row["status"] in {"warning", "critical"}]
    recent_log_errors = cron["recent_log_errors"]
    if container_health != "healthy":
        return ("critical", summary_override or "cron-master no esta sano", "caida del runtime que ejecuta jobs criticos")
    if delayed_jobs or service_logs:
        return ("warning", summary_override or "cron-master presenta retrasos o errores recientes", "jobs fuera de ventana o errores en logs del cron")
    if recent_log_errors["status"] in {"warning", "critical"}:
        return (
            recent_log_errors["status"],
            summary_override or "cron-master acumula errores recientes en logs",
            "errores recientes del plano de scheduling y orquestacion",
        )
    if editorial_drift == "yes" or platform_drift == "yes":
        return (
            "warning",
            summary_override or "cron-master detecta drift entre live y archive",
            "sync editorial o de plataforma sin alinear entre ambos contextos",
        )
    return ("info", summary_override or "cron-master sano sin retrasos visibles", "sin evidencia actual de fallo en cron-master")


def _database_evidence(db_row: dict[str, object]) -> list[str]:
    processlist = db_row["processlist"]
    evidence = [
        f"- mysql ping status: {db_row['ping']['status']}",
        f"- long_queries_warning_count: {processlist['warning_count']}",
        f"- long_queries_critical_count: {processlist['critical_count']}",
    ]
    if processlist["queries"]:
        query_ids = ", ".join(str(row["id"]) for row in processlist["queries"][:5])
        evidence.append(f"- candidate_query_ids: {query_ids}")
    return evidence


def _diagnose_database(
    service: str,
    db_row: dict[str, object],
    container_health: str,
    summary_override: str | None,
) -> tuple[str, str, str]:
    ping_status = db_row["ping"]["status"]
    processlist = db_row["processlist"]
    if ping_status != "ok" or container_health != "healthy":
        return (
            "critical",
            summary_override or f"{service} no esta sano o no responde al ping",
            "caida del contenedor, conectividad rota o mysql no responde correctamente",
        )
    if processlist["critical_count"] > 0:
        return (
            "critical",
            summary_override or f"{service} tiene queries largas por encima del umbral critico",
            "una o varias queries de larga duracion pueden estar bloqueando o degradando la base de datos",
        )
    if processlist["warning_count"] > 0:
        return (
            "warning",
            summary_override or f"{service} tiene queries largas en processlist",
            "consultas largas activas que conviene revisar antes de que degraden el servicio",
        )
    return (
        "info",
        summary_override or f"{service} sano sin queries largas relevantes",
        "sin evidencia actual de queries largas o bloqueo anomalo",
    )
