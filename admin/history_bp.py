"""History blueprint.

Provides routes for viewing and managing the execution history log.
"""

from __future__ import annotations

from flask import Blueprint, render_template, jsonify

from . import history

bp = Blueprint("history", __name__, url_prefix="/history")


@bp.route("/")
def history_page():
    """Render the history table page."""
    return render_template("history.html")


@bp.route("/api/entries")
def api_entries():
    """Return history entries as JSON."""
    entries = history.load_entries()
    return jsonify({"entries": entries})


@bp.route("/api/clear", methods=["POST"])
def api_clear():
    """Clear all history entries."""
    history.clear_entries()
    return jsonify({"success": True})
