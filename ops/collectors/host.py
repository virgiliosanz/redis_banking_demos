from __future__ import annotations

import os
import platform
import re
import shutil
from pathlib import Path

from ..config import Settings
from ..util.process import run_command
from ..util.thresholds import severity_for_load, severity_from_thresholds
from ..util.time import utc_timestamp


def _thresholds(warning: int | float, critical: int | float) -> dict[str, int | float]:
    return {"warning": warning, "critical": critical}


def _parse_darwin_vm_stat(output: str) -> tuple[int, int]:
    page_size_match = re.search(r"page size of\s+(\d+)\s+bytes", output)
    page_size = int(page_size_match.group(1)) if page_size_match else 16384

    def page_count(label: str) -> int:
        match = re.search(rf"{re.escape(label)}:\s+([0-9.]+)", output)
        if not match:
            return 0
        return int(match.group(1).replace(".", ""))

    free_pages = page_count("Pages free")
    speculative_pages = page_count("Pages speculative")
    return page_size, free_pages + speculative_pages


def _parse_darwin_memory_pressure(output: str) -> float | None:
    match = re.search(r"System-wide memory free percentage:\s+([0-9.]+)%", output)
    if not match:
        return None
    free_pct = float(match.group(1))
    return max(0.0, min(100.0, round(100.0 - free_pct, 2)))


def _parse_linux_meminfo() -> tuple[int, int]:
    meminfo = Path("/proc/meminfo").read_text(encoding="utf-8")
    total_match = re.search(r"^MemTotal:\s+(\d+)\s+kB$", meminfo, re.MULTILINE)
    available_match = re.search(r"^MemAvailable:\s+(\d+)\s+kB$", meminfo, re.MULTILINE)
    total_kb = int(total_match.group(1)) if total_match else 0
    available_kb = int(available_match.group(1)) if available_match else 0
    return total_kb * 1024, (total_kb - available_kb) * 1024


def _parse_darwin_iostat(output: str) -> tuple[float, float, float, float, float, float]:
    line = output.strip().splitlines()[-1]
    fields = line.split()
    return (
        float(fields[-6]),
        float(fields[-5]),
        float(fields[-4]),
        float(fields[-3]),
        float(fields[-2]),
        float(fields[-1]),
    )


def _parse_linux_iostat(output: str) -> tuple[float, float, float, float]:
    lines = [line for line in output.strip().splitlines() if line.strip()]
    line = lines[-1]
    fields = line.split()
    return float(fields[0]), float(fields[2]), float(fields[3]), float(fields[5])


def collect(settings: Settings) -> dict[str, object]:
    project_path = settings.project_root

    host_os = platform.system()
    logical_cpus = os.cpu_count() or 1
    docker_status = "ok" if run_command(["docker", "info"], check=False).returncode == 0 else "critical"

    mem_total_bytes = 0
    mem_used_bytes = 0
    mem_used_pct = 0.0
    cpu_user_pct = 0.0
    cpu_sys_pct = 0.0
    cpu_idle_pct = 0.0
    load_1 = 0.0
    load_5 = 0.0
    load_15 = 0.0
    iowait_pct: float | None = None
    iowait_status = "not_supported"

    if host_os == "Darwin":
        mem_total_bytes = int(run_command(["sysctl", "-n", "hw.memsize"]).stdout.strip())
        pressure_result = run_command(["memory_pressure"], check=False)
        mem_used_pct = _parse_darwin_memory_pressure(pressure_result.stdout) if pressure_result.returncode == 0 else None
        if mem_used_pct is None:
            vm_output = run_command(["vm_stat"]).stdout
            page_size, unused_pages = _parse_darwin_vm_stat(vm_output)
            mem_used_bytes = max(mem_total_bytes - (unused_pages * page_size), 0)
            mem_used_pct = round((mem_used_bytes / mem_total_bytes) * 100, 2) if mem_total_bytes else 0.0
        else:
            mem_used_bytes = int(mem_total_bytes * (mem_used_pct / 100.0))

        iostat_output = run_command(["iostat", "-w", "1", "-c", "2"]).stdout
        cpu_user_pct, cpu_sys_pct, cpu_idle_pct, load_1, load_5, load_15 = _parse_darwin_iostat(iostat_output)
    else:
        mem_total_bytes, mem_used_bytes = _parse_linux_meminfo()
        mem_used_pct = round((mem_used_bytes / mem_total_bytes) * 100, 2) if mem_total_bytes else 0.0

        iostat_output = run_command(["iostat", "-c", "1", "2"]).stdout
        cpu_user_pct, cpu_sys_pct, iowait_pct, cpu_idle_pct = _parse_linux_iostat(iostat_output)
        uptime_output = run_command(["uptime"]).stdout
        load_match = re.search(r"load averages?:\s*([0-9.]+)[, ]+\s*([0-9.]+)[, ]+\s*([0-9.]+)", uptime_output)
        if load_match:
            load_1, load_5, load_15 = map(float, load_match.groups())
        iowait_status = severity_from_thresholds(
            iowait_pct or 0.0,
            warning=settings.get_int("HOST_IOWAIT_WARNING_PCT", 10),
            critical=settings.get_int("HOST_IOWAIT_CRITICAL_PCT", 20),
        )

    disk_usage = shutil.disk_usage(project_path.resolve())
    disk_used_pct = round(((disk_usage.total - disk_usage.free) / disk_usage.total) * 100) if disk_usage.total else 0

    memory_warning = settings.get_int("HOST_MEMORY_WARNING_PCT", 85)
    memory_critical = settings.get_int("HOST_MEMORY_CRITICAL_PCT", 92)
    disk_warning = settings.get_int("HOST_DISK_WARNING_PCT", 80)
    disk_critical = settings.get_int("HOST_DISK_CRITICAL_PCT", 90)
    iowait_warning = settings.get_int("HOST_IOWAIT_WARNING_PCT", 10)
    iowait_critical = settings.get_int("HOST_IOWAIT_CRITICAL_PCT", 20)

    memory_status = severity_from_thresholds(mem_used_pct, warning=memory_warning, critical=memory_critical)
    disk_status = severity_from_thresholds(float(disk_used_pct), warning=disk_warning, critical=disk_critical)
    load_status = severity_for_load(load_1, logical_cpus)

    return {
        "generated_at": utc_timestamp(),
        "host": {
            "os": host_os,
            "logical_cpus": logical_cpus,
            "project_path": str(project_path),
        },
        "checks": {
            "docker_daemon": {
                "status": docker_status,
                "source": "docker info",
            },
            "memory": {
                "used_bytes": mem_used_bytes,
                "total_bytes": mem_total_bytes,
                "used_pct": mem_used_pct,
                "status": memory_status,
                "thresholds": _thresholds(memory_warning, memory_critical),
                "source": "memory_pressure" if host_os == "Darwin" else "/proc/meminfo",
            },
            "disk": {
                "used_pct": disk_used_pct,
                "status": disk_status,
                "thresholds": _thresholds(disk_warning, disk_critical),
                "source": str(project_path.resolve()),
            },
            "load_average": {
                "load_1": load_1,
                "load_5": load_5,
                "load_15": load_15,
                "status": load_status,
                "source": "iostat+uptime" if host_os != "Darwin" else "iostat",
            },
            "cpu": {
                "user_pct": cpu_user_pct,
                "sys_pct": cpu_sys_pct,
                "idle_pct": cpu_idle_pct,
            },
            "iowait": {
                "pct": iowait_pct,
                "status": iowait_status,
                "thresholds": _thresholds(iowait_warning, iowait_critical),
                "source": "iostat" if host_os != "Darwin" else "not_supported",
            },
        },
    }
