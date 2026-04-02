from __future__ import annotations


def severity_from_thresholds(value: float, *, warning: float, critical: float) -> str:
    if value >= critical:
        return "critical"
    if value >= warning:
        return "warning"
    return "ok"


def severity_for_load(load_1: float, logical_cpus: int) -> str:
    cpus = max(logical_cpus, 1)
    if load_1 > cpus * 1.5:
        return "critical"
    if load_1 > cpus:
        return "warning"
    return "ok"
