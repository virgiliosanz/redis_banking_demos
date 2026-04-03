"""Reports explorer blueprint.

Lists and serves report files from runtime/reports/ grouped by category.
"""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

from flask import Blueprint, render_template, jsonify


bp = Blueprint("reports", __name__, url_prefix="/reports")

REPORTS_DIR = Path(__file__).parent.parent / "runtime" / "reports"


def _file_info(filepath: Path) -> dict:
    """Return metadata dict for a single report file."""
    stat = filepath.stat()
    return {
        "name": filepath.name,
        "path": str(filepath.resolve()),
        "size_bytes": stat.st_size,
        "modified_timestamp": datetime.fromtimestamp(
            stat.st_mtime, tz=timezone.utc
        ).isoformat(),
    }


def _scan_reports(category: str | None = None) -> list[dict]:
    """Scan runtime/reports/ and return categories with their files.

    If *category* is given, only that subdirectory is returned.
    """
    if not REPORTS_DIR.is_dir():
        return []

    dirs = (
        [REPORTS_DIR / category]
        if category and (REPORTS_DIR / category).is_dir()
        else sorted(REPORTS_DIR.iterdir())
    )

    categories: list[dict] = []
    for d in dirs:
        if not d.is_dir():
            continue
        files = [_file_info(f) for f in d.iterdir() if f.is_file()]
        files.sort(key=lambda f: f["modified_timestamp"], reverse=True)
        categories.append({"name": d.name, "files": files})

    categories.sort(key=lambda c: c["name"])
    return categories


@bp.route("/")
def index():
    """Main reports page."""
    return render_template("reports.html")


@bp.route("/api/list")
def api_list():
    """Return all report files grouped by directory."""
    return jsonify({"categories": _scan_reports()})


@bp.route("/api/list/<category>")
def api_list_category(category: str):
    """Return files for a single category."""
    cats = _scan_reports(category)
    if not cats:
        return jsonify({"error": "Category not found"}), 404
    return jsonify({"categories": cats})
