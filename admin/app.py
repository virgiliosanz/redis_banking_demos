"""Admin panel Flask application.

Entry point: python -m admin.app
"""

from __future__ import annotations

import sys
from pathlib import Path

from flask import Flask, render_template, jsonify, request

from .config import ADMIN_PORT, ADMIN_HOST, DEBUG
from .runner import run_cli


def create_app() -> Flask:
    """Create and configure the Flask application."""
    app = Flask(
        __name__,
        template_folder=str(Path(__file__).parent / "templates"),
        static_folder=str(Path(__file__).parent / "static"),
    )
    app.config["DEBUG"] = DEBUG

    @app.route("/")
    def index():
        return render_template("index.html")

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

    return app


def main() -> None:
    """Run the admin panel dev server."""
    app = create_app()
    app.run(host=ADMIN_HOST, port=ADMIN_PORT, debug=DEBUG)


if __name__ == "__main__":
    main()
