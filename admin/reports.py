"""Reports explorer blueprint.

Lists and serves report files from runtime/reports/ grouped by category
and sub-type.
"""

from __future__ import annotations

import logging
import re
import time
from datetime import datetime, timezone
from pathlib import Path

from flask import Blueprint, render_template, jsonify

from .config import REPORT_RETENTION_DAYS


log = logging.getLogger(__name__)

bp = Blueprint("reports", __name__, url_prefix="/reports")

REPORTS_DIR = Path(__file__).parent.parent / "runtime" / "reports"

# ---- sub-type detection ----------------------------------------------------

_SYNC_SUBTYPES = [
    ("editorial-report-only", "Editorial report-only"),
    ("editorial-dry-run", "Editorial dry-run"),
    ("editorial-apply", "Editorial apply"),
    ("platform-report-only", "Platform report-only"),
    ("platform-dry-run", "Platform dry-run"),
    ("platform-apply", "Platform apply"),
    ("drift", "Drift"),
]

_ROLLOVER_YEAR_RE = re.compile(r"(\d{4})")


def _detect_subtype(category: str, filename: str) -> str:
    """Return a human-readable sub-type label for *filename*."""
    lower = filename.lower()
    if category == "sync":
        for prefix, label in _SYNC_SUBTYPES:
            if prefix in lower:
                return label
        return "Otros"
    if category == "rollover":
        m = _ROLLOVER_YEAR_RE.search(filename)
        if m:
            return m.group(1)
        return "Otros"
    # nightly / ia-ops / any other: single group
    return ""


# ---- file info + scanning --------------------------------------------------


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
    """Scan runtime/reports/ and return categories with subtypes.

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

        # Group by sub-type
        subtype_map: dict[str, list[dict]] = {}
        for f in files:
            st = _detect_subtype(d.name, f["name"])
            subtype_map.setdefault(st, []).append(f)

        subtypes = [
            {"name": name, "files": sfiles}
            for name, sfiles in subtype_map.items()
        ]
        subtypes.sort(key=lambda s: s["name"])

        categories.append({
            "name": d.name,
            "subtypes": subtypes,
            "total_files": len(files),
        })

    categories.sort(key=lambda c: c["name"])
    return categories


# ---- retention / cleanup ---------------------------------------------------


def cleanup_old_reports(
    reports_dir: Path | None = None,
    max_age_days: int | None = None,
) -> dict:
    """Elimina reportes con mtime mayor a *max_age_days* dias.

    Returns dict with ``deleted`` and ``remaining`` counts.
    """
    reports_dir = reports_dir or REPORTS_DIR
    max_age_days = max_age_days if max_age_days is not None else REPORT_RETENTION_DAYS
    cutoff = time.time() - (max_age_days * 86400)
    deleted = 0
    remaining = 0

    if not reports_dir.is_dir():
        return {"deleted": 0, "remaining": 0}

    for f in reports_dir.rglob("*"):
        if not f.is_file():
            continue
        try:
            if f.stat().st_mtime < cutoff:
                f.unlink()
                deleted += 1
            else:
                remaining += 1
        except OSError as exc:
            log.warning("No se pudo eliminar %s: %s", f, exc)
            remaining += 1

    if deleted:
        log.info(
            "Limpieza de reportes: %d eliminados, %d restantes (max_age=%d dias)",
            deleted, remaining, max_age_days,
        )
    return {"deleted": deleted, "remaining": remaining}


# ---- routes ----------------------------------------------------------------


@bp.route("/")
def index():
    """Pagina principal de reportes."""
    return render_template("reports.html")


@bp.route("/api/list")
def api_list():
    """Devuelve todos los reportes agrupados por categoria y subtipo."""
    return jsonify({"categories": _scan_reports()})


@bp.route("/api/list/<category>")
def api_list_category(category: str):
    """Devuelve reportes de una sola categoria."""
    cats = _scan_reports(category)
    if not cats:
        return jsonify({"error": "Category not found"}), 404
    return jsonify({"categories": cats})


@bp.route("/api/cleanup")
def api_cleanup():
    """Ejecuta limpieza de reportes antiguos y devuelve resultado."""
    result = cleanup_old_reports()
    return jsonify(result)
