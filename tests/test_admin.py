"""Unit tests for the admin panel package."""

from __future__ import annotations

import json
import subprocess
import unittest
from pathlib import Path
from unittest import mock

from admin.runner import CommandResult, run_cli
from admin.config import ADMIN_PORT, ADMIN_HOST, DEBUG
from admin.containers import get_compose_root
from admin.app import create_app
import admin.health_bp


# ---------------------------------------------------------------------------
# runner.py tests
# ---------------------------------------------------------------------------

class TestCommandResult(unittest.TestCase):
    def test_success_when_returncode_zero(self) -> None:
        r = CommandResult(command=["echo"], returncode=0, stdout="ok", stderr="")
        self.assertTrue(r.success)

    def test_failure_when_returncode_nonzero(self) -> None:
        r = CommandResult(command=["false"], returncode=1, stdout="", stderr="err")
        self.assertFalse(r.success)

    def test_output_combines_stdout_and_stderr(self) -> None:
        r = CommandResult(command=["x"], returncode=0, stdout="out", stderr="err")
        self.assertIn("out", r.output)
        self.assertIn("err", r.output)

    def test_output_only_stdout(self) -> None:
        r = CommandResult(command=["x"], returncode=0, stdout="out", stderr="")
        self.assertEqual(r.output, "out")


class TestRunCli(unittest.TestCase):
    def test_simple_echo(self) -> None:
        result = run_cli(["echo", "hello"])
        self.assertTrue(result.success)
        self.assertEqual(result.stdout.strip(), "hello")

    def test_timeout_handling(self) -> None:
        result = run_cli(["sleep", "10"], timeout=1)
        self.assertFalse(result.success)
        self.assertIn("timed out", result.stderr)

    def test_file_not_found(self) -> None:
        result = run_cli(["__nonexistent_binary_xyz__"])
        self.assertFalse(result.success)
        self.assertIn("Command not found", result.stderr)

    def test_cwd_parameter(self) -> None:
        result = run_cli(["pwd"], cwd="/tmp")
        self.assertTrue(result.success)
        # /tmp may resolve to /private/tmp on macOS
        self.assertIn("tmp", result.stdout.strip())

    def test_cwd_none_uses_default(self) -> None:
        result = run_cli(["pwd"])
        self.assertTrue(result.success)
        self.assertTrue(len(result.stdout.strip()) > 0)


# ---------------------------------------------------------------------------
# config.py tests
# ---------------------------------------------------------------------------

class TestConfig(unittest.TestCase):
    def test_default_port(self) -> None:
        self.assertIsInstance(ADMIN_PORT, int)
        self.assertGreater(ADMIN_PORT, 0)

    def test_default_host(self) -> None:
        self.assertIsInstance(ADMIN_HOST, str)
        self.assertTrue(len(ADMIN_HOST) > 0)

    def test_debug_is_bool(self) -> None:
        self.assertIsInstance(DEBUG, bool)


# ---------------------------------------------------------------------------
# containers.py tests
# ---------------------------------------------------------------------------

class TestGetComposeRoot(unittest.TestCase):
    def test_returns_path(self) -> None:
        root = get_compose_root()
        self.assertIsInstance(root, Path)

    def test_is_parent_of_admin(self) -> None:
        root = get_compose_root()
        admin_dir = Path(__file__).parent.parent / "admin"
        self.assertTrue(admin_dir.is_dir())
        self.assertEqual(root, admin_dir.parent)


# ---------------------------------------------------------------------------
# app.py tests
# ---------------------------------------------------------------------------

class TestCreateApp(unittest.TestCase):
    def test_returns_flask_app(self) -> None:
        from flask import Flask
        app = create_app()
        self.assertIsInstance(app, Flask)

    def test_debug_config(self) -> None:
        app = create_app()
        self.assertEqual(app.config["DEBUG"], DEBUG)


class TestAppRoutes(unittest.TestCase):
    """Test all routes return 200 using Flask test_client.

    Docker/subprocess calls are mocked so no real commands execute.
    """

    def setUp(self) -> None:
        self.app = create_app()
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()

    def test_index(self) -> None:
        resp = self.client.get("/")
        self.assertEqual(resp.status_code, 200)

    def test_diagnostics(self) -> None:
        resp = self.client.get("/diagnostics")
        self.assertEqual(resp.status_code, 200)

    def test_sync_page(self) -> None:
        resp = self.client.get("/sync/")
        self.assertEqual(resp.status_code, 200)

    def test_rollover(self) -> None:
        resp = self.client.get("/rollover/")
        self.assertEqual(resp.status_code, 200)


class TestApiRoutes(unittest.TestCase):
    """Test API routes with mocked subprocess to avoid real docker calls."""

    def setUp(self) -> None:
        self.app = create_app()
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()
        self._patcher = mock.patch("admin.runner.subprocess.run")
        self.mock_run = self._patcher.start()
        self.mock_run.return_value = subprocess.CompletedProcess(
            args=["docker"], returncode=0, stdout="", stderr=""
        )

    def tearDown(self) -> None:
        self._patcher.stop()

    def test_api_run(self) -> None:
        self.mock_run.return_value = subprocess.CompletedProcess(
            args=["echo"], returncode=0, stdout="hi\n", stderr=""
        )
        resp = self.client.post("/api/run", json={"args": ["echo", "hi"]})
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertTrue(data["success"])

    def test_api_run_no_args(self) -> None:
        resp = self.client.post("/api/run", json={"args": []})
        self.assertEqual(resp.status_code, 400)

    def test_containers_api_status(self) -> None:
        self.mock_run.return_value = subprocess.CompletedProcess(
            args=["docker"], returncode=0,
            stdout='{"Name":"nginx","State":"running"}\n', stderr=""
        )
        resp = self.client.get("/containers/api/status")
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertIn("services", data)
        self.assertEqual(len(data["services"]), 1)

    def test_containers_api_restart(self) -> None:
        resp = self.client.post("/containers/api/nginx/restart")
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertTrue(data["success"])

    def test_containers_api_stop(self) -> None:
        resp = self.client.post("/containers/api/nginx/stop")
        self.assertEqual(resp.status_code, 200)

    def test_containers_api_start(self) -> None:
        resp = self.client.post("/containers/api/nginx/start")
        self.assertEqual(resp.status_code, 200)

    def test_containers_api_logs(self) -> None:
        self.mock_run.return_value = subprocess.CompletedProcess(
            args=["docker"], returncode=0, stdout="log line\n", stderr=""
        )
        resp = self.client.get("/containers/api/nginx/logs")
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertIn("logs", data)

    def test_containers_logs_page(self) -> None:
        resp = self.client.get("/containers/nginx/logs")
        self.assertEqual(resp.status_code, 200)

    def test_containers_api_status_cwd_passed(self) -> None:
        """Verify that docker compose calls pass cwd to subprocess."""
        self.mock_run.return_value = subprocess.CompletedProcess(
            args=["docker"], returncode=0, stdout="{}\n", stderr=""
        )
        self.client.get("/containers/api/status")
        call_kwargs = self.mock_run.call_args
        self.assertIsNotNone(call_kwargs.kwargs.get("cwd") or call_kwargs[1].get("cwd"))

    def test_containers_index(self) -> None:
        resp = self.client.get("/containers/")
        self.assertEqual(resp.status_code, 200)

    def test_health_returns_ok_with_services(self) -> None:
        self.mock_run.return_value = subprocess.CompletedProcess(
            args=["docker"], returncode=0,
            stdout='{"Service":"nginx","State":"running"}\n', stderr=""
        )
        resp = self.client.get("/health")
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertEqual(data["status"], "ok")
        self.assertIn("timestamp", data)
        self.assertIsInstance(data["services"], list)
        self.assertEqual(len(data["services"]), 1)
        self.assertEqual(data["services"][0]["name"], "nginx")

    def test_health_returns_ok_when_docker_fails(self) -> None:
        self.mock_run.return_value = subprocess.CompletedProcess(
            args=["docker"], returncode=1, stdout="", stderr="error"
        )
        resp = self.client.get("/health")
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertEqual(data["status"], "ok")
        self.assertIsNone(data["services"])


# ---------------------------------------------------------------------------
# /api/read-file tests
# ---------------------------------------------------------------------------

class TestApiReadFile(unittest.TestCase):
    """Test the /api/read-file endpoint security and behaviour."""

    def setUp(self) -> None:
        self.app = create_app()
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()

    def test_no_path_returns_400(self) -> None:
        resp = self.client.get("/api/read-file")
        self.assertEqual(resp.status_code, 400)
        data = resp.get_json()
        self.assertFalse(data["success"])

    def test_path_outside_reports_returns_403(self) -> None:
        resp = self.client.get("/api/read-file?path=/etc/passwd")
        self.assertEqual(resp.status_code, 403)
        data = resp.get_json()
        self.assertFalse(data["success"])

    def test_path_without_runtime_reports_returns_403(self) -> None:
        resp = self.client.get("/api/read-file?path=/tmp/somefile.md")
        self.assertEqual(resp.status_code, 403)
        data = resp.get_json()
        self.assertFalse(data["success"])

    def test_valid_path_file_not_found_returns_404(self) -> None:
        resp = self.client.get(
            "/api/read-file?path=/tmp/runtime/reports/nonexistent.md"
        )
        self.assertEqual(resp.status_code, 404)
        data = resp.get_json()
        self.assertFalse(data["success"])

    def test_valid_path_reads_file(self) -> None:
        import tempfile, os
        tmpdir = tempfile.mkdtemp()
        reports_dir = Path(tmpdir) / "runtime" / "reports"
        reports_dir.mkdir(parents=True)
        report_file = reports_dir / "test-report.md"
        report_file.write_text("# Test\nHello", encoding="utf-8")
        try:
            resp = self.client.get(
                f"/api/read-file?path={report_file}"
            )
            self.assertEqual(resp.status_code, 200)
            data = resp.get_json()
            self.assertTrue(data["success"])
            self.assertEqual(data["content"], "# Test\nHello")
        finally:
            report_file.unlink()
            reports_dir.rmdir()
            (Path(tmpdir) / "runtime").rmdir()
            os.rmdir(tmpdir)

    def test_traversal_attempt_returns_403(self) -> None:
        resp = self.client.get(
            "/api/read-file?path=/foo/runtime/reports/../../etc/passwd"
        )
        self.assertEqual(resp.status_code, 403)
        data = resp.get_json()
        self.assertFalse(data["success"])


# ---------------------------------------------------------------------------
# metrics_bp.py tests
# ---------------------------------------------------------------------------

class TestMetricsPage(unittest.TestCase):
    """Test the /metrics/ page renders correctly."""

    def setUp(self) -> None:
        self.app = create_app()
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()

    def test_metrics_page_returns_200(self) -> None:
        resp = self.client.get("/metrics/")
        self.assertEqual(resp.status_code, 200)
        self.assertIn(b"Metricas operativas", resp.data)

    def test_metrics_page_includes_chart_js(self) -> None:
        resp = self.client.get("/metrics/")
        self.assertIn(b"chart.umd.min.js", resp.data)

    def test_metrics_page_includes_range_selector(self) -> None:
        resp = self.client.get("/metrics/")
        self.assertIn(b"rangeSelector", resp.data)

    def test_metrics_page_includes_group_selector(self) -> None:
        resp = self.client.get("/metrics/")
        self.assertIn(b"sistemaFilter", resp.data)
        self.assertIn(b"serviciosFilter", resp.data)


class TestMetricsApi(unittest.TestCase):
    """Test the /metrics/api/data endpoint."""

    def setUp(self) -> None:
        import tempfile
        self.app = create_app()
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()
        self._tmpdir = tempfile.mkdtemp()
        self._db_path = Path(self._tmpdir) / "test_metrics.db"

    def tearDown(self) -> None:
        import shutil
        shutil.rmtree(self._tmpdir, ignore_errors=True)

    def _patch_store(self, rows=None):
        """Patch _get_store to return a store with optional test data."""
        from ops.metrics.storage import MetricsStore
        store = MetricsStore(db_path=self._db_path)
        if rows:
            for group, metric, value, ts in rows:
                store.write_sample(group, metric, value, ts=ts)
        return mock.patch("admin.metrics_bp._get_store", return_value=store)

    def test_api_default_params(self) -> None:
        import time
        now = time.time()
        with self._patch_store([("host", "cpu_percent", 42.0, now)]):
            resp = self.client.get("/metrics/api/data")
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertEqual(data["group"], "host")
        self.assertEqual(data["range"], "1h")
        self.assertIn("metrics", data)
        self.assertIn("cpu_percent", data["metrics"])
        self.assertEqual(len(data["metrics"]["cpu_percent"]), 1)
        self.assertAlmostEqual(data["metrics"]["cpu_percent"][0]["value"], 42.0)

    def test_api_invalid_range(self) -> None:
        resp = self.client.get("/metrics/api/data?range=99h")
        self.assertEqual(resp.status_code, 400)
        data = resp.get_json()
        self.assertIn("error", data)

    def test_api_invalid_group(self) -> None:
        resp = self.client.get("/metrics/api/data?group=bogus")
        self.assertEqual(resp.status_code, 400)
        data = resp.get_json()
        self.assertIn("error", data)

    def test_api_empty_result(self) -> None:
        with self._patch_store([]):
            resp = self.client.get("/metrics/api/data?range=1h&group=nginx")
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertEqual(data["metrics"], {})

    def test_api_multiple_metrics(self) -> None:
        import time
        now = time.time()
        rows = [
            ("host", "cpu_percent", 10.0, now - 30),
            ("host", "mem_percent", 55.0, now - 30),
            ("host", "cpu_percent", 12.0, now),
        ]
        with self._patch_store(rows):
            resp = self.client.get("/metrics/api/data?range=1h&group=host")
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertIn("cpu_percent", data["metrics"])
        self.assertIn("mem_percent", data["metrics"])
        self.assertEqual(len(data["metrics"]["cpu_percent"]), 2)
        self.assertEqual(len(data["metrics"]["mem_percent"]), 1)

    def test_api_range_6h(self) -> None:
        import time
        now = time.time()
        with self._patch_store([("mysql", "queries_per_sec", 100.0, now)]):
            resp = self.client.get("/metrics/api/data?range=6h&group=mysql")
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertEqual(data["range"], "6h")
        self.assertEqual(data["group"], "mysql")

    def test_api_response_has_iso_timestamps(self) -> None:
        import time
        now = time.time()
        with self._patch_store([("host", "cpu_percent", 5.0, now)]):
            resp = self.client.get("/metrics/api/data?range=1h&group=host")
        data = resp.get_json()
        point = data["metrics"]["cpu_percent"][0]
        self.assertIn("iso", point)
        self.assertIn("ts", point)
        self.assertIn("value", point)


# ---------------------------------------------------------------------------
# health_bp.py tests
# ---------------------------------------------------------------------------

class TestHealthSummaryEndpoint(unittest.TestCase):
    """Test the /api/health-summary endpoint."""

    def setUp(self) -> None:
        self.app = create_app()
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()
        self._patcher = mock.patch("admin.runner.subprocess.run")
        self.mock_run = self._patcher.start()
        self.mock_run.return_value = subprocess.CompletedProcess(
            args=["docker"], returncode=0, stdout="", stderr=""
        )

    def tearDown(self) -> None:
        self._patcher.stop()

    def test_returns_200(self) -> None:
        resp = self.client.get("/api/health-summary")
        self.assertEqual(resp.status_code, 200)

    def test_response_has_required_keys(self) -> None:
        resp = self.client.get("/api/health-summary")
        data = resp.get_json()
        self.assertIn("timestamp", data)
        self.assertIn("services", data)
        self.assertIn("incidents", data)
        self.assertIn("cron_jobs", data)
        self.assertIn("cron_overall_status", data)

    def test_services_list_has_expected_entries(self) -> None:
        self.mock_run.return_value = subprocess.CompletedProcess(
            args=["docker"], returncode=0,
            stdout='{"Service":"lb-nginx","State":"running","Health":"healthy"}\n'
                   '{"Service":"fe-live","State":"running","Health":""}\n',
            stderr=""
        )
        resp = self.client.get("/api/health-summary")
        data = resp.get_json()
        services = data["services"]
        self.assertIsInstance(services, list)
        # Should have all expected services
        names = [s["name"] for s in services]
        self.assertIn("lb-nginx", names)
        self.assertIn("fe-live", names)
        self.assertIn("elastic", names)
        # lb-nginx should be ok (running + healthy)
        lb = next(s for s in services if s["name"] == "lb-nginx")
        self.assertEqual(lb["status"], "ok")
        # fe-live should be ok (running + empty health)
        fe = next(s for s in services if s["name"] == "fe-live")
        self.assertEqual(fe["status"], "ok")

    def test_services_unknown_when_not_in_compose(self) -> None:
        self.mock_run.return_value = subprocess.CompletedProcess(
            args=["docker"], returncode=0, stdout="", stderr=""
        )
        resp = self.client.get("/api/health-summary")
        data = resp.get_json()
        elastic = next(s for s in data["services"] if s["name"] == "elastic")
        self.assertEqual(elastic["status"], "unknown")

    def test_services_none_when_docker_fails(self) -> None:
        self.mock_run.side_effect = Exception("docker not available")
        resp = self.client.get("/api/health-summary")
        data = resp.get_json()
        self.assertIsNone(data["services"])

    def test_incidents_empty_when_no_state_file(self) -> None:
        resp = self.client.get("/api/health-summary")
        data = resp.get_json()
        self.assertEqual(data["incidents"], [])

    def test_incidents_from_state_file(self) -> None:
        import tempfile, os
        state_content = json.dumps({
            "incidents": {
                "test-key": {
                    "service": "lb-nginx",
                    "severity": "warning",
                    "summary": "test issue",
                    "last_sent_at": "2026-04-04T10:00:00Z"
                }
            }
        })
        # Patch the report dir to point to a temp dir
        tmpdir = tempfile.mkdtemp()
        report_dir = Path(tmpdir)
        state_file = report_dir / "reactive-watch-state.json"
        state_file.write_text(state_content, encoding="utf-8")
        try:
            with mock.patch("admin.health_bp._REPORT_DIR_IAOPS", report_dir):
                resp = self.client.get("/api/health-summary")
            data = resp.get_json()
            self.assertEqual(len(data["incidents"]), 1)
            self.assertEqual(data["incidents"][0]["service"], "lb-nginx")
            self.assertEqual(data["incidents"][0]["severity"], "warning")
        finally:
            state_file.unlink()
            os.rmdir(tmpdir)

    def test_cron_jobs_has_expected_labels(self) -> None:
        resp = self.client.get("/api/health-summary")
        data = resp.get_json()
        labels = [j["label"] for j in data["cron_jobs"]]
        self.assertIn("nightly", labels)
        self.assertIn("reactive", labels)
        self.assertIn("sync", labels)
        self.assertIn("metrics", labels)
        self.assertIn("cleanup", labels)

    def test_cron_jobs_unknown_without_heartbeats(self) -> None:
        import tempfile
        with tempfile.TemporaryDirectory() as tmpdir:
            with mock.patch("admin.health_bp._collect_cron_health") as mock_cron:
                mock_cron.return_value = [
                    {"label": label, "job_name": job_name,
                     "age_minutes": None, "warning_minutes": warn,
                     "critical_minutes": crit, "status": "unknown"}
                    for label, (job_name, warn, crit)
                    in admin.health_bp._CRON_JOBS.items()
                ]
                resp = self.client.get("/api/health-summary")
        data = resp.get_json()
        for job in data["cron_jobs"]:
            self.assertEqual(job["status"], "unknown")
            self.assertIsNone(job["age_minutes"])

    def test_cron_jobs_with_mocked_heartbeat(self) -> None:
        """Test cron jobs section with mocked _collect_cron_health."""
        with mock.patch("admin.health_bp._collect_cron_health") as mock_fn:
            mock_fn.return_value = [
                {"label": "metrics", "job_name": "collect-metrics",
                 "age_minutes": 2.0, "warning_minutes": 5,
                 "critical_minutes": 15, "status": "ok"},
                {"label": "nightly", "job_name": "nightly-auditor",
                 "age_minutes": 2000.0, "warning_minutes": 1440,
                 "critical_minutes": 2880, "status": "warning"},
            ]
            resp = self.client.get("/api/health-summary")
            data = resp.get_json()
            metrics_job = next(j for j in data["cron_jobs"] if j["label"] == "metrics")
            self.assertEqual(metrics_job["status"], "ok")
            nightly_job = next(j for j in data["cron_jobs"] if j["label"] == "nightly")
            self.assertEqual(nightly_job["status"], "warning")
            self.assertEqual(data["cron_overall_status"], "warning")

    def test_cron_overall_status_ok_when_all_ok(self) -> None:
        """Overall cron status is ok when all jobs are ok."""
        with mock.patch("admin.health_bp._collect_cron_health") as mock_fn:
            mock_fn.return_value = [
                {"label": "metrics", "job_name": "collect-metrics",
                 "age_minutes": 2.0, "warning_minutes": 5,
                 "critical_minutes": 15, "status": "ok"},
            ]
            resp = self.client.get("/api/health-summary")
            data = resp.get_json()
            self.assertEqual(data["cron_overall_status"], "ok")

    def test_cron_overall_status_critical_when_any_critical(self) -> None:
        """Overall cron status is critical when any job is critical."""
        with mock.patch("admin.health_bp._collect_cron_health") as mock_fn:
            mock_fn.return_value = [
                {"label": "metrics", "job_name": "collect-metrics",
                 "age_minutes": 2.0, "warning_minutes": 5,
                 "critical_minutes": 15, "status": "ok"},
                {"label": "nightly", "job_name": "nightly-auditor",
                 "age_minutes": 5000.0, "warning_minutes": 1440,
                 "critical_minutes": 2880, "status": "critical"},
            ]
            resp = self.client.get("/api/health-summary")
            data = resp.get_json()
            self.assertEqual(data["cron_overall_status"], "critical")

    def test_response_has_wordpress_key(self) -> None:
        """Health summary response includes the wordpress key."""
        resp = self.client.get("/api/health-summary")
        data = resp.get_json()
        self.assertIn("wordpress", data)

    @mock.patch("admin.health_bp._collect_wordpress_health")
    def test_wordpress_indicators_ok(self, mock_wp) -> None:
        """WordPress indicators with all-ok values."""
        mock_wp.return_value = {
            "cron_overdue": {"value": 0, "status": "ok"},
            "updates_pending": {"value": 0, "status": "ok"},
            "php_errors": {"value": 0, "status": "ok"},
            "db_autoload": {"value": 300, "status": "ok"},
        }
        resp = self.client.get("/api/health-summary")
        data = resp.get_json()
        wp = data["wordpress"]
        self.assertIsNotNone(wp)
        self.assertEqual(wp["cron_overdue"]["status"], "ok")
        self.assertEqual(wp["updates_pending"]["status"], "ok")
        self.assertEqual(wp["php_errors"]["status"], "ok")
        self.assertEqual(wp["db_autoload"]["status"], "ok")
        self.assertEqual(wp["db_autoload"]["value"], 300)

    @mock.patch("admin.health_bp._collect_wordpress_health")
    def test_wordpress_indicators_warning(self, mock_wp) -> None:
        """WordPress indicators with warning values."""
        mock_wp.return_value = {
            "cron_overdue": {"value": 3, "status": "warning"},
            "updates_pending": {"value": 2, "status": "warning"},
            "php_errors": {"value": 5, "status": "warning"},
            "db_autoload": {"value": 700, "status": "warning"},
        }
        resp = self.client.get("/api/health-summary")
        data = resp.get_json()
        wp = data["wordpress"]
        for key in ("cron_overdue", "updates_pending", "php_errors", "db_autoload"):
            self.assertEqual(wp[key]["status"], "warning")

    @mock.patch("admin.health_bp._collect_wordpress_health")
    def test_wordpress_indicators_critical(self, mock_wp) -> None:
        """WordPress indicators with critical values."""
        mock_wp.return_value = {
            "cron_overdue": {"value": 10, "status": "critical"},
            "updates_pending": {"value": 5, "status": "critical"},
            "php_errors": {"value": 20, "status": "critical"},
            "db_autoload": {"value": 1500, "status": "critical"},
        }
        resp = self.client.get("/api/health-summary")
        data = resp.get_json()
        wp = data["wordpress"]
        for key in ("cron_overdue", "updates_pending", "php_errors", "db_autoload"):
            self.assertEqual(wp[key]["status"], "critical")

    @mock.patch("admin.health_bp._collect_wordpress_health")
    def test_wordpress_none_when_unavailable(self, mock_wp) -> None:
        """WordPress section is null when collector is unavailable."""
        mock_wp.return_value = None
        resp = self.client.get("/api/health-summary")
        data = resp.get_json()
        self.assertIsNone(data["wordpress"])

    @mock.patch("admin.health_bp._collect_wordpress_health")
    def test_wordpress_none_on_exception(self, mock_wp) -> None:
        """WordPress section is null when collector raises."""
        mock_wp.side_effect = Exception("collector down")
        resp = self.client.get("/api/health-summary")
        data = resp.get_json()
        self.assertIsNone(data["wordpress"])


class TestWordPressIndicatorLogic(unittest.TestCase):
    """Test the _wp_indicator threshold logic directly."""

    def test_ok_below_warning(self) -> None:
        from admin.health_bp import _wp_indicator
        self.assertEqual(_wp_indicator(0, (1, 5)), "ok")
        self.assertEqual(_wp_indicator(0, (500, 1000)), "ok")
        self.assertEqual(_wp_indicator(499, (500, 1000)), "ok")

    def test_warning_at_boundary(self) -> None:
        from admin.health_bp import _wp_indicator
        self.assertEqual(_wp_indicator(1, (1, 5)), "warning")
        self.assertEqual(_wp_indicator(5, (1, 5)), "warning")
        self.assertEqual(_wp_indicator(500, (500, 1000)), "warning")
        self.assertEqual(_wp_indicator(1000, (500, 1000)), "warning")

    def test_critical_above_threshold(self) -> None:
        from admin.health_bp import _wp_indicator
        self.assertEqual(_wp_indicator(6, (1, 5)), "critical")
        self.assertEqual(_wp_indicator(1001, (500, 1000)), "critical")
        self.assertEqual(_wp_indicator(11, (1, 10)), "critical")


# ---------------------------------------------------------------------------
# capacity_bp.py tests
# ---------------------------------------------------------------------------

class TestLinearRegression(unittest.TestCase):
    """Test the linear regression helper."""

    def test_two_points(self) -> None:
        from admin.capacity_bp import linear_regression
        result = linear_regression([(0.0, 10.0), (1.0, 20.0)])
        self.assertIsNotNone(result)
        slope, intercept = result
        self.assertAlmostEqual(slope, 10.0)
        self.assertAlmostEqual(intercept, 10.0)

    def test_single_point_returns_none(self) -> None:
        from admin.capacity_bp import linear_regression
        self.assertIsNone(linear_regression([(0.0, 5.0)]))

    def test_empty_returns_none(self) -> None:
        from admin.capacity_bp import linear_regression
        self.assertIsNone(linear_regression([]))

    def test_flat_line(self) -> None:
        from admin.capacity_bp import linear_regression
        result = linear_regression([(0.0, 5.0), (1.0, 5.0), (2.0, 5.0)])
        self.assertIsNotNone(result)
        slope, intercept = result
        self.assertAlmostEqual(slope, 0.0)
        self.assertAlmostEqual(intercept, 5.0)

    def test_identical_x_returns_none(self) -> None:
        from admin.capacity_bp import linear_regression
        self.assertIsNone(linear_regression([(1.0, 2.0), (1.0, 3.0)]))

    def test_multiple_points(self) -> None:
        from admin.capacity_bp import linear_regression
        # y = 2x + 1
        pts = [(0.0, 1.0), (1.0, 3.0), (2.0, 5.0), (3.0, 7.0)]
        result = linear_regression(pts)
        slope, intercept = result
        self.assertAlmostEqual(slope, 2.0)
        self.assertAlmostEqual(intercept, 1.0)


class TestDaysUntilThreshold(unittest.TestCase):
    """Test the days_until_threshold helper."""

    def test_positive_slope(self) -> None:
        from admin.capacity_bp import days_until_threshold
        # y = 2x + 50, threshold=90 -> x=20, last_x=5 -> 15 days
        result = days_until_threshold(2.0, 50.0, 5.0, 90.0)
        self.assertAlmostEqual(result, 15.0)

    def test_negative_slope_returns_none(self) -> None:
        from admin.capacity_bp import days_until_threshold
        self.assertIsNone(days_until_threshold(-1.0, 50.0, 5.0, 90.0))

    def test_zero_slope_returns_none(self) -> None:
        from admin.capacity_bp import days_until_threshold
        self.assertIsNone(days_until_threshold(0.0, 50.0, 5.0, 90.0))

    def test_already_exceeded(self) -> None:
        from admin.capacity_bp import days_until_threshold
        # y = 2*10 + 75 = 95 >= 90
        result = days_until_threshold(2.0, 75.0, 10.0, 90.0)
        self.assertEqual(result, 0.0)


class TestComputeDailyAverages(unittest.TestCase):
    """Test the daily averages computation."""

    def test_empty_rows(self) -> None:
        from admin.capacity_bp import _compute_daily_averages
        self.assertEqual(_compute_daily_averages([], "cpu"), [])

    def test_no_matching_metric(self) -> None:
        from admin.capacity_bp import _compute_daily_averages
        rows = [(1000.0, "other_metric", 5.0)]
        self.assertEqual(_compute_daily_averages(rows, "cpu"), [])

    def test_single_day(self) -> None:
        from admin.capacity_bp import _compute_daily_averages
        base = 1000000.0
        rows = [
            (base, "cpu", 10.0),
            (base + 3600, "cpu", 20.0),
            (base + 7200, "cpu", 30.0),
        ]
        result = _compute_daily_averages(rows, "cpu")
        self.assertEqual(len(result), 1)
        self.assertAlmostEqual(result[0][1], 20.0)

    def test_multiple_days(self) -> None:
        from admin.capacity_bp import _compute_daily_averages
        base = 1000000.0
        rows = [
            (base, "cpu", 10.0),
            (base + 86400, "cpu", 20.0),
            (base + 86400 * 2, "cpu", 30.0),
        ]
        result = _compute_daily_averages(rows, "cpu")
        self.assertEqual(len(result), 3)
        self.assertAlmostEqual(result[0][0], 0.0)
        self.assertAlmostEqual(result[1][0], 1.0)
        self.assertAlmostEqual(result[2][0], 2.0)


class TestCapacityPage(unittest.TestCase):
    """Test the /capacity/ page."""

    def setUp(self) -> None:
        self.app = create_app()
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()

    def test_capacity_page_returns_200(self) -> None:
        resp = self.client.get("/capacity/")
        self.assertEqual(resp.status_code, 200)
        self.assertIn(b"Planificacion de capacidad", resp.data)

    def test_capacity_page_includes_chart_js(self) -> None:
        resp = self.client.get("/capacity/")
        self.assertIn(b"chart.umd.min.js", resp.data)

    def test_capacity_navbar_link(self) -> None:
        resp = self.client.get("/capacity/")
        self.assertIn(b"Capacidad", resp.data)


class TestCapacityApi(unittest.TestCase):
    """Test the /capacity/api/data endpoint."""

    def setUp(self) -> None:
        import tempfile
        self.app = create_app()
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()
        self._tmpdir = tempfile.mkdtemp()
        self._db_path = Path(self._tmpdir) / "test_cap.db"

    def tearDown(self) -> None:
        import shutil
        shutil.rmtree(self._tmpdir, ignore_errors=True)

    def _patch_store(self, rows=None):
        from ops.metrics.storage import MetricsStore
        store = MetricsStore(db_path=self._db_path)
        if rows:
            for group, metric, value, ts in rows:
                store.write_sample(group, metric, value, ts=ts)
        return mock.patch("admin.capacity_bp._get_store", return_value=store)

    def test_api_returns_metrics_list(self) -> None:
        with self._patch_store([]):
            resp = self.client.get("/capacity/api/data")
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertIn("metrics", data)
        self.assertEqual(len(data["metrics"]), 5)

    def test_api_empty_data_has_null_values(self) -> None:
        with self._patch_store([]):
            resp = self.client.get("/capacity/api/data")
        data = resp.get_json()
        disk = next(m for m in data["metrics"] if m["key"] == "disk")
        self.assertIsNone(disk["current_value"])
        self.assertIsNone(disk["slope"])
        self.assertIsNone(disk["days_until_threshold"])

    def test_api_with_data_returns_projections(self) -> None:
        import time
        now = time.time()
        rows = []
        for day in range(7):
            ts = now - (6 - day) * 86400
            rows.append(("host", "disk_used_pct", 50.0 + day * 2, ts))
        with self._patch_store(rows):
            resp = self.client.get("/capacity/api/data")
        data = resp.get_json()
        disk = next(m for m in data["metrics"] if m["key"] == "disk")
        self.assertIsNotNone(disk["current_value"])
        self.assertIsNotNone(disk["slope"])
        self.assertGreater(disk["slope"], 0)
        self.assertIsNotNone(disk["days_until_threshold"])
