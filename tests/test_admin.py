"""Unit tests for the admin panel package."""

from __future__ import annotations

import subprocess
import unittest
from pathlib import Path
from unittest import mock

from admin.runner import CommandResult, run_cli
from admin.config import ADMIN_PORT, ADMIN_HOST, DEBUG
from admin.containers import get_compose_root
from admin.app import create_app


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

    def test_sync_editorial(self) -> None:
        resp = self.client.get("/sync/editorial")
        self.assertEqual(resp.status_code, 200)

    def test_sync_platform(self) -> None:
        resp = self.client.get("/sync/platform")
        self.assertEqual(resp.status_code, 200)

    def test_sync_drift(self) -> None:
        resp = self.client.get("/sync/drift")
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