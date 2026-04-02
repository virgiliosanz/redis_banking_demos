from __future__ import annotations

from pathlib import Path

from .util.jsonio import dumps_pretty


def ensure_directory(path: Path) -> Path:
    path.mkdir(parents=True, exist_ok=True)
    return path


def write_text_report(report_root: Path, filename: str, content: str) -> Path:
    ensure_directory(report_root)
    report_file = report_root / filename
    report_file.write_text(content, encoding="utf-8")
    return report_file


def write_json_report(report_root: Path, filename: str, payload: object) -> Path:
    ensure_directory(report_root)
    report_file = report_root / filename
    report_file.write_text(dumps_pretty(payload) + "\n", encoding="utf-8")
    return report_file
