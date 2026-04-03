"""Admin panel Flask application.

Entry point: python -m admin.app
"""

from __future__ import annotations

import sys
from pathlib import Path

from flask import Flask, render_template, jsonify, request, Blueprint, abort

from .config import ADMIN_PORT, ADMIN_HOST, DEBUG
from .runner import run_cli
from . import containers
from . import reports


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

    @app.route("/")
    def index():
        return render_template("index.html")

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
        result = run_cli(args, timeout=timeout)
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
        extracts the header fields.
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

        return jsonify({
            "filename": latest.name,
            "date": date,
            "severity": severity,
            "summary": summary,
        })

    @app.route("/api/latest-drift")
    def api_latest_drift():
        """Return metadata from the most recent drift report.

        Scans ``runtime/reports/sync/`` for ``live-archive-sync-*.md``,
        picks the latest by filename and extracts drift status fields.
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

        return jsonify({
            "filename": latest.name,
            "date": date,
            "editorial_drift": editorial_drift,
            "platform_drift": platform_drift,
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
