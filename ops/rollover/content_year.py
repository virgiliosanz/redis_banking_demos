from __future__ import annotations

import json
from pathlib import Path

from ..config import Settings
from ..runtime.heartbeats import write_heartbeat
from ..util.docker import compose_exec, wait_for_container_health
from ..util.process import run_command
from ..util.time import report_stamp, utc_timestamp


VALID_ROLLOVER_MODES = {"dry-run", "report-only", "execute"}


def _parse_cutover_config(path: Path) -> dict[str, int]:
    raw = path.read_text(encoding="utf-8").splitlines()
    values: dict[str, int] = {}
    for line in raw:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key] = int(value)
    required = ["ARCHIVE_MIN_YEAR", "ARCHIVE_MAX_YEAR", "LIVE_MIN_YEAR", "LIVE_MAX_YEAR"]
    missing = [key for key in required if key not in values]
    if missing:
        raise RuntimeError(f"Missing routing cutover keys: {', '.join(missing)}")
    return values


def _rollover_env(mode: str, target_year: int) -> list[str]:
    return [
        "env",
        f"ROLLOVER_TARGET_YEAR={target_year}",
        f"ROLLOVER_MODE={mode}",
    ]


def _wp_eval_file(cwd: Path, *, path: str, script_path: str, mode: str, target_year: int, snapshot_file: str | None = None) -> str:
    env_args = _rollover_env(mode, target_year)
    if snapshot_file:
        env_args.append(f"ROLLOVER_SNAPSHOT_FILE={snapshot_file}")
    result = compose_exec(
        "cron-master",
        [*env_args, "wp", "--allow-root", "eval-file", script_path, f"--path={path}"],
        cwd=cwd,
        exec_args=["--user", "root"],
    )
    return result.stdout.strip()


def _archive_collisions(cwd: Path, *, path: str, slug_csv: str, target_year: int) -> str:
    result = compose_exec(
        "cron-master",
        [
            "env",
            f"ROLLOVER_SLUGS_CSV={slug_csv}",
            f"ROLLOVER_TARGET_YEAR={target_year}",
            "wp",
            "--allow-root",
            "eval-file",
            "/opt/project/scripts/rollover-detect-archive-collisions.php",
            f"--path={path}",
        ],
        cwd=cwd,
        exec_args=["--user", "root"],
    )
    return result.stdout.strip()


def _write_remote_snapshot(cwd: Path, local_file: Path, remote_file: str) -> None:
    compose_exec(
        "cron-master",
        ["sh", "-lc", f"cat > '{remote_file}'"],
        cwd=cwd,
        check=True,
    )
    run_command(
        ["docker", "compose", "exec", "-T", "cron-master", "sh", "-lc", f"cat > '{remote_file}' < /dev/stdin"],
        cwd=cwd,
        check=True,
        env=None,
    )


def _copy_to_container(cwd: Path, local_file: Path, remote_file: str) -> None:
    with local_file.open("rb") as handle:
        import subprocess

        completed = subprocess.run(
            ["docker", "compose", "exec", "-T", "cron-master", "sh", "-lc", f"cat > '{remote_file}'"],
            cwd=str(cwd),
            stdin=handle,
            check=True,
        )
        _ = completed


def _reindex_site(cwd: Path, *, path: str, prefix: str, ep_host: str) -> None:
    compose_exec(
        "cron-master",
        [
            "wp",
            "--allow-root",
            "elasticpress",
            "sync",
            "--setup",
            "--yes",
            f"--path={path}",
            f"--ep-host={ep_host}",
            f"--ep-prefix={prefix}",
        ],
        cwd=cwd,
        exec_args=["--user", "root"],
    )


def _get_index_name(cwd: Path, *, path: str) -> str:
    result = compose_exec(
        "cron-master",
        ["wp", "--allow-root", "elasticpress", "get-indices", f"--path={path}"],
        cwd=cwd,
        exec_args=["--user", "root"],
    )
    return result.stdout.strip().strip("[]\"").split(",")[0]


def _publish_read_alias(cwd: Path, *, alias: str, live_index: str, archive_index: str) -> None:
    payload = {
        "actions": [
            {"remove": {"index": "*", "alias": alias, "must_exist": False}},
            {"add": {"index": live_index, "alias": alias}},
            {"add": {"index": archive_index, "alias": alias}},
        ]
    }
    json_payload = json.dumps(payload, separators=(",", ":"))
    compose_exec(
        "elastic",
        [
            "sh",
            "-lc",
            (
                "cat <<'JSON' >/tmp/n9-alias.json\n"
                f"{json_payload}\n"
                "JSON\n"
                "curl -fsS -H 'Content-Type: application/json' -X POST "
                "http://127.0.0.1:9200/_aliases --data-binary @/tmp/n9-alias.json >/dev/null\n"
                f"curl -fsS http://127.0.0.1:9200/_alias/{alias} >/dev/null"
            ),
        ],
        cwd=cwd,
    )


def _advance_cutover(cwd: Path, routing_config_file: Path, target_year: int) -> None:
    run_command(["./scripts/advance-routing-cutover.sh", str(routing_config_file), str(target_year)], cwd=cwd)
    run_command(["./scripts/render-routing-cutover.sh", str(routing_config_file)], cwd=cwd)
    compose_exec("lb-nginx", ["nginx", "-s", "reload"], cwd=cwd)


def _write_rollover_heartbeat(settings: Settings) -> None:
    heartbeat_dir = settings.get_path("CRON_HEARTBEAT_DIR", "./runtime/heartbeats")
    heartbeat_dir = settings.project_root.resolve() / heartbeat_dir if not heartbeat_dir.is_absolute() else heartbeat_dir
    job_name = settings.get("CRON_JOB_ROLLOVER", "rollover-content-year") or "rollover-content-year"
    write_heartbeat(heartbeat_dir, job_name)


def run(
    settings: Settings,
    *,
    mode: str,
    target_year: int,
    report_dir: Path | None = None,
    routing_config_file: Path | None = None,
) -> Path:
    if mode not in VALID_ROLLOVER_MODES:
        raise ValueError(f"Unsupported mode: {mode}")

    current_year = int(run_command(["date", "+%Y"]).stdout.strip())
    if target_year >= current_year:
        raise RuntimeError(f"Target year must be earlier than current year ({current_year}).")

    cwd = settings.project_root.resolve()
    report_root = report_dir or settings.get_path("REPORT_DIR", "./runtime/reports/rollover")
    if not report_root.is_absolute():
        report_root = cwd / report_root
    report_root.mkdir(parents=True, exist_ok=True)

    routing_file = routing_config_file or settings.get_path("ROUTING_CONFIG_FILE", "./config/routing-cutover.env")
    if not routing_file.is_absolute():
        routing_file = cwd / routing_file
    if not routing_file.exists():
        raise RuntimeError(f"Missing routing config: {routing_file}")

    cutover = _parse_cutover_config(routing_file)
    archive_min_year = cutover["ARCHIVE_MIN_YEAR"]
    archive_max_year = cutover["ARCHIVE_MAX_YEAR"]
    live_min_year = cutover["LIVE_MIN_YEAR"]
    live_max_year = cutover["LIVE_MAX_YEAR"]

    next_archive_max = archive_max_year
    next_live_min = live_min_year
    cutover_warning = ""
    if target_year < live_min_year:
        cutover_warning = "target_year_is_already_below_live_cutover"
    elif target_year > live_min_year:
        cutover_warning = "target_year_skips_current_live_cutover"
    else:
        next_archive_max = target_year
        next_live_min = target_year + 1

    if mode == "execute" and target_year != live_min_year:
        raise RuntimeError(f"Mode execute requires target year to match LIVE_MIN_YEAR ({live_min_year}).")

    for container in ("n9-db-live", "n9-db-archive", "n9-cron-master"):
        wait_for_container_health(container)

    live_summary = _wp_eval_file(
        cwd,
        path="/srv/wp/live",
        script_path="/opt/project/scripts/rollover-collect-year-summary.php",
        mode=mode,
        target_year=target_year,
    )
    live_summary_json = json.loads(live_summary)
    live_slug_csv = live_summary_json.get("slugs_csv", "")

    archive_collisions = _archive_collisions(
        cwd,
        path="/srv/wp/archive",
        slug_csv=live_slug_csv,
        target_year=target_year,
    )
    archive_collisions_json = json.loads(archive_collisions)
    source_snapshot = _wp_eval_file(
        cwd,
        path="/srv/wp/live",
        script_path="/opt/project/scripts/rollover-export-year.php",
        mode=mode,
        target_year=target_year,
    )
    archive_backup_snapshot = _wp_eval_file(
        cwd,
        path="/srv/wp/archive",
        script_path="/opt/project/scripts/rollover-export-year.php",
        mode=mode,
        target_year=target_year,
    )

    selected_posts = int(live_summary_json.get("selected_post_count", 0))
    selected_terms = int(live_summary_json.get("selected_term_count", 0))
    selected_attachments = int(live_summary_json.get("selected_attachment_count", 0))
    collision_count = int(archive_collisions_json.get("collision_count", 0))

    stamp = report_stamp()
    report_file = report_root / f"{target_year}-{mode}-{stamp}.md"
    artifact_dir = report_root / f"{target_year}-{mode}-{stamp}"
    artifact_dir.mkdir(parents=True, exist_ok=True)

    source_snapshot_file = artifact_dir / f"source-live-{target_year}.json"
    archive_backup_file = artifact_dir / f"archive-backup-{target_year}.json"
    source_snapshot_file.write_text(f"{source_snapshot}\n", encoding="utf-8")
    archive_backup_file.write_text(f"{archive_backup_snapshot}\n", encoding="utf-8")

    ep_host = settings.get("EP_HOST", "http://elastic:9200") or "http://elastic:9200"
    ep_search_alias = settings.get("EP_SEARCH_ALIAS", "n9-search-posts") or "n9-search-posts"
    archive_ep_prefix = settings.get("ARCHIVE_EP_PREFIX", "n9-archive") or "n9-archive"
    live_ep_prefix = settings.get("LIVE_EP_PREFIX", "n9-live") or "n9-live"

    import_result = ""
    delete_result = ""
    execute_enabled = "no"

    if mode == "execute":
        if selected_posts == 0:
            raise RuntimeError("Execute mode requires at least one selected post.")
        if collision_count != 0:
            raise RuntimeError("Execute mode requires zero archive slug collisions.")

        remote_source_snapshot = f"/tmp/rollover-source-{target_year}.json"
        _copy_to_container(cwd, source_snapshot_file, remote_source_snapshot)
        import_result = _wp_eval_file(
            cwd,
            path="/srv/wp/archive",
            script_path="/opt/project/scripts/rollover-import-snapshot.php",
            mode=mode,
            target_year=target_year,
            snapshot_file=remote_source_snapshot,
        )
        _reindex_site(cwd, path="/srv/wp/archive", prefix=archive_ep_prefix, ep_host=ep_host)
        _advance_cutover(cwd, routing_file, target_year)
        delete_result = _wp_eval_file(
            cwd,
            path="/srv/wp/live",
            script_path="/opt/project/scripts/rollover-delete-source-posts.php",
            mode=mode,
            target_year=target_year,
            snapshot_file=remote_source_snapshot,
        )
        _reindex_site(cwd, path="/srv/wp/live", prefix=live_ep_prefix, ep_host=ep_host)
        live_index = _get_index_name(cwd, path="/srv/wp/live")
        archive_index = _get_index_name(cwd, path="/srv/wp/archive")
        _publish_read_alias(cwd, alias=ep_search_alias, live_index=live_index, archive_index=archive_index)
        _write_rollover_heartbeat(settings)
        execute_enabled = "yes"

    content = [
        f"# Rollover {mode} {target_year}",
        "",
        f"- generated_at: {utc_timestamp()}",
        f"- mode: {mode}",
        f"- target_year: {target_year}",
        f"- routing_archive_min_year: {archive_min_year}",
        f"- routing_archive_max_year: {archive_max_year}",
        f"- routing_live_min_year: {live_min_year}",
        f"- routing_live_max_year: {live_max_year}",
        f"- next_archive_max_year_if_applied: {next_archive_max}",
        f"- next_live_min_year_if_applied: {next_live_min}",
        f"- cutover_warning: {cutover_warning or 'none'}",
        f"- selected_posts: {selected_posts}",
        f"- selected_terms: {selected_terms}",
        f"- selected_attachments: {selected_attachments}",
        f"- archive_slug_collisions: {collision_count}",
        f"- execute_enabled: {execute_enabled}",
        f"- source_snapshot_file: {source_snapshot_file}",
        f"- archive_backup_file: {archive_backup_file}",
        "",
        "## Live summary JSON",
        "```json",
        live_summary,
        "```",
        "",
        "## Archive collision JSON",
        "```json",
        archive_collisions,
        "```",
        "",
        "## Source snapshot JSON",
        "```json",
        source_snapshot,
        "```",
        "",
        "## Archive backup snapshot JSON",
        "```json",
        archive_backup_snapshot,
        "```",
        "",
    ]

    if import_result:
        content.extend(["## Import result JSON", "```json", import_result, "```", ""])
    if delete_result:
        content.extend(["## Delete result JSON", "```json", delete_result, "```", ""])

    content.extend(
        [
            "## Notes",
            f"- The routing cutover is now versioned via `{routing_file}`.",
            "- `execute` is only valid when target year matches current `LIVE_MIN_YEAR`.",
            "",
        ]
    )

    report_file.write_text("\n".join(content), encoding="utf-8")
    return report_file
