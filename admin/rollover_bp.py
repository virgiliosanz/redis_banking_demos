"""Rollover blueprint.

Provides the rollover management page.
"""

from __future__ import annotations

from flask import Blueprint, render_template

bp = Blueprint("rollover", __name__, url_prefix="/rollover")


@bp.route("/")
def rollover_page():
    return render_template("rollover.html")
