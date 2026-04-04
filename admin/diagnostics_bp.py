"""Diagnostics blueprint -- rich HTML views for collectors.

Each collector is called directly via Python and rendered through
a Jinja2 partial template as an HTML fragment suitable for embedding.
"""

from __future__ import annotations

import logging
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from flask import Blueprint, render_template, jsonify, request

from ops.config import load_settings
from ops.collectors import elastic as elastic_collector
from ops.collectors import app as app_collector
from ops.collectors import cron as cron_collector

logger = logging.getLogger(__name__)

bp = Blueprint("diagnostics_collectors", __name__, url_prefix="/diagnostics")

_REPORT_DIR = Path(__file__).parent.parent / "runtime" / "reports" / "ia-ops"


def _get_settings():
    """Return a Settings instance using the default resolution chain."""
    return load_settings()


def _wants_json() -> bool:
    return request.headers.get("Accept", "") == "application/json"


def _now_stamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")


def _render(template: str, data: dict[str, Any]):
    return render_template(template, data=data, timestamp=_now_stamp())


def _parse_report_header(content: str, fields: dict[str, str]) -> dict[str, str]:
    """Extract ``- key: value`` header fields from a markdown report."""
    result: dict[str, str] = {}
    for line in content.splitlines()[:15]:
        for key, attr in fields.items():
            if line.startswith(f"- {key}:"):
                result[attr] = line.split(":", 1)[1].strip()
    return result


def _parse_list_section(content: str, heading: str) -> list[str]:
    """Return list-item texts from a ``## heading`` section."""
    items: list[str] = []
    in_section = False
    for line in content.splitlines():
        if line.startswith(f"## {heading}"):
            in_section = True
            continue
        if in_section:
            if line.startswith("## "):
                break
            stripped = line.strip()
            if stripped.startswith("- "):
                items.append(stripped[2:].strip())
    return items


@bp.route("/elastic")
def elastic_health():
    """Elasticsearch cluster, indices and alias health."""
    try:
        settings = _get_settings()
        data = elastic_collector.collect(settings)
    except Exception as exc:
        logger.exception("elastic collector failed")
        if _wants_json():
            return jsonify({"error": str(exc)}), 500
        data = {"error": str(exc)}

    if _wants_json():
        return jsonify(data)
    return _render("partials/elastic_health.html", data)


@bp.route("/app")
def app_health():
    """Application HTTP checks and smoke tests."""
    try:
        settings = _get_settings()
        data = app_collector.collect(settings)
    except Exception as exc:
        logger.exception("app collector failed")
        if _wants_json():
            return jsonify({"error": str(exc)}), 500
        data = {"error": str(exc)}

    if _wants_json():
        return jsonify(data)
    return _render("partials/app_health.html", data)


@bp.route("/cron")
def cron_health():
    """Cron heartbeat health and container crontab entries."""
    try:
        settings = _get_settings()
        data = cron_collector.collect(settings)
    except Exception as exc:
        logger.exception("cron collector failed")
        if _wants_json():
            return jsonify({"error": str(exc)}), 500
        data = {"error": str(exc)}

    # Fetch container crontab from cron-master
    container_crons: list[dict[str, str]] = []
    container_error = ""
    try:
        from .runner import run_cli
        from .containers import get_compose_root
        result = run_cli(
            ["docker", "compose", "exec", "-T", "cron-master", "crontab", "-l"],
            timeout=15,
            cwd=str(get_compose_root()),
        )
        if result.success:
            container_crons = _parse_crontab_lines(result.stdout)
        else:
            stderr_lower = (result.stderr or "").lower()
            if "no crontab" in stderr_lower:
                container_crons = []
            else:
                container_error = result.stderr or result.stdout or "Error al leer crontab del contenedor"
    except Exception as exc:
        logger.exception("container crontab fetch failed")
        container_error = str(exc)

    if _wants_json():
        return jsonify({"collector": data, "container_crons": container_crons, "container_error": container_error})
    return render_template(
        "partials/cron_health.html",
        data=data,
        container_crons=container_crons,
        container_error=container_error,
        timestamp=_now_stamp(),
    )


def _parse_crontab_lines(raw: str) -> list[dict[str, str]]:
    """Parse crontab -l output into structured entries."""
    entries: list[dict[str, str]] = []
    for line in raw.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        parts = stripped.split(None, 5)
        if len(parts) >= 6:
            entries.append({
                "schedule": " ".join(parts[:5]),
                "command": parts[5],
                "status": "activo",
            })
        elif len(parts) >= 1:
            # Non-standard line (env var, etc.)
            entries.append({
                "schedule": "-",
                "command": stripped,
                "status": "variable",
            })
    return entries


@bp.route("/nightly")
def nightly_health():
    """Last nightly auditor report rendered as rich HTML."""
    report_dir = _REPORT_DIR
    if not report_dir.is_dir():
        return render_template(
            "partials/nightly_health.html",
            error="No se encontro el directorio de reportes nightly.",
            severity=None, summary=None, date=None,
            filename=None, findings=None, risks=None, actions=None,
        )

    files = sorted(report_dir.glob("nightly-auditor-*.md"))
    if not files:
        return render_template(
            "partials/nightly_health.html",
            error="No hay reportes nightly previos.",
            severity=None, summary=None, date=None,
            filename=None, findings=None, risks=None, actions=None,
        )

    latest = files[-1]
    try:
        content = latest.read_text(encoding="utf-8")
    except OSError as exc:
        return render_template(
            "partials/nightly_health.html",
            error=f"Error leyendo reporte: {exc}",
            severity=None, summary=None, date=None,
            filename=None, findings=None, risks=None, actions=None,
        )

    header = _parse_report_header(content, {
        "generated_at": "date",
        "severidad_global": "severity",
        "resumen": "summary",
    })

    # Reuse the app-level findings parser
    from admin.app import _parse_nightly_findings
    findings = _parse_nightly_findings(content)

    risks = _parse_list_section(content, "Riesgos")
    actions = _parse_list_section(content, "Acciones recomendadas")

    return render_template(
        "partials/nightly_health.html",
        error=None,
        severity=header.get("severity", ""),
        summary=header.get("summary", ""),
        date=header.get("date", ""),
        filename=latest.name,
        findings=findings,
        risks=risks,
        actions=actions,
    )


@bp.route("/sentry")
def sentry_health():
    """Last sentry agent report rendered as rich HTML."""
    report_dir = _REPORT_DIR
    if not report_dir.is_dir():
        return render_template(
            "partials/sentry_health.html",
            error="No se encontro el directorio de reportes.",
            data=None,
        )

    files = sorted(report_dir.glob("sentry-*.md"))
    if not files:
        return render_template(
            "partials/sentry_health.html",
            error=None,
            data=None,
        )

    latest = files[-1]
    try:
        content = latest.read_text(encoding="utf-8")
    except OSError as exc:
        return render_template(
            "partials/sentry_health.html",
            error=f"Error leyendo reporte: {exc}",
            data=None,
        )

    header = _parse_report_header(content, {
        "generated_at": "date",
        "severidad": "severity",
        "resumen": "summary",
        "servicio_afectado": "service",
    })

    # Extract cause (plain text paragraph after ## Causa probable)
    cause = ""
    in_cause = False
    for line in content.splitlines():
        if line.startswith("## Causa probable"):
            in_cause = True
            continue
        if in_cause:
            if line.startswith("## "):
                break
            stripped = line.strip()
            if stripped:
                cause = stripped
                break

    # Extract risk
    risk = ""
    in_risk = False
    for line in content.splitlines():
        if line.startswith("## Riesgo si no se actua"):
            in_risk = True
            continue
        if in_risk:
            if line.startswith("## "):
                break
            stripped = line.strip()
            if stripped:
                risk = stripped
                break

    evidence = _parse_list_section(content, "Evidencias")
    actions = _parse_list_section(content, "Acciones manuales")

    data = {
        "date": header.get("date", ""),
        "severity": header.get("severity", ""),
        "summary": header.get("summary", ""),
        "service": header.get("service", ""),
        "filename": latest.name,
        "cause": cause,
        "risk": risk,
        "evidence": evidence,
        "actions": actions,
    }

    return render_template(
        "partials/sentry_health.html",
        error=None,
        data=data,
    )
