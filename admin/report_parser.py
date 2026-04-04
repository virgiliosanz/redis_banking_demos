"""Report parsing utilities.

Pure functions for extracting structured data from nightly, drift and
other markdown reports.  No Flask dependency.
"""

from __future__ import annotations

import json
from typing import Any


def extract_json_blocks(content: str) -> list[tuple[str, dict[str, Any]]]:
    """Return ``(section_heading, parsed_dict)`` for each JSON code block."""
    results: list[tuple[str, dict[str, Any]]] = []
    current_heading = ""
    in_block = False
    block_lines: list[str] = []
    for line in content.splitlines():
        if line.startswith("## "):
            current_heading = line[3:].strip()
        elif line.strip() == "```json":
            in_block = True
            block_lines = []
        elif line.strip() == "```" and in_block:
            in_block = False
            try:
                parsed = json.loads("\n".join(block_lines))
                results.append((current_heading, parsed))
            except (json.JSONDecodeError, ValueError):
                pass
        elif in_block:
            block_lines.append(line)
    return results


def collect_non_ok_checks(
    data: dict[str, Any], section: str
) -> list[dict[str, str]]:
    """Walk a collector JSON dict and return findings for non-ok checks."""
    findings: list[dict[str, str]] = []

    checks: dict[str, Any] | None = data.get("checks")
    if isinstance(checks, dict):
        for name, val in checks.items():
            if isinstance(val, dict) and val.get("status") not in (
                "ok",
                "not_supported",
                None,
            ):
                status = val["status"]
                sev = "error" if status == "critical" else status
                text = f"{section}: {name} = {status}"
                detail = val.get("count")
                if detail is not None:
                    text += f" (count: {detail})"
                pct = val.get("used_pct")
                if pct is not None:
                    text += f" ({pct}%)"
                findings.append({"severity": sev, "text": text})
            if isinstance(val, list):
                for item in val:
                    if isinstance(item, dict) and item.get("status") not in (
                        "ok",
                        "not_supported",
                        None,
                    ):
                        item_status = item["status"]
                        sev = "error" if item_status == "critical" else item_status
                        item_name = item.get("name") or item.get("script", name)
                        findings.append({
                            "severity": sev,
                            "text": f"{section}: {item_name} = {item_status}",
                        })

    # Cron jobs
    jobs = data.get("jobs")
    if isinstance(jobs, list):
        for job in jobs:
            if isinstance(job, dict) and job.get("status") not in ("ok", "info", None):
                sev = "error" if job["status"] == "critical" else job["status"]
                findings.append({
                    "severity": sev,
                    "text": f"{section}: {job.get('job_name', '?')} heartbeat {job['status']}",
                })

    # Elasticsearch cluster_health
    cluster = data.get("cluster_health")
    if isinstance(cluster, dict):
        cs = cluster.get("collector_status", cluster.get("status", ""))
        if cs not in ("ok", "green", ""):
            sev = "error" if cs in ("critical", "red") else "warning"
            es_status = cluster.get("status", cs)
            findings.append({
                "severity": sev,
                "text": f"{section}: cluster {es_status}",
            })

    # Databases
    dbs = data.get("databases")
    if isinstance(dbs, list):
        for db in dbs:
            if not isinstance(db, dict):
                continue
            svc = db.get("service", "?")
            for sub_key in ("ping", "processlist"):
                sub = db.get(sub_key)
                if isinstance(sub, dict) and sub.get("status") not in ("ok", None):
                    sev = "error" if sub["status"] == "critical" else sub["status"]
                    findings.append({
                        "severity": sev,
                        "text": f"{section}: {svc} {sub_key} = {sub['status']}",
                    })

    return findings


def parse_nightly_findings(content: str) -> list[dict[str, str]]:
    """Extract findings from a nightly auditor markdown report."""
    findings: list[dict[str, str]] = []

    # Extract from JSON blocks
    for section, data in extract_json_blocks(content):
        findings.extend(collect_non_ok_checks(data, section))

    # Extract from Riesgos section
    in_riesgos = False
    for line in content.splitlines():
        if line.startswith("## Riesgos"):
            in_riesgos = True
            continue
        if in_riesgos:
            if line.startswith("## "):
                break
            stripped = line.strip()
            if stripped.startswith("- "):
                text = stripped[2:].strip()
                if text and text.lower() != "sin riesgos adicionales fuera de los checks ya reflejados.":
                    findings.append({"severity": "warning", "text": text})

    return findings


EDITORIAL_DETAIL_KEYS = (
    "only_in_live_logins",
    "only_in_archive_logins",
    "changed_users",
)

PLATFORM_DETAIL_KEYS = (
    "scalar_mismatches",
    "active_plugins_only_in_live",
    "active_plugins_only_in_archive",
    "hash_mismatches",
)


def parse_drift_details(content: str) -> dict[str, dict[str, Any]]:
    """Extract editorial and platform drift detail summaries."""
    editorial: dict[str, Any] = {"count": 0, "items": [], "brief": ""}
    platform: dict[str, Any] = {"count": 0, "items": [], "brief": ""}

    current_section = ""
    for line in content.splitlines():
        if line.startswith("## Editorial drift") or line.startswith("### Editorial drift"):
            current_section = "editorial"
            continue
        elif line.startswith("## Platform drift") or line.startswith("### Platform drift"):
            current_section = "platform"
            continue
        elif line.startswith("## ") or line.startswith("### "):
            if current_section:
                current_section = ""
            continue

        if not line.startswith("- "):
            continue

        key_val = line[2:].strip()
        if ":" not in key_val:
            continue
        key, val = key_val.split(":", 1)
        key = key.strip()
        val = val.strip()

        if current_section == "editorial":
            if key == "editorial_brief":
                editorial["brief"] = val
            elif key in EDITORIAL_DETAIL_KEYS and val != "none":
                items = [v.strip() for v in val.split(",") if v.strip()]
                editorial["items"].append(f"{key}: {val}")
                editorial["count"] += len(items)

        elif current_section == "platform":
            if key == "platform_brief":
                platform["brief"] = val
            elif key in PLATFORM_DETAIL_KEYS and val != "none":
                items = [v.strip() for v in val.split(",") if v.strip()]
                platform["items"].append(f"{key}: {val}")
                platform["count"] += len(items)

    return {"editorial": editorial, "platform": platform}


def describe_container_cron(command: str) -> str:
    """Return a human-readable description for a container cron command."""
    cmd = command.lower()
    if "wp cron" in cmd or "wp-cron" in cmd:
        return "Procesamiento de tareas programadas de WordPress"
    if "snapshot" in cmd:
        return "Snapshot de contenido editorial"
    if "sync" in cmd:
        return "Sincronizacion programada"
    if "backup" in cmd:
        return "Copia de seguridad programada"
    if "cache" in cmd or "flush" in cmd:
        return "Mantenimiento de cache"
    if "eval-file" in cmd:
        return "Script PHP interno de WordPress"
    return "Tarea de WordPress"


def parse_crontab_lines(raw: str) -> list[dict[str, str]]:
    """Parse crontab -l output into structured entries."""
    entries: list[dict[str, str]] = []
    for line in raw.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        parts = stripped.split(None, 5)
        if len(parts) >= 6:
            entries.append({
                "schedule": " ".join(parts[:5]),
                "command": parts[5],
                "status": "activo",
            })
        elif len(parts) >= 1:
            # Non-standard line (env var, etc.)
            entries.append({
                "schedule": "-",
                "command": stripped,
                "status": "variable",
            })
    return entries
