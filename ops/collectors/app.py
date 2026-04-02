from __future__ import annotations

from pathlib import Path

from ..config import Settings
from ..util.http import get_status_code
from ..util.process import run_command
from ..util.time import utc_timestamp


def _http_check(url: str, *, expected_http_code: int = 200) -> dict[str, object]:
    http_code = get_status_code(url)
    return {
        "url": url,
        "http_code": http_code,
        "expected_http_code": expected_http_code,
        "status": "ok" if http_code == expected_http_code else "critical",
    }


def _run_smoke(name: str, script: str) -> dict[str, object]:
    result = run_command([script], cwd=Path.cwd(), check=False)
    return {
        "name": name,
        "script": script,
        "source": "local_smoke_script",
        "status": "ok" if result.returncode == 0 else "critical",
    }


def collect(settings: Settings) -> dict[str, object]:
    base_url = settings.get("BASE_URL", "http://nuevecuatrouno.test") or "http://nuevecuatrouno.test"
    archive_url = settings.get("ARCHIVE_URL", "http://archive.nuevecuatrouno.test") or "http://archive.nuevecuatrouno.test"

    smoke_scripts = [
        _run_smoke("routing", "./scripts/smoke-routing.sh"),
        _run_smoke("search", "./scripts/smoke-search.sh"),
        _run_smoke("services", "./scripts/smoke-services.sh"),
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
