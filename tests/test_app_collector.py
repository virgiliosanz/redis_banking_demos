from __future__ import annotations

import unittest
from unittest import mock

from ops.collectors import app as app_collector
from ops.config import Settings


class AppCollectorTests(unittest.TestCase):
    def _settings(self) -> Settings:
        return Settings(config_file=__file__, values={"PROJECT_ROOT": ".", "BASE_URL": "http://live.test", "ARCHIVE_URL": "http://archive.test"})

    def test_collect_exposes_urls_expected_codes_and_smoke_sources(self) -> None:
        with mock.patch("ops.collectors.app.get_status_code", side_effect=[200, 200, 200]), mock.patch(
            "ops.collectors.app.run_command"
        ) as run_command:
            run_command.return_value.returncode = 0
            payload = app_collector.collect(self._settings())

        self.assertEqual(payload["checks"]["live_login"]["url"], "http://live.test/wp-login.php")
        self.assertEqual(payload["checks"]["live_login"]["expected_http_code"], 200)
        self.assertEqual(payload["checks"]["live_login"]["status"], "ok")
        self.assertEqual(payload["checks"]["live_login"]["reason"], "")
        self.assertEqual(payload["checks"]["smoke_scripts"][0]["source"], "local_smoke_script")
        self.assertEqual(payload["checks"]["smoke_scripts"][0]["script"], "./scripts/smoke-routing.sh")
        self.assertNotIn("error_detail", payload["checks"]["smoke_scripts"][0])

    def test_http_check_unreachable_returns_unreachable_status(self) -> None:
        with mock.patch("ops.collectors.app.get_status_code", side_effect=[0, 0, 0]), mock.patch(
            "ops.collectors.app.run_command"
        ) as run_command:
            run_command.return_value.returncode = 0
            payload = app_collector.collect(self._settings())

        chk = payload["checks"]["live_login"]
        self.assertEqual(chk["status"], "unreachable")
        self.assertEqual(chk["http_code"], 0)
        self.assertIn("conectar", chk["reason"])

    def test_http_check_wrong_code_returns_critical(self) -> None:
        with mock.patch("ops.collectors.app.get_status_code", side_effect=[404, 200, 200]), mock.patch(
            "ops.collectors.app.run_command"
        ) as run_command:
            run_command.return_value.returncode = 0
            payload = app_collector.collect(self._settings())

        chk = payload["checks"]["live_login"]
        self.assertEqual(chk["status"], "critical")
        self.assertIn("404", chk["reason"])

    def test_smoke_failure_includes_error_detail(self) -> None:
        with mock.patch("ops.collectors.app.get_status_code", side_effect=[200, 200, 200]), mock.patch(
            "ops.collectors.app.run_command"
        ) as run_command:
            run_command.return_value.returncode = 1
            run_command.return_value.stderr = "connection refused"
            run_command.return_value.stdout = ""
            payload = app_collector.collect(self._settings())

        smoke = payload["checks"]["smoke_scripts"][0]
        self.assertEqual(smoke["status"], "critical")
        self.assertEqual(smoke["error_detail"], "connection refused")

    def test_smoke_failure_fallback_to_exit_code(self) -> None:
        with mock.patch("ops.collectors.app.get_status_code", side_effect=[200, 200, 200]), mock.patch(
            "ops.collectors.app.run_command"
        ) as run_command:
            run_command.return_value.returncode = 2
            run_command.return_value.stderr = ""
            run_command.return_value.stdout = ""
            payload = app_collector.collect(self._settings())

        smoke = payload["checks"]["smoke_scripts"][0]
        self.assertEqual(smoke["error_detail"], "exit code 2")


if __name__ == "__main__":
    unittest.main()
