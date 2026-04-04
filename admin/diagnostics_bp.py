"""Diagnostics blueprint — rich HTML views for collectors.

Each collector is called directly via Python and rendered through
a Jinja2 partial template.  Routes support two modes:

* ``?standalone=1`` → full page extending ``base.html``
* default          → HTML fragment suitable for embedding
"""

from __future__ import annotations

import logging
from typing import Any

from flask import Blueprint, render_template, jsonify, request

from ops.config import load_settings
from ops.collectors import elastic as elastic_collector
from ops.collectors import app as app_collector

logger = logging.getLogger(__name__)

bp = Blueprint("diagnostics_collectors", __name__, url_prefix="/diagnostics")


def _get_settings():
    """Return a Settings instance using the default resolution chain."""
    return load_settings()


def _wants_json() -> bool:
    return request.headers.get("Accept", "") == "application/json"


def _render(template: str, data: dict[str, Any]):
    standalone = request.args.get("standalone") == "1"
    if standalone:
        return render_template(
            template, data=data, standalone=True,
        )
    return render_template(template, data=data, standalone=False)


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
