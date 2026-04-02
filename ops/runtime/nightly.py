from __future__ import annotations

from dataclasses import dataclass

from ..config import Settings
from ..context import collect_operational_context, collect_statuses, load_drift_status
from .drift import format_drift_summary
from ..util.jsonio import dumps_pretty
from ..util.time import utc_timestamp


@dataclass(frozen=True)
class NightlyAssessment:
    generated_at: str
    severity: str
    summary: str
    host_memory_status: str
    docker_status: str
    recent_5xx: int
    elastic_alias_status: str
    cron_warning_jobs: list[str]
    editorial_drift: str
    platform_drift: str
    editorial_drift_summary: list[str]
    platform_drift_summary: list[str]
    editorial_drift_brief: str
    platform_drift_brief: str
    drift_report_file: str
    risks: list[str]
    actions: list[str]
    context: dict[str, object]


def build_nightly_assessment(settings: Settings) -> NightlyAssessment:
    context = collect_operational_context(settings)
    drift = load_drift_status(settings)
    return assess_nightly_context(
        context,
        drift_report_file=drift.report_file,
        editorial_drift=drift.editorial.status,
        platform_drift=drift.platform.status,
        editorial_drift_summary=drift.editorial.summary,
        platform_drift_summary=drift.platform.summary,
        editorial_drift_brief=format_drift_summary(drift.editorial),
        platform_drift_brief=format_drift_summary(drift.platform),
    )


def assess_nightly_context(
    context: dict[str, object],
    *,
    drift_report_file: str,
    editorial_drift: str,
    platform_drift: str,
    editorial_drift_summary: list[str] | None = None,
    platform_drift_summary: list[str] | None = None,
    editorial_drift_brief: str | None = None,
    platform_drift_brief: str | None = None,
    generated_at: str | None = None,
) -> NightlyAssessment:
    statuses = collect_statuses(context)
    critical_count = sum(1 for status in statuses if status == "critical")
    warning_count = sum(1 for status in statuses if status == "warning")

    severity = "critical" if critical_count else "warning" if warning_count else "info"
    summary = (
        "plataforma degradada con checks criticos"
        if critical_count
        else "plataforma sana con warnings operativos"
        if warning_count
        else "plataforma sana sin hallazgos relevantes"
    )

    host_memory_status = context["host"]["checks"]["memory"]["status"]
    docker_status = context["host"]["checks"]["docker_daemon"]["status"]
    recent_5xx = context["runtime"]["checks"]["lb_nginx_recent_5xx"]["count"]
    elastic_alias_status = context["elastic"]["alias"]["status"]
    mysql_databases = context["mysql"]["databases"]
    smoke_failures = [row["name"] for row in context["app"]["checks"]["smoke_scripts"] if row["status"] != "ok"]
    cron_warning_jobs = [row["job_name"] for row in context["cron"]["jobs"] if row["status"] in {"warning", "critical"}]
    editorial_drift_summary = editorial_drift_summary or []
    platform_drift_summary = platform_drift_summary or []
    editorial_drift_brief = editorial_drift_brief or ("sin diferencias editoriales" if editorial_drift != "yes" else "ver drift report")
    platform_drift_brief = platform_drift_brief or ("sin diferencias de plataforma" if platform_drift != "yes" else "ver drift report")

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
    if context["runtime"]["checks"]["lb_nginx_recent_4xx"]["status"] != "ok":
        risks.append("- Existen respuestas 4xx repetidas en lb-nginx; pueden indicar routing roto, recursos faltantes o clientes golpeando rutas invalidas.")
        actions.append("- Revisar patrones de 4xx recientes para distinguir ruido esperado de una regresion de routing o assets.")
    if elastic_alias_status != "ok":
        risks.append("- El alias de lectura de Elasticsearch no esta sano.")
        actions.append("- Confirmar indices live/archive y republicar el alias antes de dar por buena la busqueda.")
    for db_row in mysql_databases:
        db_name = db_row["service"]
        ping_status = db_row["ping"]["status"]
        processlist = db_row["processlist"]
        if ping_status != "ok":
            risks.append(f"- {db_name} no responde correctamente al ping de MySQL.")
            actions.append(f"- Revisar conectividad y estado interno de {db_name} antes de repetir jobs o smokes dependientes de DB.")
        elif processlist["status"] != "ok":
            risks.append(
                f"- {db_name} tiene queries largas en processlist: {processlist['warning_count']} observadas, {processlist['critical_count']} por encima del umbral critico."
            )
            actions.append(
                f"- Revisar el processlist de {db_name} y validar manualmente si alguna query larga debe investigarse o matarse de forma controlada."
            )
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
        if editorial_drift == "yes":
            risks.append(f"- Drift editorial resumido: {editorial_drift_brief}.")
        if platform_drift == "yes":
            risks.append(f"- Drift de plataforma resumido: {platform_drift_brief}.")
        actions.append("- Revisar el ultimo drift report y ejecutar la sync correspondiente antes de aceptar divergencia.")
    if not risks:
        risks.append("- Sin riesgos adicionales fuera de los checks ya reflejados.")
    if not actions:
        actions.append("- Sin accion inmediata; mantener la observacion diaria y repetir smokes tras cambios de runtime.")

    return NightlyAssessment(
        generated_at=generated_at or context.get("generated_at", utc_timestamp()),
        severity=severity,
        summary=summary,
        host_memory_status=host_memory_status,
        docker_status=docker_status,
        recent_5xx=recent_5xx,
        elastic_alias_status=elastic_alias_status,
        cron_warning_jobs=cron_warning_jobs,
        editorial_drift=editorial_drift,
        platform_drift=platform_drift,
        editorial_drift_summary=editorial_drift_summary,
        platform_drift_summary=platform_drift_summary,
        editorial_drift_brief=editorial_drift_brief,
        platform_drift_brief=platform_drift_brief,
        drift_report_file=drift_report_file,
        risks=risks,
        actions=actions,
        context=context,
    )


def render_nightly_report(assessment: NightlyAssessment) -> str:
    return f"""# Nightly Auditor

- generated_at: {assessment.generated_at}
- resumen: {assessment.summary}
- severidad_global: {assessment.severity}

## Host
```json
{dumps_pretty(assessment.context["host"])}
```

## Servicios
```json
{dumps_pretty(assessment.context["runtime"])}
```

## Aplicacion
```json
{dumps_pretty(assessment.context["app"])}
```

## MySQL
```json
{dumps_pretty(assessment.context["mysql"])}
```

## Cron
```json
{dumps_pretty(assessment.context["cron"])}
```

## Drift detectado
- editorial_drift: {assessment.editorial_drift}
- platform_drift: {assessment.platform_drift}
- editorial_drift_brief: {assessment.editorial_drift_brief}
- platform_drift_brief: {assessment.platform_drift_brief}
- drift_report: {assessment.drift_report_file}

### Editorial drift summary
{chr(10).join(assessment.editorial_drift_summary) if assessment.editorial_drift_summary else "- none"}

### Platform drift summary
{chr(10).join(assessment.platform_drift_summary) if assessment.platform_drift_summary else "- none"}

## Riesgos
{chr(10).join(assessment.risks)}

## Acciones recomendadas
{chr(10).join(assessment.actions)}

## Elasticsearch
```json
{dumps_pretty(assessment.context["elastic"])}
```
"""


def render_nightly_telegram_message(assessment: NightlyAssessment, *, report_file: str | None) -> str:
    delayed_jobs = ", ".join(assessment.cron_warning_jobs) if assessment.cron_warning_jobs else "none"
    report_line = f"report: {report_file}" if report_file else "report: no generado"
    return "\n".join(
        [
            f"[Nightly Auditor][{assessment.severity.upper()}]",
            assessment.summary,
            f"host_memory: {assessment.host_memory_status}",
            f"docker: {assessment.docker_status}",
            f"lb_nginx_recent_5xx: {assessment.recent_5xx}",
            f"elastic_alias: {assessment.elastic_alias_status}",
            f"cron_delayed_jobs: {delayed_jobs}",
            f"editorial_drift: {assessment.editorial_drift}",
            f"platform_drift: {assessment.platform_drift}",
            f"editorial_drift_brief: {assessment.editorial_drift_brief}",
            f"platform_drift_brief: {assessment.platform_drift_brief}",
            report_line,
        ]
    )
