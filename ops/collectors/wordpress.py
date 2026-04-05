"""WordPress health collector — WP-Cron, database, updates, errors, content."""

from __future__ import annotations

import json
import logging
from pathlib import Path

from ..config import Settings
from ..services import compose_service_name
from ..util.docker import compose_exec
from ..util.time import utc_timestamp

logger = logging.getLogger(__name__)


def _wp_metrics(context: str, *, cwd: Path) -> dict[str, object]:
    """Run wp-metrics.php inside cron-master for the given site context."""
    service = compose_service_name("cron-master")
    result = compose_exec(
        service,
        [
            "env", f"N9_SITE_CONTEXT={context}",
            "wp", "--allow-root", "eval-file",
            "/opt/project/scripts/internal/wp-metrics.php",
            f"--path=/srv/wp/site",
        ],
        cwd=cwd,
        check=False,
        exec_args=["--user", "root"],
    )
    if result.returncode != 0:
        logger.warning(
            "wp-metrics.php failed for %s (rc=%d): %s",
            context, result.returncode, result.stderr,
        )
        return {"error": f"wp-metrics fallo para {context}"}
    try:
        return json.loads(result.stdout)
    except (json.JSONDecodeError, ValueError) as exc:
        logger.warning("wp-metrics.php returned invalid JSON for %s: %s", context, exc)
        return {"error": f"JSON invalido desde wp-metrics ({context})"}


def collect(settings: Settings) -> dict[str, object]:
    """Collect WordPress health data for live and archive contexts."""
    cwd = settings.project_root.resolve()
    result: dict[str, object] = {"generated_at": utc_timestamp()}

    for context in ("live", "archive"):
        try:
            result[context] = _wp_metrics(context, cwd=cwd)
        except Exception as exc:
            logger.exception("WordPress collector failed for %s", context)
            result[context] = {"error": str(exc)}

    return result
