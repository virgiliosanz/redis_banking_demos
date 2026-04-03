"""Admin panel Flask application.

Entry point: python -m admin.app
"""

from __future__ import annotations

import sys
from pathlib import Path

from flask import Flask, render_template, jsonify, request, Blueprint

from .config import ADMIN_PORT, ADMIN_HOST, DEBUG
from .runner import run_cli
from . import containers


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
