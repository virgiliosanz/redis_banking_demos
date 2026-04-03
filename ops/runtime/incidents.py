from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ReactiveIncident:
    key: str
    service: str
    severity: str
    summary: str
    pattern: str | None = None


def _smoke_status(context: dict[str, object], smoke_name: str) -> str:
    smoke_rows = context["app"]["checks"]["smoke_scripts"]
    for row in smoke_rows:
        if row["name"] == smoke_name:
            return row["status"]
    return "unknown"


def _check_host_incidents(
    host: dict[str, object],
) -> list[ReactiveIncident]:
    incidents: list[ReactiveIncident] = []
    for check_name in ("docker_daemon", "memory", "disk", "load_average", "iowait"):
        row = host[check_name]
        status = row["status"]
        if status not in {"warning", "critical"}:
            continue

        if check_name == "docker_daemon":
            summary = "host no puede hablar con Docker"
        elif check_name == "memory":
            summary = f"host con memoria alta: {row['used_pct']}%"
        elif check_name == "disk":
            summary = f"host con disco alto: {row['used_pct']}%"
        elif check_name == "load_average":
            summary = f"host con carga elevada: load1={row['load_1']}"
        else:
            summary = f"host con iowait elevado: {row['pct']}%"

        incidents.append(
            ReactiveIncident(
                key=f"host:{check_name}:{status}",
                service="host",
                severity=status if status in {"warning", "critical"} else "warning",
                summary=summary,
            )
        )
    return incidents


def _check_container_incidents(
    runtime: dict[str, object],
    service_map: dict[str, str],
) -> list[ReactiveIncident]:
    incidents: list[ReactiveIncident] = []
    for row in runtime["containers"]:
        health_status = row["health_status"]
        service = service_map.get(row["container_name"])
        if service is None:
            continue
        if health_status in {"unhealthy", "exited", "dead"}:
            severity = "critical" if health_status != "unhealthy" else "warning"
            incidents.append(
                ReactiveIncident(
                    key=f"{service}:health:{health_status}",
                    service=service,
                    severity=severity,
                    summary=f"{service} con estado {health_status}",
                )
            )

    recent_4xx = runtime["checks"]["lb_nginx_recent_4xx"]
    if recent_4xx["status"] != "ok":
        incidents.append(
            ReactiveIncident(
                key=f"lb-nginx:4xx:{recent_4xx['status']}",
                service="lb-nginx",
                severity=recent_4xx["status"],
                summary=f"lb-nginx acumula {recent_4xx['count']} respuestas 4xx recientes",
                pattern=" 4\\d\\d ",
            )
        )

    recent_5xx = runtime["checks"]["lb_nginx_recent_5xx"]
    if recent_5xx["status"] != "ok":
        incidents.append(
            ReactiveIncident(
                key=f"lb-nginx:5xx:{recent_5xx['status']}",
                service="lb-nginx",
                severity=recent_5xx["status"],
                summary=f"lb-nginx acumula {recent_5xx['count']} respuestas 5xx recientes",
                pattern=" 5\\d\\d ",
            )
        )
    return incidents


def _check_smoke_incidents(
    context: dict[str, object],
    app: dict[str, object],
) -> list[ReactiveIncident]:
    incidents: list[ReactiveIncident] = []
    if _smoke_status(context, "routing") != "ok":
        incidents.append(
            ReactiveIncident(
                key="lb-nginx:smoke:routing",
                service="lb-nginx",
                severity="critical",
                summary="smoke-routing falla en la capa publica",
            )
        )

    if app["live_login"]["status"] != "ok":
        incidents.append(
            ReactiveIncident(
                key="be-admin:live-login:critical",
                service="be-admin",
                severity="critical",
                summary="wp-login de live no responde correctamente",
            )
        )

    if app["archive_login"]["status"] != "ok":
        incidents.append(
            ReactiveIncident(
                key="be-admin:archive-login:critical",
                service="be-admin",
                severity="critical",
                summary="wp-login de archive no responde correctamente",
            )
        )

    if app["unified_search_endpoint"]["status"] != "ok":
        incidents.append(
            ReactiveIncident(
                key="elastic:search-endpoint:critical",
                service="elastic",
                severity="critical",
                summary="la busqueda unificada no responde correctamente",
            )
        )

    if _smoke_status(context, "search") != "ok":
        incidents.append(
            ReactiveIncident(
                key="elastic:smoke:search",
                service="elastic",
                severity="critical",
                summary="smoke-search falla en la capa publica",
            )
        )
    return incidents


def _check_mysql_incidents(
    mysql: list[dict[str, object]],
) -> list[ReactiveIncident]:
    incidents: list[ReactiveIncident] = []
    for row in mysql:
        service = row["service"]
        if row["ping"]["status"] != "ok":
            incidents.append(
                ReactiveIncident(
                    key=f"{service}:ping:critical",
                    service=service,
                    severity="critical",
                    summary=f"{service} no responde al ping de MySQL",
                )
            )
            continue

        processlist = row["processlist"]
        if processlist["status"] in {"warning", "critical"}:
            incidents.append(
                ReactiveIncident(
                    key=f"{service}:processlist:{processlist['status']}",
                    service=service,
                    severity=processlist["status"],
                    summary=(
                        f"{service} tiene queries largas: {processlist['warning_count']} observadas, "
                        f"{processlist['critical_count']} por encima del umbral critico"
                    ),
                )
            )
    return incidents


def _check_elastic_incidents(
    elastic: dict[str, object],
) -> list[ReactiveIncident]:
    incidents: list[ReactiveIncident] = []
    if elastic["alias"]["status"] != "ok":
        incidents.append(
            ReactiveIncident(
                key="elastic:alias:missing",
                service="elastic",
                severity="critical",
                summary="alias de lectura de Elasticsearch ausente",
            )
        )
    return incidents


def _check_cron_incidents(
    cron: dict[str, object],
    *,
    editorial_drift: str = "unknown",
    platform_drift: str = "unknown",
) -> list[ReactiveIncident]:
    incidents: list[ReactiveIncident] = []
    delayed_jobs = [row["job_name"] for row in cron["jobs"] if row["status"] in {"warning", "critical"}]
    if delayed_jobs:
        severity = "critical" if any(row["status"] == "critical" for row in cron["jobs"]) else "warning"
        incidents.append(
            ReactiveIncident(
                key=f"cron-master:delayed:{severity}",
                service="cron-master",
                severity=severity,
                summary=f"cron-master tiene jobs fuera de ventana: {', '.join(delayed_jobs)}",
                pattern="ERROR|FATAL|CRITICAL",
            )
        )

    log_errors = cron["recent_log_errors"]
    if log_errors["status"] in {"warning", "critical"}:
        incidents.append(
            ReactiveIncident(
                key=f"cron-master:logs:{log_errors['status']}",
                service="cron-master",
                severity=log_errors["status"],
                summary=f"cron-master acumula {log_errors['count']} errores recientes en logs",
                pattern="ERROR|FATAL|CRITICAL",
            )
        )

    if editorial_drift == "yes" or platform_drift == "yes":
        incidents.append(
            ReactiveIncident(
                key=f"cron-master:drift:{editorial_drift}:{platform_drift}",
                service="cron-master",
                severity="warning",
                summary=f"drift detectado entre live y archive: editorial={editorial_drift}, platform={platform_drift}",
            )
        )
    return incidents


def build_reactive_incidents(
    context: dict[str, object],
    *,
    editorial_drift: str = "unknown",
    platform_drift: str = "unknown",
) -> list[ReactiveIncident]:
    host = context["host"]["checks"]
    runtime = context["runtime"]
    app = context["app"]["checks"]
    mysql = context["mysql"]["databases"]
    elastic = context["elastic"]
    cron = context["cron"]

    incidents: list[ReactiveIncident] = []
    incidents.extend(_check_host_incidents(host))
    incidents.extend(_check_container_incidents(runtime, context["service_map"]))
    incidents.extend(_check_smoke_incidents(context, app))
    incidents.extend(_check_mysql_incidents(mysql))
    incidents.extend(_check_elastic_incidents(elastic))
    incidents.extend(
        _check_cron_incidents(cron, editorial_drift=editorial_drift, platform_drift=platform_drift)
    )
    return incidents
