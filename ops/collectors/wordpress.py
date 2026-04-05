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


def _transform_metrics(raw: dict[str, object]) -> dict[str, object]:
    """Transform flat wp-metrics.php output into structured sections.

    The PHP script returns ``{"metrics": {"cron_events_total": …, …}}``.
    The diagnostics template expects nested dicts keyed by section:
    ``cron``, ``database``, ``updates``, ``errors``, ``content``.
    """
    metrics = raw.get("metrics", raw)
    if not isinstance(metrics, dict):
        return raw

    result: dict[str, object] = {}

    # Cron section (live only)
    if "cron_events_total" in metrics:
        result["cron"] = {
            "total": metrics.get("cron_events_total", 0),
            "overdue": metrics.get("cron_events_overdue", 0),
            "max_overdue_seconds": metrics.get("cron_events_overdue_max_age", 0),
        }

    # Database section
    if "db_size_mb" in metrics:
        result["database"] = {
            "size_mb": metrics.get("db_size_mb", 0),
            "autoload_count": metrics.get("autoload_count", 0),
            "autoload_size_kb": metrics.get("autoload_size_kb", 0),
            "transients_count": metrics.get("transients_count", 0),
        }

    # Updates section (live only)
    if "plugins_update_available" in metrics:
        result["updates"] = {
            "plugins": metrics.get("plugins_update_available", 0),
            "themes": metrics.get("themes_update_available", 0),
        }

    # Errors section
    if "php_error_count" in metrics:
        result["errors"] = {
            "php_error_count": metrics.get("php_error_count", 0),
        }

    # Content section (live only)
    if "posts_published" in metrics:
        result["content"] = {
            "posts_published": metrics.get("posts_published", 0),
            "posts_draft": metrics.get("posts_draft", 0),
            "pages_published": metrics.get("pages_published", 0),
        }

    return result


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
        raw = json.loads(result.stdout)
    except (json.JSONDecodeError, ValueError) as exc:
        logger.warning("wp-metrics.php returned invalid JSON for %s: %s", context, exc)
        return {"error": f"JSON invalido desde wp-metrics ({context})"}
    # Transform flat metrics into structured sections for the template
    return _transform_metrics(raw)


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
