"""Tests for ops.collectors.metrics — all external calls mocked."""

from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

from ops.collectors.metrics import (
    _collect_containers,
    _collect_elastic,
    _collect_host,
    _collect_host_network,
    _collect_mysql,
    _collect_nginx,
    _collect_phpfpm,
    _collect_wordpress,
    _parse_docker_size,
    _parse_net_io,
    _parse_pct,
    collect_and_store,
)
from ops.config import Settings
from ops.metrics.storage import MetricsStore
from ops.util.process import CommandResult


def _settings() -> Settings:
    return Settings(
        config_file=Path("/dev/null"),
        values={"PROJECT_ROOT": "/tmp/test-project"},
    )


def _make_store(tmpdir: str) -> MetricsStore:
    return MetricsStore(db_path=Path(tmpdir) / "test.db")


class TestParsePct(unittest.TestCase):
    def test_normal(self) -> None:
        self.assertAlmostEqual(_parse_pct("12.34%"), 12.34)

    def test_no_percent(self) -> None:
        self.assertAlmostEqual(_parse_pct("5.0"), 5.0)

    def test_invalid(self) -> None:
        self.assertIsNone(_parse_pct("--"))


class TestCollectHost(unittest.TestCase):
    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.store = _make_store(self._tmpdir.name)

    def tearDown(self) -> None:
        self.store.close()
        self._tmpdir.cleanup()

    @patch("ops.collectors.metrics.host_collector.collect")
    def test_extracts_host_metrics(self, mock_collect: MagicMock) -> None:
        mock_collect.return_value = {
            "checks": {
                "memory": {"used_pct": 65.3},
                "disk": {"used_pct": 42},
                "load_average": {"load_1": 1.2, "load_5": 1.0, "load_15": 0.8},
                "cpu": {"user_pct": 10.0, "sys_pct": 5.0, "idle_pct": 85.0},
            }
        }
        _collect_host(_settings(), self.store)
        rows = self.store.query("host", 60)
        metrics = {name: val for _, name, val in rows}
        self.assertAlmostEqual(metrics["memory_used_pct"], 65.3)
        self.assertAlmostEqual(metrics["cpu_idle_pct"], 85.0)
        self.assertEqual(len(metrics), 8)

    @patch("ops.collectors.metrics.host_collector.collect", side_effect=RuntimeError("boom"))
    def test_handles_host_failure(self, _mock: MagicMock) -> None:
        _collect_host(_settings(), self.store)
        self.assertEqual(self.store.query("host", 60), [])


class TestParseDockerSize(unittest.TestCase):
    def test_megabytes(self) -> None:
        self.assertAlmostEqual(_parse_docker_size("1.5MB"), 1.5e6)

    def test_kilobytes(self) -> None:
        self.assertAlmostEqual(_parse_docker_size("832kB"), 832e3)

    def test_gigabytes(self) -> None:
        self.assertAlmostEqual(_parse_docker_size("2.1GB"), 2.1e9)

    def test_bytes(self) -> None:
        self.assertAlmostEqual(_parse_docker_size("500B"), 500.0)

    def test_invalid(self) -> None:
        self.assertIsNone(_parse_docker_size("--"))

    def test_empty(self) -> None:
        self.assertIsNone(_parse_docker_size(""))


class TestParseNetIo(unittest.TestCase):
    def test_normal(self) -> None:
        net_in, net_out = _parse_net_io("1.5MB / 3.2MB")
        self.assertAlmostEqual(net_in, 1.5e6)
        self.assertAlmostEqual(net_out, 3.2e6)

    def test_kilobytes(self) -> None:
        net_in, net_out = _parse_net_io("832kB / 1.2GB")
        self.assertAlmostEqual(net_in, 832e3)
        self.assertAlmostEqual(net_out, 1.2e9)

    def test_invalid(self) -> None:
        self.assertEqual(_parse_net_io("--"), (None, None))


class TestCollectHostNetwork(unittest.TestCase):
    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.store = _make_store(self._tmpdir.name)

    def tearDown(self) -> None:
        self.store.close()
        self._tmpdir.cleanup()

    @patch("ops.collectors.metrics.platform.system", return_value="Linux")
    @patch("ops.collectors.metrics.Path.read_text")
    def test_linux_proc_net_dev(self, mock_read: MagicMock, _mock_sys: MagicMock) -> None:
        mock_read.return_value = (
            "Inter-|   Receive                                                |  Transmit\n"
            " face |bytes    packets errs drop fifo frame compressed multicast|"
            "bytes    packets errs drop fifo colls carrier compressed\n"
            "    lo: 1000  10    0    0    0     0          0         0  1000  10    0    0    0     0       0          0\n"
            "  eth0: 5000  50    0    0    0     0          0         0  3000  30    0    0    0     0       0          0\n"
            "  eth1: 2000  20    0    0    0     0          0         0  1000  10    0    0    0     0       0          0\n"
        )
        _collect_host_network(self.store)
        rows = self.store.query("host", 60)
        metrics = {name: val for _, name, val in rows}
        self.assertAlmostEqual(metrics["net_bytes_recv"], 7000.0)
        self.assertAlmostEqual(metrics["net_bytes_sent"], 4000.0)
        self.assertAlmostEqual(metrics["net_packets_recv"], 70.0)
        self.assertAlmostEqual(metrics["net_packets_sent"], 40.0)

    @patch("ops.collectors.metrics.platform.system", return_value="Linux")
    @patch("ops.collectors.metrics.Path.read_text", side_effect=OSError("no proc"))
    def test_linux_proc_unavailable(self, _mock_read: MagicMock, _mock_sys: MagicMock) -> None:
        _collect_host_network(self.store)
        self.assertEqual(self.store.query("host", 60), [])

    @patch("ops.collectors.metrics.platform.system", return_value="Windows")
    def test_unsupported_os(self, _mock_sys: MagicMock) -> None:
        _collect_host_network(self.store)
        self.assertEqual(self.store.query("host", 60), [])


class TestCollectContainers(unittest.TestCase):
    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.store = _make_store(self._tmpdir.name)

    def tearDown(self) -> None:
        self.store.close()
        self._tmpdir.cleanup()

    @patch("ops.collectors.metrics.run_command")
    def test_parses_docker_stats(self, mock_run: MagicMock) -> None:
        mock_run.return_value = CommandResult(
            args=[], returncode=0,
            stdout="n9-fe-live\t12.50%\t3.20%\t1.5MB / 3.2MB\nn9-db-live\t0.80%\t25.10%\t832kB / 500kB\n",
            stderr="",
        )
        _collect_containers(self.store)
        rows = self.store.query("containers", 60)
        metrics = {name: val for _, name, val in rows}
        self.assertAlmostEqual(metrics["n9-fe-live.cpu_pct"], 12.5)
        self.assertAlmostEqual(metrics["n9-db-live.mem_pct"], 25.1)
        self.assertAlmostEqual(metrics["n9-fe-live.net_in_bytes"], 1.5e6)
        self.assertAlmostEqual(metrics["n9-fe-live.net_out_bytes"], 3.2e6)
        self.assertAlmostEqual(metrics["n9-db-live.net_in_bytes"], 832e3)
        self.assertAlmostEqual(metrics["n9-db-live.net_out_bytes"], 500e3)

    @patch("ops.collectors.metrics.run_command")
    def test_handles_docker_failure(self, mock_run: MagicMock) -> None:
        mock_run.return_value = CommandResult(args=[], returncode=1, stdout="", stderr="error")
        _collect_containers(self.store)
        self.assertEqual(self.store.query("containers", 60), [])


class TestCollectElastic(unittest.TestCase):
    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.store = _make_store(self._tmpdir.name)

    def tearDown(self) -> None:
        self.store.close()
        self._tmpdir.cleanup()

    @patch("ops.collectors.metrics.compose_exec")
    def test_parses_elastic_stats(self, mock_exec: MagicMock) -> None:
        nodes_data = {
            "nodes": {"node1": {
                "jvm": {"mem": {"heap_used_percent": 45}},
                "indices": {
                    "docs": {"count": 12000},
                    "search": {"query_total": 500},
                    "indexing": {"index_total": 300},
                },
            }}
        }
        health_data = {"active_shards": 10, "relocating_shards": 0, "unassigned_shards": 1}

        def side_effect(service, cmd, cwd=None, check=True):
            cmd_str = " ".join(cmd)
            if "_nodes/stats" in cmd_str:
                return CommandResult(args=[], returncode=0, stdout=json.dumps(nodes_data), stderr="")
            return CommandResult(args=[], returncode=0, stdout=json.dumps(health_data), stderr="")

        mock_exec.side_effect = side_effect
        _collect_elastic(_settings(), self.store)
        rows = self.store.query("elastic", 60)
        metrics = {name: val for _, name, val in rows}
        self.assertAlmostEqual(metrics["heap_used_pct"], 45.0)
        self.assertAlmostEqual(metrics["docs_count"], 12000.0)
        self.assertAlmostEqual(metrics["active_shards"], 10.0)



class TestCollectMySQL(unittest.TestCase):
    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.store = _make_store(self._tmpdir.name)

    def tearDown(self) -> None:
        self.store.close()
        self._tmpdir.cleanup()

    @patch("ops.collectors.metrics.compose_exec")
    def test_parses_mysql_status(self, mock_exec: MagicMock) -> None:
        mysql_output = "Threads_connected\t5\nQuestions\t12345\nSlow_queries\t2\n"
        mock_exec.return_value = CommandResult(args=[], returncode=0, stdout=mysql_output, stderr="")
        _collect_mysql(_settings(), self.store)
        rows_live = self.store.query("mysql.db-live", 60)
        metrics = {name: val for _, name, val in rows_live}
        self.assertAlmostEqual(metrics["threads_connected"], 5.0)
        self.assertAlmostEqual(metrics["slow_queries"], 2.0)

    @patch("ops.collectors.metrics.compose_exec")
    def test_handles_mysql_failure(self, mock_exec: MagicMock) -> None:
        mock_exec.return_value = CommandResult(args=[], returncode=1, stdout="", stderr="err")
        _collect_mysql(_settings(), self.store)
        self.assertEqual(self.store.query("mysql.db-live", 60), [])


class TestCollectNginx(unittest.TestCase):
    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.store = _make_store(self._tmpdir.name)

    def tearDown(self) -> None:
        self.store.close()
        self._tmpdir.cleanup()

    @patch("ops.collectors.metrics.compose_exec")
    def test_parses_stub_status(self, mock_exec: MagicMock) -> None:
        stub = (
            "Active connections: 3\n"
            "server accepts handled requests\n"
            " 100 100 500\n"
            "Reading: 1 Writing: 2 Waiting: 0\n"
        )
        mock_exec.return_value = CommandResult(args=[], returncode=0, stdout=stub, stderr="")
        _collect_nginx(_settings(), self.store)
        rows = self.store.query("nginx", 60)
        metrics = {name: val for _, name, val in rows}
        self.assertAlmostEqual(metrics["active_connections"], 3.0)
        self.assertAlmostEqual(metrics["requests"], 500.0)
        self.assertAlmostEqual(metrics["writing"], 2.0)

    @patch("ops.collectors.metrics.compose_exec")
    def test_handles_nginx_not_available(self, mock_exec: MagicMock) -> None:
        mock_exec.return_value = CommandResult(args=[], returncode=1, stdout="", stderr="")
        _collect_nginx(_settings(), self.store)
        self.assertEqual(self.store.query("nginx", 60), [])


class TestCollectPhpfpm(unittest.TestCase):
    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.store = _make_store(self._tmpdir.name)

    def tearDown(self) -> None:
        self.store.close()
        self._tmpdir.cleanup()

    @patch("ops.collectors.metrics.compose_exec")
    def test_parses_fpm_status(self, mock_exec: MagicMock) -> None:
        """PHP-FPM status is fetched via curl on lb-nginx, not cgi-fcgi."""
        body = (
            '{"active processes":4,"idle processes":6,'
            '"listen queue":0,"max listen queue":2,'
            '"slow requests":1,"total processes":10}'
        )

        def side_effect(service, cmd, cwd=None, check=True):
            # All calls should go through lb-nginx (or its compose name)
            cmd_str = " ".join(cmd)
            if "/fpm-status-live" in cmd_str:
                return CommandResult(args=[], returncode=0, stdout=body, stderr="")
            # Other pools return failure for simplicity
            return CommandResult(args=[], returncode=7, stdout="", stderr="")

        mock_exec.side_effect = side_effect
        _collect_phpfpm(_settings(), self.store)
        rows = self.store.query("phpfpm.fe-live", 60)
        metrics = {name: val for _, name, val in rows}
        self.assertAlmostEqual(metrics["active_processes"], 4.0)
        self.assertAlmostEqual(metrics["idle_processes"], 6.0)
        self.assertAlmostEqual(metrics["slow_requests"], 1.0)

    @patch("ops.collectors.metrics.compose_exec")
    def test_phpfpm_uses_nginx_container(self, mock_exec: MagicMock) -> None:
        """Verify that compose_exec is called on lb-nginx, not on PHP containers."""
        mock_exec.return_value = CommandResult(args=[], returncode=7, stdout="", stderr="")
        _collect_phpfpm(_settings(), self.store)
        for call_args in mock_exec.call_args_list:
            service_arg = call_args[0][0]
            self.assertNotIn(service_arg, ("fe-live", "fe-archive", "be-admin"),
                             "PHP-FPM status must be fetched via lb-nginx, not directly")


class TestCollectAndStore(unittest.TestCase):
    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.store = _make_store(self._tmpdir.name)

    def tearDown(self) -> None:
        self.store.close()
        self._tmpdir.cleanup()

    @patch("ops.collectors.metrics._collect_wordpress")
    @patch("ops.collectors.metrics._collect_phpfpm")
    @patch("ops.collectors.metrics._collect_nginx")
    @patch("ops.collectors.metrics._collect_mysql")
    @patch("ops.collectors.metrics._collect_elastic")
    @patch("ops.collectors.metrics._collect_containers")
    @patch("ops.collectors.metrics._collect_host_network")
    @patch("ops.collectors.metrics._collect_host")
    def test_orchestrates_all_collectors(
        self, mock_host: MagicMock, mock_host_net: MagicMock,
        mock_containers: MagicMock,
        mock_elastic: MagicMock, mock_mysql: MagicMock,
        mock_nginx: MagicMock, mock_phpfpm: MagicMock,
        mock_wordpress: MagicMock,
    ) -> None:
        def write_host(settings, store):
            store.write_sample("host", "cpu", 10.0)

        def write_containers(store):
            store.write_sample("containers", "c1.cpu_pct", 5.0)

        mock_host.side_effect = write_host
        mock_containers.side_effect = write_containers

        result = collect_and_store(_settings(), self.store)
        self.assertEqual(result["samples_written"], 2)
        self.assertEqual(result["groups"]["host"], 1)
        self.assertEqual(result["groups"]["containers"], 1)
        self.assertIn("purged", result)
        self.assertEqual(result["retention_hours"], 24)

    @patch("ops.collectors.metrics._collect_wordpress")
    @patch("ops.collectors.metrics._collect_phpfpm")
    @patch("ops.collectors.metrics._collect_nginx")
    @patch("ops.collectors.metrics._collect_mysql")
    @patch("ops.collectors.metrics._collect_elastic")
    @patch("ops.collectors.metrics._collect_containers")
    @patch("ops.collectors.metrics._collect_host_network")
    @patch("ops.collectors.metrics._collect_host")
    def test_purge_runs(
        self, mock_host: MagicMock, mock_host_net: MagicMock,
        mock_containers: MagicMock,
        mock_elastic: MagicMock, mock_mysql: MagicMock,
        mock_nginx: MagicMock, mock_phpfpm: MagicMock,
        mock_wordpress: MagicMock,
    ) -> None:
        import time
        # Insert an old sample directly
        self.store.write_sample("old", "m", 1.0, ts=time.time() - 100000)
        result = collect_and_store(_settings(), self.store)
        self.assertEqual(result["purged"], 1)


class TestCollectWordPress(unittest.TestCase):
    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.store = _make_store(self._tmpdir.name)

    def tearDown(self) -> None:
        self.store.close()
        self._tmpdir.cleanup()

    @patch("ops.collectors.metrics.compose_exec")
    def test_live_context_all_metrics(self, mock_exec: MagicMock) -> None:
        """Live context returns all metrics including cron/content/updates."""
        live_data = {
            "metrics": {
                "cron_events_total": 42,
                "cron_events_overdue": 3,
                "cron_events_overdue_max_age": 120,
                "db_size_mb": 85.5,
                "autoload_size_kb": 512.3,
                "autoload_count": 200,
                "transients_count": 50,
                "posts_published": 1500,
                "posts_draft": 10,
                "pages_published": 25,
                "plugins_update_available": 2,
                "themes_update_available": 1,
                "php_error_count": 5,
            }
        }
        archive_data = {
            "metrics": {
                "db_size_mb": 120.0,
                "autoload_size_kb": 256.0,
                "autoload_count": 100,
                "transients_count": 20,
                "php_error_count": 0,
            }
        }

        def side_effect(service, cmd, cwd=None, check=True, exec_args=None):
            cmd_str = " ".join(cmd)
            if "N9_SITE_CONTEXT=live" in cmd_str:
                return CommandResult(args=[], returncode=0, stdout=json.dumps(live_data), stderr="")
            return CommandResult(args=[], returncode=0, stdout=json.dumps(archive_data), stderr="")

        mock_exec.side_effect = side_effect
        _collect_wordpress(_settings(), self.store)

        live_rows = self.store.query("wordpress.fe-live", 60)
        live_metrics = {name: val for _, name, val in live_rows}
        self.assertAlmostEqual(live_metrics["cron_events_total"], 42)
        self.assertAlmostEqual(live_metrics["db_size_mb"], 85.5)
        self.assertAlmostEqual(live_metrics["posts_published"], 1500)
        self.assertAlmostEqual(live_metrics["plugins_update_available"], 2)
        self.assertAlmostEqual(live_metrics["php_error_count"], 5)
        self.assertEqual(len(live_metrics), 13)

        archive_rows = self.store.query("wordpress.fe-archive", 60)
        archive_metrics = {name: val for _, name, val in archive_rows}
        self.assertAlmostEqual(archive_metrics["db_size_mb"], 120.0)
        self.assertAlmostEqual(archive_metrics["php_error_count"], 0)
        self.assertEqual(len(archive_metrics), 5)

    @patch("ops.collectors.metrics.compose_exec")
    def test_archive_skips_live_only_metrics(self, mock_exec: MagicMock) -> None:
        """Archive context only has db and error metrics (no cron/content/updates)."""
        archive_data = {
            "metrics": {
                "db_size_mb": 120.0,
                "autoload_size_kb": 256.0,
                "autoload_count": 100,
                "transients_count": 20,
                "php_error_count": 0,
            }
        }

        def side_effect(service, cmd, cwd=None, check=True, exec_args=None):
            cmd_str = " ".join(cmd)
            if "N9_SITE_CONTEXT=live" in cmd_str:
                return CommandResult(args=[], returncode=1, stdout="", stderr="error")
            return CommandResult(args=[], returncode=0, stdout=json.dumps(archive_data), stderr="")

        mock_exec.side_effect = side_effect
        _collect_wordpress(_settings(), self.store)

        live_rows = self.store.query("wordpress.fe-live", 60)
        self.assertEqual(len(live_rows), 0)

        archive_rows = self.store.query("wordpress.fe-archive", 60)
        archive_metrics = {name: val for _, name, val in archive_rows}
        self.assertNotIn("cron_events_total", archive_metrics)
        self.assertNotIn("posts_published", archive_metrics)
        self.assertNotIn("plugins_update_available", archive_metrics)
        self.assertAlmostEqual(archive_metrics["db_size_mb"], 120.0)

    @patch("ops.collectors.metrics.compose_exec")
    def test_handles_exec_failure(self, mock_exec: MagicMock) -> None:
        """Gracefully handles compose_exec exceptions."""
        mock_exec.side_effect = Exception("container not running")
        _collect_wordpress(_settings(), self.store)
        live_rows = self.store.query("wordpress.fe-live", 60)
        archive_rows = self.store.query("wordpress.fe-archive", 60)
        self.assertEqual(len(live_rows), 0)
        self.assertEqual(len(archive_rows), 0)

    @patch("ops.collectors.metrics.compose_exec")
    def test_handles_invalid_json(self, mock_exec: MagicMock) -> None:
        """Gracefully handles invalid JSON output."""
        mock_exec.return_value = CommandResult(args=[], returncode=0, stdout="not json", stderr="")
        _collect_wordpress(_settings(), self.store)
        live_rows = self.store.query("wordpress.fe-live", 60)
        archive_rows = self.store.query("wordpress.fe-archive", 60)
        self.assertEqual(len(live_rows), 0)
        self.assertEqual(len(archive_rows), 0)


if __name__ == "__main__":
    unittest.main()