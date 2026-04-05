"""Metrics dashboard blueprint.

Provides a page with Chart.js graphs for operational metrics and a JSON API
endpoint consumed by the frontend.
"""

from __future__ import annotations

import json
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

from flask import Blueprint, render_template, jsonify, request

from ops.metrics.storage import MetricsStore

bp = Blueprint("metrics", __name__, url_prefix="/metrics")

_RANGE_MAP = {
    "5m": 5,
    "15m": 15,
    "30m": 30,
    "1h": 60,
    "6h": 360,
    "24h": 1440,
    "3d": 3 * 24 * 60,
    "7d": 7 * 24 * 60,
}

# Ranges that require the extended (raw + hourly) query path.
_EXTENDED_RANGES = {"3d", "7d"}

VALID_GROUPS = {"host", "nginx", "mysql", "elastic", "phpfpm", "containers", "wordpress"}


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
        offset_hours: shift the query window back by *N* hours and
            normalise the returned timestamps to the current window.
            Useful for temporal comparison (e.g. ``offset_hours=24``
            fetches yesterday's data with today's timestamps).

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
    offset_hours_raw = request.args.get("offset_hours", None)

    if range_key not in _RANGE_MAP:
        return jsonify({"error": f"Invalid range '{range_key}'. Use: {', '.join(_RANGE_MAP)}"}), 400

    if group not in VALID_GROUPS:
        return jsonify({"error": f"Invalid group '{group}'. Use: {', '.join(sorted(VALID_GROUPS))}"}), 400

    offset_seconds = 0
    if offset_hours_raw is not None:
        try:
            offset_hours = int(offset_hours_raw)
            if offset_hours < 0:
                raise ValueError("must be non-negative")
            offset_seconds = offset_hours * 3600
        except (ValueError, TypeError):
            return jsonify({"error": f"Invalid offset_hours '{offset_hours_raw}'. Must be a non-negative integer."}), 400

    range_minutes = _RANGE_MAP[range_key]

    try:
        store = _get_store()
        if offset_seconds:
            # For offset queries, always use query_extended to cover
            # data that may have been aggregated into hourly buckets.
            rows = store.query_extended(group, range_minutes, offset_seconds=offset_seconds)
        elif range_key in _EXTENDED_RANGES:
            rows = store.query_extended(group, range_minutes)
        else:
            rows = store.query(group, range_minutes)
        store.close()
    except Exception as exc:
        return jsonify({"error": str(exc)}), 500

    # Organise by metric name.
    # When offset is active, shift timestamps forward so they align with
    # the current time window (the frontend can overlay them directly).
    metrics: dict[str, list[dict]] = defaultdict(list)
    for ts, metric_name, value in rows:
        normalised_ts = ts + offset_seconds
        metrics[metric_name].append({
            "ts": normalised_ts,
            "iso": datetime.fromtimestamp(normalised_ts, tz=timezone.utc).isoformat(),
            "value": value,
        })

    return jsonify({
        "group": group,
        "range": range_key,
        "offset_hours": offset_seconds // 3600 if offset_seconds else 0,
        "metrics": dict(metrics),
    })


_REPORT_DIR_IAOPS = Path(__file__).parent.parent / "runtime" / "reports" / "ia-ops"


@bp.route("/api/incidents")
def api_incidents():
    """Return reactive-watch incidents within the requested time range.

    Query parameters:
        range: one of the keys in ``_RANGE_MAP`` (default ``1h``).

    Response::

        {
            "incidents": [
                {"timestamp": 1712200000, "service": "mysql",
                 "severity": "critical", "summary": "..."},
                ...
            ]
        }
    """
    range_key = request.args.get("range", "1h")
    if range_key not in _RANGE_MAP:
        return jsonify({"error": f"Invalid range '{range_key}'. Use: {', '.join(_RANGE_MAP)}"}), 400

    range_minutes = _RANGE_MAP[range_key]
    cutoff = datetime.now(timezone.utc).timestamp() - (range_minutes * 60)

    state_path = _REPORT_DIR_IAOPS / "reactive-watch-state.json"
    if not state_path.is_file():
        return jsonify({"incidents": []})

    try:
        data = json.loads(state_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return jsonify({"incidents": []})

    incidents_dict = data.get("incidents", {})
    if not isinstance(incidents_dict, dict):
        return jsonify({"incidents": []})

    incidents = []
    for _key, info in incidents_dict.items():
        if not isinstance(info, dict):
            continue
        epoch = info.get("last_sent_epoch")
        if not isinstance(epoch, (int, float)):
            continue
        if epoch < cutoff:
            continue
        incidents.append({
            "timestamp": epoch,
            "service": info.get("service", "unknown"),
            "severity": info.get("severity", "unknown"),
            "summary": info.get("summary", ""),
        })

    incidents.sort(key=lambda x: x["timestamp"])
    return jsonify({"incidents": incidents})
