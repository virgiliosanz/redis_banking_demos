from __future__ import annotations

import subprocess
from pathlib import Path

from ..config import Settings
from ..util.docker import service_logs


def collect_service_logs(settings: Settings, service: str, pattern: str | None = None) -> str:
    regex = pattern or "ERROR|FATAL|CRITICAL"
    raw = service_logs(service, tail_lines=settings.get_int("LOG_TAIL_LINES", 500), cwd=settings.project_root.resolve())

    grep = subprocess.run(
        ["grep", "-E", regex],
        input=raw,
        capture_output=True,
        text=True,
        check=False,
    )
    filtered = grep.stdout
    if not filtered:
        return ""

    redact = subprocess.run(
        ["./scripts/redact-sensitive.sh"],
        input=filtered,
        capture_output=True,
        text=True,
        check=False,
    )
    return redact.stdout
