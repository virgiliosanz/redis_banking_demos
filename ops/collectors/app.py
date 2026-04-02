from __future__ import annotations

from pathlib import Path

from ..config import Settings
from ..util.http import get_status_code
from ..util.process import run_command
from ..util.time import utc_timestamp


def _run_smoke(name: str, script: str) -> dict[str, str]:
    result = run_command([script], cwd=Path.cwd(), check=False)
    return {"name": name, "status": "ok" if result.returncode == 0 else "critical"}


def collect(settings: Settings) -> dict[str, object]:
    base_url = settings.get("BASE_URL", "http://nuevecuatrouno.test") or "http://nuevecuatrouno.test"
    archive_url = settings.get("ARCHIVE_URL", "http://archive.nuevecuatrouno.test") or "http://archive.nuevecuatrouno.test"

    live_login_code = get_status_code(f"{base_url}/wp-login.php")
    archive_login_code = get_status_code(f"{archive_url}/wp-login.php")
    search_code = get_status_code(f"{base_url}/?s=rioja-laboratorio")

    smoke_scripts = [
        _run_smoke("routing", "./scripts/smoke-routing.sh"),
        _run_smoke("search", "./scripts/smoke-search.sh"),
        _run_smoke("services", "./scripts/smoke-services.sh"),
    ]

    return {
        "generated_at": utc_timestamp(),
        "checks": {
            "live_login": {
                "http_code": live_login_code,
                "status": "ok" if live_login_code == 200 else "critical",
            },
            "archive_login": {
                "http_code": archive_login_code,
                "status": "ok" if archive_login_code == 200 else "critical",
            },
            "unified_search_endpoint": {
                "http_code": search_code,
                "status": "ok" if search_code == 200 else "critical",
            },
            "smoke_scripts": smoke_scripts,
        },
    }
