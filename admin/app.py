"""Admin panel Flask application.

Entry point: python -m admin.app
"""

from __future__ import annotations

import json
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from flask import Flask, render_template, jsonify, request, Blueprint, abort

from .config import ADMIN_PORT, ADMIN_HOST, DEBUG
from .runner import run_cli
from . import containers
from . import history
from . import history_bp
from . import reports


def _extract_json_blocks(content: str) -> list[tuple[str, dict[str, Any]]]:
    """Return ``(section_heading, parsed_dict)`` for each JSON code block."""
    results: list[tuple[str, dict[str, Any]]] = []
    current_heading = ""
    in_block = False
    block_lines: list[str] = []
    for line in content.splitlines():
        if line.startswith("## "):
            current_heading = line[3:].strip()
        elif line.strip() == "```json":
            in_block = True
            block_lines = []
        elif line.strip() == "```" and in_block:
            in_block = False
            try:
                parsed = json.loads("\n".join(block_lines))
                results.append((current_heading, parsed))
            except (json.JSONDecodeError, ValueError):
                pass
        elif in_block:
            block_lines.append(line)
    return results


def _collect_non_ok_checks(
    data: dict[str, Any], section: str
) -> list[dict[str, str]]:
    """Walk a collector JSON dict and return findings for non-ok checks."""
    findings: list[dict[str, str]] = []

    checks: dict[str, Any] | None = data.get("checks")
    if isinstance(checks, dict):
        for name, val in checks.items():
            if isinstance(val, dict) and val.get("status") not in (
                "ok",
                "not_supported",
                None,
            ):
                status = val["status"]
                sev = "error" if status == "critical" else status
                text = f"{section}: {name} = {status}"
                detail = val.get("count")
                if detail is not None:
                    text += f" (count: {detail})"
                pct = val.get("used_pct")
                if pct is not None:
                    text += f" ({pct}%)"
                findings.append({"severity": sev, "text": text})
            if isinstance(val, list):
                for item in val:
                    if isinstance(item, dict) and item.get("status") not in (
                        "ok",
                        "not_supported",
                        None,
                    ):
                        item_status = item["status"]
                        sev = "error" if item_status == "critical" else item_status
                        item_name = item.get("name") or item.get("script", name)
                        findings.append({
                            "severity": sev,
                            "text": f"{section}: {item_name} = {item_status}",
                        })

    # Cron jobs
    jobs = data.get("jobs")
    if isinstance(jobs, list):
        for job in jobs:
            if isinstance(job, dict) and job.get("status") not in ("ok", "info", None):
                sev = "error" if job["status"] == "critical" else job["status"]
                findings.append({
                    "severity": sev,
                    "text": f"{section}: {job.get('job_name', '?')} heartbeat {job['status']}",
                })

    # Elasticsearch cluster_health
    cluster = data.get("cluster_health")
    if isinstance(cluster, dict):
        cs = cluster.get("collector_status", cluster.get("status", ""))
        if cs not in ("ok", "green", ""):
            sev = "error" if cs in ("critical", "red") else "warning"
            es_status = cluster.get("status", cs)
            findings.append({
                "severity": sev,
                "text": f"{section}: cluster {es_status}",
            })

    # Databases
    dbs = data.get("databases")
    if isinstance(dbs, list):
        for db in dbs:
            if not isinstance(db, dict):
                continue
            svc = db.get("service", "?")
            for sub_key in ("ping", "processlist"):
                sub = db.get(sub_key)
                if isinstance(sub, dict) and sub.get("status") not in ("ok", None):
                    sev = "error" if sub["status"] == "critical" else sub["status"]
                    findings.append({
                        "severity": sev,
                        "text": f"{section}: {svc} {sub_key} = {sub['status']}",
                    })

    return findings


def _parse_nightly_findings(content: str) -> list[dict[str, str]]:
    """Extract findings from a nightly auditor markdown report."""
    findings: list[dict[str, str]] = []

    # Extract from JSON blocks
    for section, data in _extract_json_blocks(content):
        findings.extend(_collect_non_ok_checks(data, section))

    # Extract from Riesgos section
    in_riesgos = False
    for line in content.splitlines():
        if line.startswith("## Riesgos"):
            in_riesgos = True
            continue
        if in_riesgos:
            if line.startswith("## "):
                break
            stripped = line.strip()
            if stripped.startswith("- "):
                text = stripped[2:].strip()
                if text and text.lower() != "sin riesgos adicionales fuera de los checks ya reflejados.":
                    findings.append({"severity": "warning", "text": text})

    return findings


_EDITORIAL_DETAIL_KEYS = (
    "only_in_live_logins",
    "only_in_archive_logins",
    "changed_users",
)

_PLATFORM_DETAIL_KEYS = (
    "scalar_mismatches",
    "active_plugins_only_in_live",
    "active_plugins_only_in_archive",
    "hash_mismatches",
)


def _parse_drift_details(content: str) -> dict[str, dict[str, Any]]:
    """Extract editorial and platform drift detail summaries."""
    editorial: dict[str, Any] = {"count": 0, "items": [], "brief": ""}
    platform: dict[str, Any] = {"count": 0, "items": [], "brief": ""}

    current_section = ""
    for line in content.splitlines():
        if line.startswith("## Editorial drift") or line.startswith("### Editorial drift"):
            current_section = "editorial"
            continue
        elif line.startswith("## Platform drift") or line.startswith("### Platform drift"):
            current_section = "platform"
            continue
        elif line.startswith("## ") or line.startswith("### "):
            if current_section:
                current_section = ""
            continue

        if not line.startswith("- "):
            continue

        key_val = line[2:].strip()
        if ":" not in key_val:
            continue
        key, val = key_val.split(":", 1)
        key = key.strip()
        val = val.strip()

        if current_section == "editorial":
            if key == "editorial_brief":
                editorial["brief"] = val
            elif key in _EDITORIAL_DETAIL_KEYS and val != "none":
                items = [v.strip() for v in val.split(",") if v.strip()]
                editorial["items"].append(f"{key}: {val}")
                editorial["count"] += len(items)

        elif current_section == "platform":
            if key == "platform_brief":
                platform["brief"] = val
            elif key in _PLATFORM_DETAIL_KEYS and val != "none":
                items = [v.strip() for v in val.split(",") if v.strip()]
                platform["items"].append(f"{key}: {val}")
                platform["count"] += len(items)

    return {"editorial": editorial, "platform": platform}


def create_app() -> Flask:
    """Create and configure the Flask application."""
    app = Flask(
        __name__,
        template_folder=str(Path(__file__).parent / "templates"),
        static_folder=str(Path(__file__).parent / "static"),
    )
    app.config["DEBUG"] = DEBUG

    # Register blueprints
    app.register_blueprint(containers.bp)
    app.register_blueprint(reports.bp)
    app.register_blueprint(history_bp.bp)

    @app.route("/")
    def index():
        return render_template("index.html")

    @app.route("/health")
    def health():
        """Health check endpoint.

        Returns JSON with service status when Docker is reachable,
        or ``services: null`` when it is not.
        """
        timestamp = datetime.now(timezone.utc).isoformat()
        services_summary = None
        try:
            compose_root = containers.get_compose_root()
            result = run_cli(
                ["docker", "compose", "ps", "--format", "json"],
                timeout=10,
                cwd=str(compose_root),
            )
            if result.success:
                svc_list = []
                for line in result.stdout.strip().split("\n"):
                    if line:
                        try:
                            svc_list.append(json.loads(line))
                        except json.JSONDecodeError:
                            continue
                services_summary = [
                    {
                        "name": s.get("Service") or s.get("Name", "unknown"),
                        "state": s.get("State", "unknown"),
                    }
                    for s in svc_list
                ]
        except Exception:
            pass

        return jsonify({
            "status": "ok",
            "timestamp": timestamp,
            "services": services_summary,
        })

    @app.route("/diagnostics")
    def diagnostics():
        return render_template("diagnostics.html")

    @app.route("/sync/editorial")
    def sync_editorial():
        return render_template(
            "sync.html",
            page_title="Sync Editorial Users",
            page_subtitle="Sincronizar usuarios editoriales entre live y archive",
            command="sync-editorial-users",
            show_mode_selector=True,
        )

    @app.route("/sync/platform")
    def sync_platform():
        return render_template(
            "sync.html",
            page_title="Sync Platform Config",
            page_subtitle="Sincronizar configuracion de plataforma entre live y archive",
            command="sync-platform-config",
            show_mode_selector=True,
        )

    @app.route("/sync/drift")
    def sync_drift():
        return render_template(
            "sync.html",
            page_title="Drift Report",
            page_subtitle="Reporte de desviaciones entre live y archive",
            command="report-live-archive-sync-drift",
            show_mode_selector=False,
        )

    @app.route("/api/run", methods=["POST"])
    def api_run():
        """Execute a CLI command and return the result as JSON.

        Expects JSON body: {"args": ["python3", "-m", "ops.cli.ia_ops", "collect-host-health"]}
        """
        data = request.get_json(force=True)
        args = data.get("args", [])
        if not args:
            return jsonify({"error": "No command provided"}), 400

        timeout = data.get("timeout", 120)
        t0 = time.monotonic()
        result = run_cli(args, timeout=timeout)
        elapsed = time.monotonic() - t0
        history.save_entry(
            command=result.command,
            returncode=result.returncode,
            duration_seconds=elapsed,
            success=result.success,
        )
        return jsonify({
            "command": result.command,
            "returncode": result.returncode,
            "stdout": result.stdout,
            "stderr": result.stderr,
            "success": result.success,
        })

    @app.route("/api/read-file")
    def api_read_file():
        """Read a report file and return its content as JSON.

        Only allows reading files under ``runtime/reports/`` for security.
        """
        file_path = request.args.get("path", "")
        if not file_path:
            return jsonify({"error": "No path provided", "success": False}), 400

        try:
            resolved = Path(file_path).resolve()
        except (ValueError, OSError):
            return jsonify({"error": "Invalid path", "success": False}), 400

        # Security: only allow files under any runtime/reports/ directory
        allowed = False
        for part_idx, part in enumerate(resolved.parts):
            if (
                part == "runtime"
                and part_idx + 1 < len(resolved.parts)
                and resolved.parts[part_idx + 1] == "reports"
            ):
                allowed = True
                break

        if not allowed:
            return jsonify({"error": "Access denied", "success": False}), 403

        if not resolved.is_file():
            return jsonify({"error": "File not found", "success": False}), 404

        try:
            content = resolved.read_text(encoding="utf-8")
        except OSError as exc:
            return jsonify({"error": str(exc), "success": False}), 500

        return jsonify({"content": content, "path": str(resolved), "success": True})

    @app.route("/api/latest-nightly")
    def api_latest_nightly():
        """Return metadata from the most recent nightly auditor report.

        Scans ``runtime/reports/ia-ops/`` for ``nightly-auditor-*.md``,
        picks the latest by filename (lexicographic = chronological) and
        extracts the header fields plus findings.
        """
        report_dir = Path(__file__).parent.parent / "runtime" / "reports" / "ia-ops"
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

        findings = _parse_nightly_findings(content)

        return jsonify({
            "filename": latest.name,
            "path": str(latest.resolve()),
            "date": date,
            "severity": severity,
            "summary": summary,
            "findings": findings,
        })

    @app.route("/api/latest-drift")
    def api_latest_drift():
        """Return metadata from the most recent drift report.

        Scans ``runtime/reports/sync/`` for ``live-archive-sync-*.md``,
        picks the latest by filename and extracts drift status fields
        plus detail summaries.
        """
        report_dir = Path(__file__).parent.parent / "runtime" / "reports" / "sync"
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

        drift_details = _parse_drift_details(content)

        return jsonify({
            "filename": latest.name,
            "path": str(latest.resolve()),
            "date": date,
            "editorial_drift": editorial_drift,
            "platform_drift": platform_drift,
            "editorial_details": drift_details["editorial"],
            "platform_details": drift_details["platform"],
        })

    # Register blueprints
    app.register_blueprint(create_rollover_blueprint())
    app.register_blueprint(create_crontab_blueprint())

    return app


def create_rollover_blueprint() -> Blueprint:
    """Create blueprint for rollover routes."""
    bp = Blueprint("rollover", __name__, url_prefix="/rollover")

    @bp.route("/")
    def rollover_page():
        return render_template("rollover.html")

    return bp


def create_crontab_blueprint() -> Blueprint:
    """Create blueprint for crontab routes."""
    bp = Blueprint("crontab", __name__, url_prefix="/crontab")

    @bp.route("/")
    def crontab_page():
        return render_template("crontab.html")

    return bp


def main() -> None:
    """Run the admin panel dev server."""
    app = create_app()
    app.run(host=ADMIN_HOST, port=ADMIN_PORT, debug=DEBUG)


if __name__ == "__main__":
    main()
