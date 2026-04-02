from __future__ import annotations

import unittest
from unittest import mock

from ops.collectors import app as app_collector
from ops.config import Settings


class AppCollectorTests(unittest.TestCase):
    def test_collect_exposes_urls_expected_codes_and_smoke_sources(self) -> None:
        settings = Settings(config_file=__file__, values={"PROJECT_ROOT": ".", "BASE_URL": "http://live.test", "ARCHIVE_URL": "http://archive.test"})

        with mock.patch("ops.collectors.app.get_status_code", side_effect=[200, 200, 200]), mock.patch(
            "ops.collectors.app.run_command"
        ) as run_command:
            run_command.return_value.returncode = 0
            payload = app_collector.collect(settings)

        self.assertEqual(payload["checks"]["live_login"]["url"], "http://live.test/wp-login.php")
        self.assertEqual(payload["checks"]["live_login"]["expected_http_code"], 200)
        self.assertEqual(payload["checks"]["smoke_scripts"][0]["source"], "local_smoke_script")
        self.assertEqual(payload["checks"]["smoke_scripts"][0]["script"], "./scripts/smoke-routing.sh")


if __name__ == "__main__":
    unittest.main()
