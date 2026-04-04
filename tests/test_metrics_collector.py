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
    _collect_mysql,
    _collect_nginx,
    _collect_phpfpm,
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
            stdout="n9-fe-live\t12.50%\t3.20%\nn9-db-live\t0.80%\t25.10%\n",
            stderr="",
        )
        _collect_containers(self.store)
        rows = self.store.query("container", 60)
        metrics = {name: val for _, name, val in rows}
        self.assertAlmostEqual(metrics["n9-fe-live.cpu_pct"], 12.5)
        self.assertAlmostEqual(metrics["n9-db-live.mem_pct"], 25.1)

    @patch("ops.collectors.metrics.run_command")
    def test_handles_docker_failure(self, mock_run: MagicMock) -> None:
        mock_run.return_value = CommandResult(args=[], returncode=1, stdout="", stderr="error")
        _collect_containers(self.store)
        self.assertEqual(self.store.query("container", 60), [])


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
        body = (
            'Content-Type: application/json\r\n\r\n'
            '{"active processes":4,"idle processes":6,'
            '"listen queue":0,"max listen queue":2,'
            '"slow requests":1,"total processes":10}'
        )
        mock_exec.return_value = CommandResult(args=[], returncode=0, stdout=body, stderr="")
        _collect_phpfpm(_settings(), self.store)
        rows = self.store.query("phpfpm.fe-live", 60)
        metrics = {name: val for _, name, val in rows}
        self.assertAlmostEqual(metrics["active_processes"], 4.0)
        self.assertAlmostEqual(metrics["idle_processes"], 6.0)
        self.assertAlmostEqual(metrics["slow_requests"], 1.0)


class TestCollectAndStore(unittest.TestCase):
    def setUp(self) -> None:
        self._tmpdir = tempfile.TemporaryDirectory()
        self.store = _make_store(self._tmpdir.name)

    def tearDown(self) -> None:
        self.store.close()
        self._tmpdir.cleanup()

    @patch("ops.collectors.metrics._collect_phpfpm")
    @patch("ops.collectors.metrics._collect_nginx")
    @patch("ops.collectors.metrics._collect_mysql")
    @patch("ops.collectors.metrics._collect_elastic")
    @patch("ops.collectors.metrics._collect_containers")
    @patch("ops.collectors.metrics._collect_host")
    def test_orchestrates_all_collectors(
        self, mock_host: MagicMock, mock_containers: MagicMock,
        mock_elastic: MagicMock, mock_mysql: MagicMock,
        mock_nginx: MagicMock, mock_phpfpm: MagicMock,
    ) -> None:
        def write_host(settings, store):
            store.write_sample("host", "cpu", 10.0)

        def write_containers(store):
            store.write_sample("container", "c1.cpu_pct", 5.0)

        mock_host.side_effect = write_host
        mock_containers.side_effect = write_containers

        result = collect_and_store(_settings(), self.store)
        self.assertEqual(result["samples_written"], 2)
        self.assertEqual(result["groups"]["host"], 1)
        self.assertEqual(result["groups"]["container"], 1)
        self.assertIn("purged", result)
        self.assertEqual(result["retention_hours"], 24)

    @patch("ops.collectors.metrics._collect_phpfpm")
    @patch("ops.collectors.metrics._collect_nginx")
    @patch("ops.collectors.metrics._collect_mysql")
    @patch("ops.collectors.metrics._collect_elastic")
    @patch("ops.collectors.metrics._collect_containers")
    @patch("ops.collectors.metrics._collect_host")
    def test_purge_runs(
        self, mock_host: MagicMock, mock_containers: MagicMock,
        mock_elastic: MagicMock, mock_mysql: MagicMock,
        mock_nginx: MagicMock, mock_phpfpm: MagicMock,
    ) -> None:
        import time
        # Insert an old sample directly
        self.store.write_sample("old", "m", 1.0, ts=time.time() - 100000)
        result = collect_and_store(_settings(), self.store)
        self.assertEqual(result["purged"], 1)


if __name__ == "__main__":
    unittest.main()