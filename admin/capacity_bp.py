"""Capacity planning blueprint.

Provides a page with trend charts and projections for disk, memory,
Elasticsearch docs and MySQL connections, plus a JSON API endpoint
that returns daily averages and linear-regression projections.
"""

from __future__ import annotations

from collections import defaultdict

from flask import Blueprint, render_template, jsonify

from ops.metrics.storage import MetricsStore

bp = Blueprint("capacity", __name__, url_prefix="/capacity")

_CAPACITY_METRICS = {
    "disk": {"group": "host", "metric": "disk_used_pct", "threshold": 90.0, "label": "Disco", "unit": "%"},
    "memory": {"group": "host", "metric": "memory_used_pct", "threshold": 90.0, "label": "Memoria", "unit": "%"},
    "elastic_docs": {"group": "elastic", "metric": "docs_count", "threshold": None, "label": "Elastic docs", "unit": "docs"},
    "mysql_live": {"group": "mysql.db-live", "metric": "threads_connected", "threshold": None, "label": "MySQL live conns", "unit": "conns"},
    "mysql_archive": {"group": "mysql.db-archive", "metric": "threads_connected", "threshold": None, "label": "MySQL archive conns", "unit": "conns"},
}

_RANGE_DAYS = 7
_SECONDS_PER_DAY = 86400


def _get_store() -> MetricsStore:
    return MetricsStore()


def linear_regression(points: list[tuple[float, float]]) -> tuple[float, float] | None:
    """Least-squares linear regression. Returns (slope, intercept) or None."""
    n = len(points)
    if n < 2:
        return None
    sum_x = sum(p[0] for p in points)
    sum_y = sum(p[1] for p in points)
    sum_xy = sum(p[0] * p[1] for p in points)
    sum_x2 = sum(p[0] ** 2 for p in points)
    denom = n * sum_x2 - sum_x ** 2
    if denom == 0:
        return None
    slope = (n * sum_xy - sum_x * sum_y) / denom
    intercept = (sum_y - slope * sum_x) / n
    return slope, intercept


def days_until_threshold(slope: float, intercept: float, last_x: float, threshold: float) -> float | None:
    """Days from last_x until threshold. None if slope <= 0."""
    if slope <= 0:
        return None
    current = slope * last_x + intercept
    if current >= threshold:
        return 0.0
    x_at_threshold = (threshold - intercept) / slope
    days = x_at_threshold - last_x
    return round(days, 1) if days > 0 else 0.0


def _compute_daily_averages(rows: list[tuple[float, str, float]], metric_name: str) -> list[tuple[float, float]]:
    """Group rows into daily averages. Returns [(day_index, avg_value)]."""
    daily: dict[int, list[float]] = defaultdict(list)
    matching = [(ts, val) for ts, name, val in rows if name == metric_name]
    if not matching:
        return []
    min_ts = min(ts for ts, _ in matching)
    for ts, val in matching:
        day = int((ts - min_ts) / _SECONDS_PER_DAY)
        daily[day].append(val)
    return [(float(d), sum(v) / len(v)) for d, v in sorted(daily.items())]


def _build_metric_data(store: MetricsStore, key: str, cfg: dict) -> dict:
    """Build trend data for a single capacity metric."""
    range_minutes = _RANGE_DAYS * 24 * 60
    rows = store.query_extended(cfg["group"], range_minutes)
    daily_avg = _compute_daily_averages(rows, cfg["metric"])
    result: dict = {
        "key": key, "label": cfg["label"], "unit": cfg["unit"],
        "daily_avg": [{"day": d, "value": round(v, 2)} for d, v in daily_avg],
        "current_value": None, "slope": None, "intercept": None,
        "days_until_threshold": None, "threshold": cfg["threshold"], "growth_per_day": None,
    }
    if daily_avg:
        result["current_value"] = round(daily_avg[-1][1], 2)
    reg = linear_regression(daily_avg)
    if reg is not None:
        slope, intercept = reg
        result["slope"] = round(slope, 4)
        result["intercept"] = round(intercept, 2)
        result["growth_per_day"] = round(slope, 4)
        if cfg["threshold"] is not None and daily_avg:
            dut = days_until_threshold(slope, intercept, daily_avg[-1][0], cfg["threshold"])
            result["days_until_threshold"] = dut
    return result


@bp.route("/")
def capacity_page():
    """Render the capacity planning page."""
    return render_template("capacity.html")


@bp.route("/api/data")
def api_capacity_data():
    """Return capacity trend data as JSON."""
    try:
        store = _get_store()
        metrics = [_build_metric_data(store, k, c) for k, c in _CAPACITY_METRICS.items()]
        store.close()
    except Exception as exc:
        return jsonify({"error": str(exc)}), 500
    return jsonify({"metrics": metrics})
