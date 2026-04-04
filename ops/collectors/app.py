from __future__ import annotations

from pathlib import Path

from ..config import DEFAULT_ARCHIVE_URL, DEFAULT_BASE_URL, Settings
from ..util.http import get_status_code
from ..util.process import run_command
from ..util.time import utc_timestamp


def _http_check(url: str, *, expected_http_code: int = 200) -> dict[str, object]:
    http_code = get_status_code(url)
    if http_code == expected_http_code:
        status = "ok"
        reason = ""
    elif http_code == 0:
        status = "unreachable"
        reason = "No se pudo conectar al servicio (timeout o conexion rechazada)"
    else:
        status = "critical"
        reason = f"Esperado HTTP {expected_http_code}, recibido {http_code}"
    return {
        "url": url,
        "http_code": http_code,
        "expected_http_code": expected_http_code,
        "status": status,
        "reason": reason,
    }


def _run_smoke(name: str, script: str, *, cwd: Path) -> dict[str, object]:
    result = run_command([script], cwd=cwd, check=False)
    ok = result.returncode == 0
    entry: dict[str, object] = {
        "name": name,
        "script": script,
        "source": "local_smoke_script",
        "status": "ok" if ok else "critical",
    }
    if not ok:
        detail = (result.stderr or result.stdout or "").strip()
        # Limit detail length to avoid bloating the payload
        if len(detail) > 300:
            detail = detail[:300] + "..."
        entry["error_detail"] = detail or f"exit code {result.returncode}"
    return entry


def collect(settings: Settings) -> dict[str, object]:
    base_url = settings.get("BASE_URL", DEFAULT_BASE_URL)
    archive_url = settings.get("ARCHIVE_URL", DEFAULT_ARCHIVE_URL)

    project_root = settings.project_root.resolve()
    smoke_scripts = [
        _run_smoke("routing", "./scripts/smoke-routing.sh", cwd=project_root),
        _run_smoke("search", "./scripts/smoke-search.sh", cwd=project_root),
        _run_smoke("services", "./scripts/smoke-services.sh", cwd=project_root),
    ]

    return {
        "generated_at": utc_timestamp(),
        "checks": {
            "live_login": _http_check(f"{base_url}/wp-login.php"),
            "archive_login": _http_check(f"{archive_url}/wp-login.php"),
            "unified_search_endpoint": _http_check(f"{base_url}/?s=rioja-laboratorio"),
            "smoke_scripts": smoke_scripts,
        },
    }
