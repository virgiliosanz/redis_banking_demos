from __future__ import annotations

import re

from ..config import Settings
from ..util.docker import service_logs
from ..util.process import run_command


def collect_service_logs(settings: Settings, service: str, pattern: str | None = None) -> str:
    regex = pattern or r"ERROR|FATAL|CRITICAL"
    raw = service_logs(service, tail_lines=settings.get_int("LOG_TAIL_LINES", 500), cwd=settings.project_root.resolve())

    compiled = re.compile(regex, flags=re.IGNORECASE)
    filtered = "\n".join(line for line in raw.splitlines() if compiled.search(line))
    if not filtered:
        return ""

    redact_script = str((settings.project_root / "scripts/redact-sensitive.sh").resolve())
    result = run_command([redact_script], input=filtered, check=False)
    return result.stdout if result.returncode == 0 else filtered
