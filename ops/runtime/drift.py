from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

from ..config import Settings
from ..reporting import write_text_report
from ..services import compose_service_name, wait_for_service_keys
from ..util.docker import compose_exec
from ..util.jsonio import dumps_pretty, loads_json
from ..util.time import report_stamp, utc_timestamp


@dataclass(frozen=True)
class DriftSection:
    status: str
    summary: list[str]
    details: dict[str, object]
    live_snapshot: dict[str, object]
    archive_snapshot: dict[str, object]


@dataclass(frozen=True)
class DriftStatus:
    report_file: str
    content: str
    editorial: DriftSection
    platform: DriftSection


def _wp_eval_json(path: str, script_path: str, excluded_logins: str, *, cwd: Path) -> str:
    result = compose_exec(
        compose_service_name("cron-master"),
        [
            "env",
            f"SYNC_EXCLUDE_USER_LOGINS={excluded_logins}",
            "wp",
            "--allow-root",
            "eval-file",
            script_path,
            f"--path={path}",
        ],
        cwd=cwd,
        exec_args=["--user", "root"],
    )
    return result.stdout.strip()


def _load_snapshot(raw: str) -> dict[str, object]:
    payload = loads_json(raw)
    if not isinstance(payload, dict):
        raise ValueError("snapshot payload must be a JSON object")
    return payload


def _string_list(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [str(item) for item in value]


def _format_values(values: list[str], *, max_items: int = 6) -> str:
    if not values:
        return "none"
    shown = values[:max_items]
    suffix = "" if len(values) <= max_items else f" (+{len(values) - max_items} mas)"
    return ", ".join(shown) + suffix


def _count_label(count: int, singular: str, plural: str | None = None) -> str:
    suffix = singular if count == 1 else plural or f"{singular}s"
    return f"{count} {suffix}"


def _editorial_user_index(snapshot: dict[str, object]) -> dict[str, dict[str, object]]:
    users = snapshot.get("users")
    if not isinstance(users, list):
        return {}

    indexed: dict[str, dict[str, object]] = {}
    for row in users:
        if not isinstance(row, dict):
            continue
        login = row.get("login")
        if not isinstance(login, str) or not login:
            continue
        indexed[login] = row
    return indexed


def _user_changed_fields(live_user: dict[str, object], archive_user: dict[str, object]) -> list[str]:
    fields = ["email", "display_name", "nicename", "status", "roles", "caps_hash", "password_hash_digest"]
    return [field for field in fields if live_user.get(field) != archive_user.get(field)]


def _format_changed_users(rows: list[dict[str, object]], *, max_items: int = 5) -> str:
    if not rows:
        return "none"

    chunks: list[str] = []
    for row in rows[:max_items]:
        login = str(row["login"])
        changed_fields = row.get("changed_fields")
        if isinstance(changed_fields, list):
            chunks.append(f"{login}({','.join(str(field) for field in changed_fields)})")
        else:
            chunks.append(login)
    suffix = "" if len(rows) <= max_items else f" (+{len(rows) - max_items} mas)"
    return "; ".join(chunks) + suffix


def _compare_editorial_snapshots(live_snapshot: dict[str, object], archive_snapshot: dict[str, object]) -> DriftSection:
    live_users = _editorial_user_index(live_snapshot)
    archive_users = _editorial_user_index(archive_snapshot)

    live_logins = sorted(live_users)
    archive_logins = sorted(archive_users)
    only_in_live = sorted(set(live_logins) - set(archive_logins))
    only_in_archive = sorted(set(archive_logins) - set(live_logins))

    changed_users: list[dict[str, object]] = []
    for login in sorted(set(live_logins) & set(archive_logins)):
        changed_fields = _user_changed_fields(live_users[login], archive_users[login])
        if changed_fields:
            changed_users.append({"login": login, "changed_fields": changed_fields})

    site_mismatch = live_snapshot.get("site") != archive_snapshot.get("site")
    excluded_mismatch = _string_list(live_snapshot.get("excluded_logins")) != _string_list(archive_snapshot.get("excluded_logins"))

    status = "yes" if only_in_live or only_in_archive or changed_users or site_mismatch or excluded_mismatch else "no"
    details = {
        "live_user_count": len(live_logins),
        "archive_user_count": len(archive_logins),
        "only_in_live_logins": only_in_live,
        "only_in_archive_logins": only_in_archive,
        "changed_users": changed_users,
        "site_mismatch": site_mismatch,
        "excluded_logins_mismatch": excluded_mismatch,
    }
    summary = [
        f"- live_user_count: {len(live_logins)}",
        f"- archive_user_count: {len(archive_logins)}",
        f"- only_in_live_logins: {_format_values(only_in_live)}",
        f"- only_in_archive_logins: {_format_values(only_in_archive)}",
        f"- changed_users: {_format_changed_users(changed_users)}",
        f"- site_mismatch: {'yes' if site_mismatch else 'no'}",
        f"- excluded_logins_mismatch: {'yes' if excluded_mismatch else 'no'}",
    ]
    return DriftSection(
        status=status,
        summary=summary,
        details=details,
        live_snapshot=live_snapshot,
        archive_snapshot=archive_snapshot,
    )


def _compare_platform_snapshots(live_snapshot: dict[str, object], archive_snapshot: dict[str, object]) -> DriftSection:
    scalar_fields = ["site", "stylesheet", "template"]
    scalar_mismatches = [field for field in scalar_fields if live_snapshot.get(field) != archive_snapshot.get(field)]

    live_plugins = sorted(set(_string_list(live_snapshot.get("active_plugins"))))
    archive_plugins = sorted(set(_string_list(archive_snapshot.get("active_plugins"))))
    only_in_live_plugins = sorted(set(live_plugins) - set(archive_plugins))
    only_in_archive_plugins = sorted(set(archive_plugins) - set(live_plugins))

    hash_fields = ["theme_mods_hash", "sidebars_widgets_hash", "nav_menu_locations_hash"]
    hash_mismatches = [field for field in hash_fields if live_snapshot.get(field) != archive_snapshot.get(field)]

    allowlist_mismatch = _string_list(live_snapshot.get("allowlist_option_names")) != _string_list(archive_snapshot.get("allowlist_option_names"))

    status = "yes" if scalar_mismatches or only_in_live_plugins or only_in_archive_plugins or hash_mismatches or allowlist_mismatch else "no"
    details = {
        "scalar_mismatches": scalar_mismatches,
        "active_plugins_only_in_live": only_in_live_plugins,
        "active_plugins_only_in_archive": only_in_archive_plugins,
        "hash_mismatches": hash_mismatches,
        "allowlist_option_names_mismatch": allowlist_mismatch,
    }
    summary = [
        f"- scalar_mismatches: {_format_values(scalar_mismatches)}",
        f"- active_plugins_only_in_live: {_format_values(only_in_live_plugins)}",
        f"- active_plugins_only_in_archive: {_format_values(only_in_archive_plugins)}",
        f"- hash_mismatches: {_format_values(hash_mismatches)}",
        f"- allowlist_option_names_mismatch: {'yes' if allowlist_mismatch else 'no'}",
    ]
    return DriftSection(
        status=status,
        summary=summary,
        details=details,
        live_snapshot=live_snapshot,
        archive_snapshot=archive_snapshot,
    )


def format_drift_summary(section: DriftSection) -> str:
    details = section.details
    if "changed_users" in details:
        only_in_live = len(details["only_in_live_logins"]) if isinstance(details.get("only_in_live_logins"), list) else 0
        only_in_archive = len(details["only_in_archive_logins"]) if isinstance(details.get("only_in_archive_logins"), list) else 0
        changed_users = len(details["changed_users"]) if isinstance(details.get("changed_users"), list) else 0
        if section.status == "no":
            return "sin diferencias editoriales"
        return (
            f"{_count_label(only_in_live, 'login')} solo en live, "
            f"{_count_label(only_in_archive, 'login')} solo en archive, "
            f"{_count_label(changed_users, 'usuario')} cambiado(s)"
        )

    scalar_mismatches = len(details["scalar_mismatches"]) if isinstance(details.get("scalar_mismatches"), list) else 0
    plugins_live = len(details["active_plugins_only_in_live"]) if isinstance(details.get("active_plugins_only_in_live"), list) else 0
    plugins_archive = len(details["active_plugins_only_in_archive"]) if isinstance(details.get("active_plugins_only_in_archive"), list) else 0
    hash_mismatches = len(details["hash_mismatches"]) if isinstance(details.get("hash_mismatches"), list) else 0
    if section.status == "no":
        return "sin diferencias de plataforma"
    return (
        f"{_count_label(scalar_mismatches, 'campo base', 'campos base')}, "
        f"{_count_label(plugins_live, 'plugin')} solo en live, "
        f"{_count_label(plugins_archive, 'plugin')} solo en archive, "
        f"{_count_label(hash_mismatches, 'hash', 'hashes')} distinto(s)"
    )


def build_drift_report(settings: Settings) -> DriftStatus:
    excluded_logins = settings.get("SYNC_EXCLUDE_USER_LOGINS", "n9liveadmin,n9archiveadmin")
    report_dir = settings.get_path("REPORT_DIR", "./runtime/reports/sync")
    project_root = settings.project_root.resolve()

    wait_for_service_keys(settings, ("db-live", "db-archive", "cron-master"))

    live_editorial = _load_snapshot(
        _wp_eval_json(
            "/srv/wp/live",
            "/opt/project/scripts/internal/sync/editorial/snapshot.php",
            excluded_logins,
            cwd=project_root,
        )
    )
    archive_editorial = _load_snapshot(
        _wp_eval_json(
            "/srv/wp/archive",
            "/opt/project/scripts/internal/sync/editorial/snapshot.php",
            excluded_logins,
            cwd=project_root,
        )
    )
    live_platform = _load_snapshot(
        _wp_eval_json(
            "/srv/wp/live",
            "/opt/project/scripts/internal/sync/platform/snapshot.php",
            excluded_logins,
            cwd=project_root,
        )
    )
    archive_platform = _load_snapshot(
        _wp_eval_json(
            "/srv/wp/archive",
            "/opt/project/scripts/internal/sync/platform/snapshot.php",
            excluded_logins,
            cwd=project_root,
        )
    )

    editorial = _compare_editorial_snapshots(live_editorial, archive_editorial)
    platform = _compare_platform_snapshots(live_platform, archive_platform)

    content = f"""# Drift report live/archive

- generated_at: {utc_timestamp()}
- excluded_bootstrap_logins: {excluded_logins}
- editorial_drift: {editorial.status}
- platform_drift: {platform.status}

## Editorial drift summary
{chr(10).join(editorial.summary)}
- editorial_brief: {format_drift_summary(editorial)}

## Platform drift summary
{chr(10).join(platform.summary)}
- platform_brief: {format_drift_summary(platform)}

## Live editorial snapshot
```json
{dumps_pretty(editorial.live_snapshot)}
```

## Archive editorial snapshot
```json
{dumps_pretty(editorial.archive_snapshot)}
```

## Live platform snapshot
```json
{dumps_pretty(platform.live_snapshot)}
```

## Archive platform snapshot
```json
{dumps_pretty(platform.archive_snapshot)}
```
"""
    report_file = write_text_report(report_dir, f"live-archive-sync-{report_stamp()}.md", content)
    return DriftStatus(
        report_file=str(report_file),
        content=content,
        editorial=editorial,
        platform=platform,
    )
