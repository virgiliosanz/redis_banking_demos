"""Admin panel Flask application.

Entry point: python -m admin.app
"""

from __future__ import annotations

import json
import time
from datetime import datetime, timezone
from pathlib import Path

from flask import Flask, render_template, jsonify, request

from .config import ADMIN_PORT, ADMIN_HOST, DEBUG
from .runner import run_cli
from . import containers
from . import history
from . import history_bp
from . import reports
from . import diagnostics_bp
from . import crontab_bp
from . import rollover_bp
from . import dashboard_bp


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
    app.register_blueprint(diagnostics_bp.bp)

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

    @app.route("/sync/")
    def sync_page():
        return render_template("sync_unified.html")

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
        # Only log ops CLI commands in history to avoid pollution
        if (
            len(result.command) >= 1
            and result.command[0] in ("python3", "python")
            and any("ops.cli.ia_ops" in arg for arg in result.command)
        ):
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

    # Register extracted blueprints
    app.register_blueprint(crontab_bp.bp)
    app.register_blueprint(rollover_bp.bp)
    app.register_blueprint(dashboard_bp.bp)

    return app


def main() -> None:
    """Run the admin panel dev server."""
    app = create_app()
    app.run(host=ADMIN_HOST, port=ADMIN_PORT, debug=DEBUG)


if __name__ == "__main__":
    main()
