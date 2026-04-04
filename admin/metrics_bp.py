"""Metrics dashboard blueprint.

Provides a page with Chart.js graphs for operational metrics and a JSON API
endpoint consumed by the frontend.
"""

from __future__ import annotations

from collections import defaultdict
from datetime import datetime, timezone

from flask import Blueprint, render_template, jsonify, request

from ops.metrics.storage import MetricsStore

bp = Blueprint("metrics", __name__, url_prefix="/metrics")

_RANGE_MAP = {
    "1h": 60,
    "6h": 360,
    "24h": 1440,
    "3d": 3 * 24 * 60,
    "7d": 7 * 24 * 60,
}

# Ranges that require the extended (raw + hourly) query path.
_EXTENDED_RANGES = {"3d", "7d"}

VALID_GROUPS = {"host", "nginx", "mysql", "elastic", "phpfpm", "containers"}


def _get_store() -> MetricsStore:
    """Return a MetricsStore instance (created per-request)."""
    return MetricsStore()


@bp.route("/")
def metrics_page():
    """Render the metrics dashboard page."""
    return render_template("metrics.html")


@bp.route("/api/data")
def api_metrics():
    """Return metric samples as JSON.

    Query parameters:
        range: ``1h``, ``6h`` or ``24h`` (default ``1h``).
        group: metric group name (default ``host``).

    Response::

        {
            "group": "host",
            "range": "1h",
            "metrics": {
                "cpu_percent": [
                    {"ts": 1712200000.0, "iso": "2025-04-04T...", "value": 12.3},
                    ...
                ],
                ...
            }
        }
    """
    range_key = request.args.get("range", "1h")
    group = request.args.get("group", "host")

    if range_key not in _RANGE_MAP:
        return jsonify({"error": f"Invalid range '{range_key}'. Use: {', '.join(_RANGE_MAP)}"}), 400

    if group not in VALID_GROUPS:
        return jsonify({"error": f"Invalid group '{group}'. Use: {', '.join(sorted(VALID_GROUPS))}"}), 400

    range_minutes = _RANGE_MAP[range_key]

    try:
        store = _get_store()
        if range_key in _EXTENDED_RANGES:
            rows = store.query_extended(group, range_minutes)
        else:
            rows = store.query(group, range_minutes)
        store.close()
    except Exception as exc:
        return jsonify({"error": str(exc)}), 500

    # Organise by metric name
    metrics: dict[str, list[dict]] = defaultdict(list)
    for ts, metric_name, value in rows:
        metrics[metric_name].append({
            "ts": ts,
            "iso": datetime.fromtimestamp(ts, tz=timezone.utc).isoformat(),
            "value": value,
        })

    return jsonify({
        "group": group,
        "range": range_key,
        "metrics": dict(metrics),
    })
