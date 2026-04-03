"""Containers management blueprint.

Provides routes for managing Docker Compose services:
- List containers with status
- Start/stop/restart services
- View service logs
"""

from __future__ import annotations

import json
from pathlib import Path

from flask import Blueprint, render_template, jsonify, request

from .runner import run_cli


bp = Blueprint("containers", __name__, url_prefix="/containers")


def get_compose_root() -> Path:
    """Get the docker compose project root directory.
    
    Defaults to the repository root (parent of admin package).
    """
    return Path(__file__).parent.parent


@bp.route("/")
def index():
    """Display containers list with status."""
    return render_template("containers.html")


@bp.route("/api/status")
def api_status():
    """Get status of all services as JSON.
    
    Executes: docker compose ps --format json
    Returns: List of service objects with name, state, status, etc.
    """
    compose_root = get_compose_root()
    result = run_cli(
        ["docker", "compose", "ps", "--format", "json"],
        timeout=30
    )
    
    if not result.success:
        return jsonify({
            "error": "Failed to get container status",
            "stderr": result.stderr
        }), 500
    
    # Parse JSON lines output (one JSON object per line)
    services = []
    for line in result.stdout.strip().split("\n"):
        if line:
            try:
                services.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    
    return jsonify({"services": services})


@bp.route("/api/<service>/restart", methods=["POST"])
def api_restart(service: str):
    """Restart a specific service.
    
    Executes: docker compose restart <service>
    """
    result = run_cli(
        ["docker", "compose", "restart", service],
        timeout=60
    )
    
    return jsonify({
        "success": result.success,
        "output": result.output,
        "returncode": result.returncode
    })


@bp.route("/api/<service>/stop", methods=["POST"])
def api_stop(service: str):
    """Stop a specific service.
    
    Executes: docker compose stop <service>
    """
    result = run_cli(
        ["docker", "compose", "stop", service],
        timeout=60
    )
    
    return jsonify({
        "success": result.success,
        "output": result.output,
        "returncode": result.returncode
    })


@bp.route("/api/<service>/start", methods=["POST"])
def api_start(service: str):
    """Start a specific service.
    
    Executes: docker compose start <service>
    """
    result = run_cli(
        ["docker", "compose", "start", service],
        timeout=60
    )
    
    return jsonify({
        "success": result.success,
        "output": result.output,
        "returncode": result.returncode
    })


@bp.route("/<service>/logs")
def logs(service: str):
    """Display logs for a specific service."""
    # Get line limit from query parameter (default: 100)
    lines = request.args.get("lines", "100")
    try:
        lines_int = int(lines)
        if lines_int < 1:
            lines_int = 100
    except ValueError:
        lines_int = 100
    
    return render_template("logs.html", service=service, lines=lines_int)


@bp.route("/api/<service>/logs")
def api_logs(service: str):
    """Get logs for a specific service as JSON.
    
    Executes: docker compose logs --tail=<N> <service>
    Query params: lines (default: 100)
    """
    lines = request.args.get("lines", "100")
    try:
        lines_int = int(lines)
        if lines_int < 1:
            lines_int = 100
    except ValueError:
        lines_int = 100
    
    result = run_cli(
        ["docker", "compose", "logs", f"--tail={lines_int}", service],
        timeout=30
    )
    
    return jsonify({
        "success": result.success,
        "logs": result.output,
        "returncode": result.returncode
    })
