"""Crontab management blueprint.

Provides routes for viewing host and container crontab status.
"""

from __future__ import annotations

from typing import Any

from flask import Blueprint, render_template, jsonify

from .runner import run_cli
from .report_parser import describe_container_cron

bp = Blueprint("crontab", __name__, url_prefix="/crontab")


@bp.route("/")
def crontab_page():
    return render_template("crontab.html")


@bp.route("/api/status")
def crontab_api_status():
    """Check installation status of each managed cron block.

    Reads ``crontab -l`` and checks for the BEGIN/END markers of each
    managed block.  Returns JSON with ``installed`` flag and the cron
    lines for each job type.
    """
    from ops.scheduling.cron import (
        MANAGED_BLOCK_NAME,
        METRICS_MANAGED_BLOCK_NAME,
        REACTIVE_MANAGED_BLOCK_NAME,
        SYNC_MANAGED_BLOCK_NAME,
    )

    blocks = {
        "nightly": MANAGED_BLOCK_NAME,
        "reactive": REACTIVE_MANAGED_BLOCK_NAME,
        "sync": SYNC_MANAGED_BLOCK_NAME,
        "metrics": METRICS_MANAGED_BLOCK_NAME,
    }

    result = run_cli(["crontab", "-l"], timeout=10)

    if not result.success:
        stderr_lower = (result.stderr or "").lower()
        stdout_lower = (result.stdout or "").lower()
        if "no crontab" in stderr_lower or "no crontab" in stdout_lower:
            crontab_content = ""
        else:
            error_msg = result.stderr or result.stdout or "crontab -l failed"
            return jsonify({
                k: {"installed": False, "line": None, "error": error_msg}
                for k in blocks
            })
    else:
        crontab_content = result.stdout or ""

    status: dict[str, Any] = {}
    for key, block_name in blocks.items():
        begin = f"# BEGIN {block_name}"
        end = f"# END {block_name}"
        inside = False
        cron_lines: list[str] = []

        for line in crontab_content.splitlines():
            if line.strip() == begin:
                inside = True
                continue
            if line.strip() == end:
                inside = False
                continue
            if inside and line.strip() and not line.startswith("SHELL=") and not line.startswith("PATH="):
                cron_lines.append(line)

        if cron_lines:
            status[key] = {
                "installed": True,
                "line": "\n".join(cron_lines),
            }
        else:
            status[key] = {"installed": False, "line": None}

    return jsonify(status)


@bp.route("/api/container-crons")
def crontab_api_container_crons():
    """Fetch crontab entries from the cron-master container.

    Runs ``docker compose exec -T cron-master crontab -l`` and parses
    the output into a list of schedule/command/status dicts.
    """
    from .containers import get_compose_root
    compose_root = get_compose_root()
    result = run_cli(
        ["docker", "compose", "exec", "-T", "cron-master", "crontab", "-l"],
        timeout=15,
        cwd=str(compose_root),
    )

    if not result.success:
        stderr_lower = (result.stderr or "").lower()
        if "no crontab" in stderr_lower:
            return jsonify({"entries": [], "error": ""})
        return jsonify({
            "entries": [],
            "error": result.stderr or result.stdout or "Error al leer crontab del contenedor",
        })

    entries: list[dict[str, str]] = []
    for line in (result.stdout or "").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        parts = stripped.split(None, 5)
        if len(parts) >= 6:
            command = parts[5]
            entries.append({
                "schedule": " ".join(parts[:5]),
                "command": command,
                "status": "activo",
                "description": describe_container_cron(command),
            })
        elif len(parts) >= 1:
            entries.append({
                "schedule": "-",
                "command": stripped,
                "status": "variable",
                "description": describe_container_cron(stripped),
            })

    return jsonify({"entries": entries, "error": ""})
