"""Metrics collector — extracts numeric samples from existing collectors
and additional sources, then writes them to the MetricsStore (SQLite)."""

from __future__ import annotations

import json
import logging
import platform
import re
from pathlib import Path
from typing import Any

from ..config import Settings
from ..metrics.storage import MetricsStore
from ..services import compose_service_name
from ..util.docker import compose_exec
from ..util.process import run_command
from . import host as host_collector

logger = logging.getLogger(__name__)

# Default retention in hours (24 h)
_DEFAULT_RETENTION_HOURS = 24


# ------------------------------------------------------------------
# Host metrics
# ------------------------------------------------------------------

def _collect_host(settings: Settings, store: MetricsStore) -> None:
    """Extract numeric metrics from the host collector."""
    try:
        data = host_collector.collect(settings)
    except Exception:
        logger.warning("host collector failed, skipping host metrics", exc_info=True)
        return

    checks = data.get("checks", {})
    mem = checks.get("memory", {})
    disk = checks.get("disk", {})
    load = checks.get("load_average", {})
    cpu = checks.get("cpu", {})

    _safe_write(store, "host", "memory_used_pct", mem.get("used_pct"))
    _safe_write(store, "host", "disk_used_pct", disk.get("used_pct"))
    _safe_write(store, "host", "load_1", load.get("load_1"))
    _safe_write(store, "host", "load_5", load.get("load_5"))
    _safe_write(store, "host", "load_15", load.get("load_15"))
    _safe_write(store, "host", "cpu_user_pct", cpu.get("user_pct"))
    _safe_write(store, "host", "cpu_sys_pct", cpu.get("sys_pct"))
    _safe_write(store, "host", "cpu_idle_pct", cpu.get("idle_pct"))


# ------------------------------------------------------------------
# Host network metrics
# ------------------------------------------------------------------

def _collect_host_network(store: MetricsStore) -> None:
    """Collect cumulative network counters for the host.

    On Linux reads ``/proc/net/dev``; on macOS uses ``netstat -ib``.
    Stores raw cumulative counters — the dashboard computes rates.
    """
    host_os = platform.system()
    try:
        if host_os == "Linux":
            _collect_host_network_linux(store)
        elif host_os == "Darwin":
            _collect_host_network_darwin(store)
        else:
            logger.debug("host network metrics not supported on %s", host_os)
    except Exception:
        logger.warning("host network collector failed", exc_info=True)


def _collect_host_network_linux(store: MetricsStore) -> None:
    """Parse ``/proc/net/dev`` for aggregate network counters."""
    try:
        text = Path("/proc/net/dev").read_text(encoding="utf-8")
    except OSError:
        logger.warning("/proc/net/dev not available")
        return

    total_recv_bytes = 0
    total_sent_bytes = 0
    total_recv_packets = 0
    total_sent_packets = 0

    for line in text.strip().splitlines()[2:]:  # skip header lines
        parts = line.split()
        if len(parts) < 11:
            continue
        iface = parts[0].rstrip(":")
        if iface == "lo":
            continue
        total_recv_bytes += int(parts[1])
        total_recv_packets += int(parts[2])
        total_sent_bytes += int(parts[9])
        total_sent_packets += int(parts[10])

    store.write_sample("host", "net_bytes_recv", float(total_recv_bytes))
    store.write_sample("host", "net_bytes_sent", float(total_sent_bytes))
    store.write_sample("host", "net_packets_recv", float(total_recv_packets))
    store.write_sample("host", "net_packets_sent", float(total_sent_packets))


def _collect_host_network_darwin(store: MetricsStore) -> None:
    """Parse ``netstat -ib`` for aggregate network counters on macOS."""
    result = run_command(["netstat", "-ib"], check=False)
    if result.returncode != 0:
        logger.warning("netstat -ib failed (rc=%d)", result.returncode)
        return

    total_recv_bytes = 0
    total_sent_bytes = 0
    total_recv_packets = 0
    total_sent_packets = 0

    for line in result.stdout.strip().splitlines()[1:]:  # skip header
        parts = line.split()
        if len(parts) < 7:
            continue
        iface = parts[0]
        if iface == "lo0" or iface.startswith("lo"):
            continue
        # netstat -ib columns: Name Mtu Network Address Ipkts Ibytes Opkts Obytes
        # Some lines have fewer columns (link-level entries)
        try:
            recv_packets = int(parts[-4])
            recv_bytes = int(parts[-3])
            sent_packets = int(parts[-2])
            sent_bytes = int(parts[-1])
            total_recv_bytes += recv_bytes
            total_sent_bytes += sent_bytes
            total_recv_packets += recv_packets
            total_sent_packets += sent_packets
        except (ValueError, IndexError):
            continue

    store.write_sample("host", "net_bytes_recv", float(total_recv_bytes))
    store.write_sample("host", "net_bytes_sent", float(total_sent_bytes))
    store.write_sample("host", "net_packets_recv", float(total_recv_packets))
    store.write_sample("host", "net_packets_sent", float(total_sent_packets))


# ------------------------------------------------------------------
# Container metrics (docker stats)
# ------------------------------------------------------------------

_SIZE_UNITS: dict[str, float] = {
    "B": 1,
    "KB": 1e3, "KIB": 1024,
    "MB": 1e6, "MIB": 1024**2,
    "GB": 1e9, "GIB": 1024**3,
    "TB": 1e12, "TIB": 1024**4,
}


def _parse_docker_size(raw: str) -> float | None:
    """Parse a docker size string like ``1.5MB`` or ``832kB`` into bytes."""
    raw = raw.strip()
    m = re.match(r"^([0-9.]+)\s*([A-Za-z]+)$", raw)
    if not m:
        return None
    number = float(m.group(1))
    unit = m.group(2).upper()
    factor = _SIZE_UNITS.get(unit)
    if factor is None:
        return None
    return number * factor


def _parse_net_io(net_io_raw: str) -> tuple[float | None, float | None]:
    """Parse docker stats NetIO format ``'1.5MB / 3.2MB'`` into (in_bytes, out_bytes)."""
    parts = net_io_raw.split("/")
    if len(parts) != 2:
        return None, None
    return _parse_docker_size(parts[0]), _parse_docker_size(parts[1])


def _collect_containers(store: MetricsStore) -> None:
    """CPU %, mem % and network I/O per container via ``docker stats --no-stream``."""
    try:
        result = run_command(
            ["docker", "stats", "--no-stream", "--format",
             "{{.Name}}\t{{.CPUPerc}}\t{{.MemPerc}}\t{{.NetIO}}"],
            check=False,
        )
        if result.returncode != 0:
            logger.warning("docker stats failed (rc=%d)", result.returncode)
            return
    except Exception:
        logger.warning("docker stats unavailable", exc_info=True)
        return

    for line in result.stdout.strip().splitlines():
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        name = parts[0]
        cpu_val = _parse_pct(parts[1])
        mem_val = _parse_pct(parts[2])
        if cpu_val is not None:
            store.write_sample("containers", f"{name}.cpu_pct", cpu_val)
        if mem_val is not None:
            store.write_sample("containers", f"{name}.mem_pct", mem_val)
        if len(parts) >= 4:
            net_in, net_out = _parse_net_io(parts[3])
            if net_in is not None:
                store.write_sample("containers", f"{name}.net_in_bytes", net_in)
            if net_out is not None:
                store.write_sample("containers", f"{name}.net_out_bytes", net_out)


# ------------------------------------------------------------------
# Elastic metrics
# ------------------------------------------------------------------

def _collect_elastic(settings: Settings, store: MetricsStore) -> None:
    """Heap %, doc count, search/index rate from Elasticsearch."""
    cwd = settings.project_root.resolve()
    elastic_service = compose_service_name("elastic")

    try:
        nodes_raw = compose_exec(
            elastic_service,
            ["sh", "-lc", "curl -fsS http://127.0.0.1:9200/_nodes/stats"],
            cwd=cwd, check=False,
        )
        health_raw = compose_exec(
            elastic_service,
            ["sh", "-lc", "curl -fsS http://127.0.0.1:9200/_cluster/health"],
            cwd=cwd, check=False,
        )
    except Exception:
        logger.warning("elastic stats unavailable", exc_info=True)
        return

    # Cluster health
    if health_raw.returncode == 0 and health_raw.stdout.strip():
        try:
            health = json.loads(health_raw.stdout)
            _safe_write(store, "elastic", "active_shards", health.get("active_shards"))
            _safe_write(store, "elastic", "relocating_shards", health.get("relocating_shards"))
            _safe_write(store, "elastic", "unassigned_shards", health.get("unassigned_shards"))
        except (json.JSONDecodeError, TypeError):
            logger.warning("elastic cluster/health parse error")

    # Node stats — aggregate across all nodes
    if nodes_raw.returncode == 0 and nodes_raw.stdout.strip():
        try:
            nodes_data = json.loads(nodes_raw.stdout)
            nodes = nodes_data.get("nodes", {})
            for _nid, ninfo in nodes.items():
                jvm = ninfo.get("jvm", {}).get("mem", {})
                heap_pct = jvm.get("heap_used_percent")
                _safe_write(store, "elastic", "heap_used_pct", heap_pct)

                indices = ninfo.get("indices", {})
                docs_count = indices.get("docs", {}).get("count")
                _safe_write(store, "elastic", "docs_count", docs_count)

                search_total = indices.get("search", {}).get("query_total")
                _safe_write(store, "elastic", "search_query_total", search_total)

                indexing_total = indices.get("indexing", {}).get("index_total")
                _safe_write(store, "elastic", "indexing_total", indexing_total)
                break  # single-node cluster; take first
        except (json.JSONDecodeError, TypeError):
            logger.warning("elastic nodes/stats parse error")


# ------------------------------------------------------------------
# MySQL metrics
# ------------------------------------------------------------------

def _collect_mysql(settings: Settings, store: MetricsStore) -> None:
    """Threads connected, questions, slow queries from SHOW GLOBAL STATUS."""
    services = [
        ("db-live", settings.get("DB_LIVE_ROOT_SECRET_PATH", "/run/secrets/db_live_mysql_root_password")),
        ("db-archive", settings.get("DB_ARCHIVE_ROOT_SECRET_PATH", "/run/secrets/db_archive_mysql_root_password")),
    ]
    cwd = settings.project_root.resolve()

    for service, secret_path in services:
        group = f"mysql.{service}"
        try:
            result = compose_exec(
                service,
                ["sh", "-lc",
                 f'MYSQL_PWD="$(cat {secret_path})" '
                 'mysql -h 127.0.0.1 -uroot --batch --raw --skip-column-names '
                 '-e "SHOW GLOBAL STATUS WHERE Variable_name IN '
                 "('Threads_connected','Questions','Slow_queries','Innodb_buffer_pool_pages_total',"
                 "'Innodb_buffer_pool_pages_free')\""],
                cwd=cwd, check=False,
            )
        except Exception:
            logger.warning("mysql %s stats unavailable", service, exc_info=True)
            continue

        if result.returncode != 0:
            logger.warning("mysql %s SHOW GLOBAL STATUS failed (rc=%d)", service, result.returncode)
            continue

        for line in result.stdout.strip().splitlines():
            parts = line.split("\t")
            if len(parts) != 2:
                continue
            var_name, var_value = parts
            try:
                store.write_sample(group, var_name.lower(), float(var_value))
            except (ValueError, TypeError):
                pass


# ------------------------------------------------------------------
# Nginx metrics (stub_status)
# ------------------------------------------------------------------

def _collect_nginx(settings: Settings, store: MetricsStore) -> None:
    """Parse nginx stub_status for active connections and request counts.

    Requires ``stub_status`` enabled in the nginx config (``/nginx_status``
    location).  After any config change run ``nginx -s reload`` inside the
    lb-nginx container so the endpoint becomes available.
    """
    cwd = settings.project_root.resolve()
    nginx_service = compose_service_name("lb-nginx")

    try:
        result = compose_exec(
            nginx_service,
            ["sh", "-lc", "curl -fsS http://127.0.0.1:80/nginx_status"],
            cwd=cwd, check=False,
        )
    except Exception:
        logger.warning("nginx stub_status unavailable", exc_info=True)
        return

    if result.returncode != 0:
        logger.debug("nginx stub_status not available (rc=%d)", result.returncode)
        return

    text = result.stdout
    # Active connections: 3
    m = re.search(r"Active connections:\s*(\d+)", text)
    if m:
        store.write_sample("nginx", "active_connections", float(m.group(1)))

    # server accepts handled requests
    #  12 12 45
    lines = text.strip().splitlines()
    for i, line in enumerate(lines):
        if "server" in line and "accepts" in line and i + 1 < len(lines):
            nums = lines[i + 1].split()
            if len(nums) >= 3:
                store.write_sample("nginx", "accepts", float(nums[0]))
                store.write_sample("nginx", "handled", float(nums[1]))
                store.write_sample("nginx", "requests", float(nums[2]))
            break

    # Reading: 0 Writing: 1 Waiting: 2
    m2 = re.search(r"Reading:\s*(\d+)\s+Writing:\s*(\d+)\s+Waiting:\s*(\d+)", text)
    if m2:
        store.write_sample("nginx", "reading", float(m2.group(1)))
        store.write_sample("nginx", "writing", float(m2.group(2)))
        store.write_sample("nginx", "waiting", float(m2.group(3)))


# ------------------------------------------------------------------
# PHP-FPM metrics
# ------------------------------------------------------------------

# Mapping: service key → nginx location path for its FPM status endpoint.
_PHPFPM_SERVICES: dict[str, str] = {
    "fe-live": "/fpm-status-live",
    "fe-archive": "/fpm-status-archive",
    "be-admin": "/fpm-status-admin",
}


def _collect_phpfpm(settings: Settings, store: MetricsStore) -> None:
    """Read PHP-FPM status via ``curl`` on the lb-nginx container.

    Each pool exposes a ``/fpm-status-*?json`` endpoint through nginx,
    so there is no need for ``cgi-fcgi`` inside the PHP containers.
    """
    cwd = settings.project_root.resolve()
    nginx_service = compose_service_name("lb-nginx")

    for service, status_path in _PHPFPM_SERVICES.items():
        group = f"phpfpm.{service}"
        url = f"http://127.0.0.1{status_path}?json"
        try:
            result = compose_exec(
                nginx_service,
                ["sh", "-lc", f"curl -fsS {url}"],
                cwd=cwd, check=False,
            )
        except Exception:
            logger.warning("phpfpm %s status unavailable", service, exc_info=True)
            continue

        if result.returncode != 0:
            logger.debug("phpfpm %s status not available (rc=%d)", service, result.returncode)
            continue

        # Response is direct JSON from nginx (no CGI headers)
        body = result.stdout.strip()
        if not body:
            continue
        try:
            data = json.loads(body)
        except (json.JSONDecodeError, TypeError):
            logger.warning("phpfpm %s status parse error", service)
            continue

        _safe_write(store, group, "active_processes", data.get("active processes"))
        _safe_write(store, group, "idle_processes", data.get("idle processes"))
        _safe_write(store, group, "listen_queue", data.get("listen queue"))
        _safe_write(store, group, "max_listen_queue", data.get("max listen queue"))
        _safe_write(store, group, "slow_requests", data.get("slow requests"))
        _safe_write(store, group, "total_processes", data.get("total processes"))


# ------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------

def _safe_write(store: MetricsStore, group: str, metric: str, value: Any) -> None:
    """Write a sample only if value is numeric (not None)."""
    if value is None:
        return
    try:
        store.write_sample(group, metric, float(value))
    except (ValueError, TypeError):
        pass


def _parse_pct(raw: str) -> float | None:
    """Parse '12.34%' → 12.34."""
    raw = raw.strip().rstrip("%")
    try:
        return float(raw)
    except ValueError:
        return None


# ------------------------------------------------------------------
# Public API
# ------------------------------------------------------------------

def collect_and_store(settings: Settings, store: MetricsStore) -> dict[str, Any]:
    """Run all sub-collectors, write samples, purge old data.

    Returns a summary dict with counts per group and total.
    """
    retention_hours = settings.get_int("METRICS_RETENTION_HOURS", _DEFAULT_RETENTION_HOURS)

    counts: dict[str, int] = {}

    class _CountingStore:
        """Proxy that counts writes per group."""

        def write_sample(self, group: str, metric: str, value: float, ts: float | None = None) -> None:
            store.write_sample(group, metric, value, ts=ts)
            counts[group] = counts.get(group, 0) + 1

    proxy = _CountingStore()

    _collect_host(settings, proxy)  # type: ignore[arg-type]
    _collect_host_network(proxy)  # type: ignore[arg-type]
    _collect_containers(proxy)  # type: ignore[arg-type]
    _collect_elastic(settings, proxy)  # type: ignore[arg-type]
    _collect_mysql(settings, proxy)  # type: ignore[arg-type]
    _collect_nginx(settings, proxy)  # type: ignore[arg-type]
    _collect_phpfpm(settings, proxy)  # type: ignore[arg-type]

    purged = store.purge(max_age_hours=retention_hours)

    return {
        "samples_written": sum(counts.values()),
        "groups": counts,
        "purged": purged,
        "retention_hours": retention_hours,
    }
