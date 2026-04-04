"""Dashboard data API blueprint.

Provides JSON endpoints consumed by the main dashboard page to show
the latest nightly and drift report summaries.
"""

from __future__ import annotations

from pathlib import Path

from flask import Blueprint, jsonify

from .report_parser import parse_nightly_findings, parse_drift_details

bp = Blueprint("dashboard", __name__)

_REPORT_DIR_IAOPS = Path(__file__).parent.parent / "runtime" / "reports" / "ia-ops"
_REPORT_DIR_SYNC = Path(__file__).parent.parent / "runtime" / "reports" / "sync"


@bp.route("/api/latest-nightly")
def api_latest_nightly():
    """Return metadata from the most recent nightly auditor report.

    Scans ``runtime/reports/ia-ops/`` for ``nightly-auditor-*.md``,
    picks the latest by filename (lexicographic = chronological) and
    extracts the header fields plus findings.
    """
    report_dir = _REPORT_DIR_IAOPS
    if not report_dir.is_dir():
        return jsonify({"error": "Report directory not found"}), 404

    files = sorted(report_dir.glob("nightly-auditor-*.md"))
    if not files:
        return jsonify({"error": "No nightly reports found"}), 404

    latest = files[-1]
    try:
        content = latest.read_text(encoding="utf-8")
    except OSError as exc:
        return jsonify({"error": str(exc)}), 500

    # Parse header fields from markdown
    date = severity = summary = ""
    for line in content.splitlines()[:10]:
        if line.startswith("- generated_at:"):
            date = line.split(":", 1)[1].strip()
        elif line.startswith("- severidad_global:"):
            severity = line.split(":", 1)[1].strip()
        elif line.startswith("- resumen:"):
            summary = line.split(":", 1)[1].strip()

    findings = parse_nightly_findings(content)

    return jsonify({
        "filename": latest.name,
        "path": str(latest.resolve()),
        "date": date,
        "severity": severity,
        "summary": summary,
        "findings": findings,
    })


@bp.route("/api/latest-drift")
def api_latest_drift():
    """Return metadata from the most recent drift report.

    Scans ``runtime/reports/sync/`` for ``live-archive-sync-*.md``,
    picks the latest by filename and extracts drift status fields
    plus detail summaries.
    """
    report_dir = _REPORT_DIR_SYNC
    if not report_dir.is_dir():
        return jsonify({"error": "Report directory not found"}), 404

    files = sorted(report_dir.glob("live-archive-sync-*.md"))
    if not files:
        return jsonify({"error": "No drift reports found"}), 404

    latest = files[-1]
    try:
        content = latest.read_text(encoding="utf-8")
    except OSError as exc:
        return jsonify({"error": str(exc)}), 500

    date = editorial_drift = platform_drift = ""
    for line in content.splitlines()[:10]:
        if line.startswith("- generated_at:"):
            date = line.split(":", 1)[1].strip()
        elif line.startswith("- editorial_drift:"):
            editorial_drift = line.split(":", 1)[1].strip()
        elif line.startswith("- platform_drift:"):
            platform_drift = line.split(":", 1)[1].strip()

    drift_details = parse_drift_details(content)

    return jsonify({
        "filename": latest.name,
        "path": str(latest.resolve()),
        "date": date,
        "editorial_drift": editorial_drift,
        "platform_drift": platform_drift,
        "editorial_details": drift_details["editorial"],
        "platform_details": drift_details["platform"],
    })
