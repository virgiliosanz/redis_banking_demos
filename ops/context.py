from __future__ import annotations

from .collectors import app as app_collector
from .collectors import cron as cron_collector
from .collectors import elastic as elastic_collector
from .collectors import host as host_collector
from .collectors import mysql as mysql_collector
from .collectors import runtime as runtime_collector
from .config import Settings
from .runtime.drift import DriftStatus, build_drift_report
from .util.time import utc_timestamp


def collect_operational_context(settings: Settings) -> dict[str, object]:
    return {
        "generated_at": utc_timestamp(),
        "host": host_collector.collect(settings),
        "runtime": runtime_collector.collect(settings),
        "app": app_collector.collect(settings),
        "mysql": mysql_collector.collect(settings),
        "elastic": elastic_collector.collect(settings),
        "cron": cron_collector.collect(settings),
    }


def load_drift_status(settings: Settings) -> DriftStatus:
    return build_drift_report(settings)


def collect_statuses(node: object) -> list[str]:
    statuses: list[str] = []

    def walk(current: object) -> None:
        if isinstance(current, dict):
            status = current.get("status")
            if isinstance(status, str):
                statuses.append(status)
            for value in current.values():
                walk(value)
        elif isinstance(current, list):
            for value in current:
                walk(value)

    walk(node)
    return statuses
