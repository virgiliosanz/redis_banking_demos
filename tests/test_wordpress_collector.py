"""Tests for ops.collectors.wordpress."""

from __future__ import annotations

import json
import unittest
from unittest import mock

from ops.collectors import wordpress as wordpress_collector
from ops.config import Settings


def _settings() -> Settings:
    return Settings(config_file=__file__, values={"PROJECT_ROOT": "."})


class WordPressCollectorTests(unittest.TestCase):
    """Unit tests for the WordPress health collector."""

    def _mock_compose_exec(self, live_data: dict | None = None, archive_data: dict | None = None):
        """Return a side_effect function that returns different data per context."""
        def side_effect(service, args, *, cwd=None, check=True, exec_args=None):
            result = mock.MagicMock()
            # Determine context from args
            context = "live"
            for arg in args:
                if "N9_SITE_CONTEXT=archive" in arg:
                    context = "archive"
                    break
            data = live_data if context == "live" else archive_data
            if data is None:
                result.returncode = 1
                result.stderr = "container not running"
                result.stdout = ""
            else:
                result.returncode = 0
                result.stdout = json.dumps(data)
                result.stderr = ""
            return result
        return side_effect

    @mock.patch("ops.collectors.wordpress.compose_exec")
    def test_collect_returns_live_and_archive(self, mock_exec):
        live = {
            "cron": {"total": 10, "overdue": 0, "max_overdue_seconds": 0},
            "database": {"size_mb": 100, "autoload_count": 300, "autoload_size_kb": 500, "transients_count": 50},
            "updates": {"plugins": 0, "themes": 0},
            "errors": {"php_error_count": 0},
            "content": {"posts_published": 500, "posts_draft": 3, "pages_published": 10},
        }
        archive = {
            "database": {"size_mb": 200, "autoload_count": 200, "autoload_size_kb": 400, "transients_count": 30},
            "errors": {"php_error_count": 0},
        }
        mock_exec.side_effect = self._mock_compose_exec(live, archive)

        result = wordpress_collector.collect(_settings())

        self.assertIn("generated_at", result)
        self.assertIn("live", result)
        self.assertIn("archive", result)
        self.assertEqual(result["live"]["cron"]["total"], 10)
        self.assertEqual(result["archive"]["database"]["size_mb"], 200)

    @mock.patch("ops.collectors.wordpress.compose_exec")
    def test_collect_graceful_fallback_on_failure(self, mock_exec):
        mock_exec.side_effect = self._mock_compose_exec(None, None)

        result = wordpress_collector.collect(_settings())

        self.assertIn("live", result)
        self.assertIn("archive", result)
        self.assertIn("error", result["live"])
        self.assertIn("error", result["archive"])

    @mock.patch("ops.collectors.wordpress.compose_exec")
    def test_collect_handles_invalid_json(self, mock_exec):
        def side_effect(service, args, *, cwd=None, check=True, exec_args=None):
            result = mock.MagicMock()
            result.returncode = 0
            result.stdout = "not valid json"
            result.stderr = ""
            return result
        mock_exec.side_effect = side_effect

        result = wordpress_collector.collect(_settings())

        self.assertIn("error", result["live"])
        self.assertIn("JSON invalido", result["live"]["error"])

    @mock.patch("ops.collectors.wordpress.compose_exec")
    def test_collect_handles_exception(self, mock_exec):
        mock_exec.side_effect = Exception("Docker not available")

        result = wordpress_collector.collect(_settings())

        self.assertIn("error", result["live"])
        self.assertIn("Docker not available", result["live"]["error"])

    @mock.patch("ops.collectors.wordpress.compose_exec")
    def test_uses_cron_master_service(self, mock_exec):
        mock_exec.side_effect = self._mock_compose_exec({"ok": True}, {"ok": True})

        wordpress_collector.collect(_settings())

        for call in mock_exec.call_args_list:
            self.assertEqual(call[0][0], "cron-master")

    @mock.patch("ops.collectors.wordpress.compose_exec")
    def test_exec_args_include_root_user(self, mock_exec):
        mock_exec.side_effect = self._mock_compose_exec({"ok": True}, {"ok": True})

        wordpress_collector.collect(_settings())

        for call in mock_exec.call_args_list:
            self.assertEqual(call[1].get("exec_args"), ["--user", "root"])


class WordPressDiagnosticsEndpointTests(unittest.TestCase):
    """Test the /diagnostics/wordpress endpoint."""

    def setUp(self) -> None:
        from admin.app import create_app
        self.app = create_app()
        self.app.config["TESTING"] = True
        self.client = self.app.test_client()

    @mock.patch("admin.diagnostics_bp.wordpress_collector")
    def test_wordpress_endpoint_returns_html(self, mock_collector):
        mock_collector.collect.return_value = {
            "generated_at": "2026-04-05T12:00:00Z",
            "live": {
                "cron": {"total": 5, "overdue": 0, "max_overdue_seconds": 0},
                "database": {"size_mb": 100, "autoload_count": 300, "autoload_size_kb": 500, "transients_count": 50},
                "updates": {"plugins": 0, "themes": 0},
                "errors": {"php_error_count": 0},
                "content": {"posts_published": 100, "posts_draft": 2, "pages_published": 5},
            },
            "archive": {
                "database": {"size_mb": 200, "autoload_count": 200, "autoload_size_kb": 400, "transients_count": 30},
                "errors": {"php_error_count": 0},
            },
        }
        resp = self.client.get("/diagnostics/wordpress")
        self.assertEqual(resp.status_code, 200)
        self.assertIn(b"WordPress", resp.data)

    @mock.patch("admin.diagnostics_bp.wordpress_collector")
    def test_wordpress_endpoint_returns_json(self, mock_collector):
        mock_collector.collect.return_value = {"generated_at": "now", "live": {}, "archive": {}}
        resp = self.client.get("/diagnostics/wordpress", headers={"Accept": "application/json"})
        self.assertEqual(resp.status_code, 200)
        data = resp.get_json()
        self.assertIn("generated_at", data)

    @mock.patch("admin.diagnostics_bp.wordpress_collector")
    def test_wordpress_endpoint_handles_error(self, mock_collector):
        mock_collector.collect.side_effect = Exception("boom")
        resp = self.client.get("/diagnostics/wordpress")
        self.assertEqual(resp.status_code, 200)

    @mock.patch("admin.diagnostics_bp.wordpress_collector")
    def test_wordpress_json_error_returns_500(self, mock_collector):
        mock_collector.collect.side_effect = Exception("boom")
        resp = self.client.get("/diagnostics/wordpress", headers={"Accept": "application/json"})
        self.assertEqual(resp.status_code, 500)
        data = resp.get_json()
        self.assertIn("error", data)


if __name__ == "__main__":
    unittest.main()
