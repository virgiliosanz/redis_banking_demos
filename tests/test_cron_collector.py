from __future__ import annotations

import unittest
from unittest import mock

from ops.collectors import cron as cron_collector
from ops.config import Settings


class CronCollectorTests(unittest.TestCase):
    def test_job_specs_use_documented_defaults_when_config_is_sparse(self) -> None:
        settings = Settings(config_file=__file__, values={"PROJECT_ROOT": "."})

        specs = cron_collector._job_specs(settings)

        self.assertEqual(
            specs,
            [
                ("sync-editorial-users", 1440, 2880, "warning"),
                ("sync-platform-config", 1440, 2880, "warning"),
                ("rollover-content-year", 525600, 527040, "info"),
            ],
        )

    def test_collect_recent_log_errors_exposes_thresholds_and_source(self) -> None:
        settings = Settings(config_file=__file__, values={"PROJECT_ROOT": ".", "LOG_TAIL_LINES": "200"})

        with mock.patch("ops.collectors.cron.read_heartbeat") as read_heartbeat, mock.patch(
            "ops.collectors.cron.service_logs", return_value="ERROR a\nERROR b\n"
        ):
            read_heartbeat.return_value.age_minutes.return_value = None
            read_heartbeat.return_value.last_success_epoch = None
            payload = cron_collector.collect(settings)

        self.assertEqual(payload["recent_log_errors"]["status"], "warning")
        self.assertEqual(payload["recent_log_errors"]["tail_lines"], 200)
        self.assertEqual(payload["recent_log_errors"]["warning_threshold"], 1)
        self.assertEqual(payload["recent_log_errors"]["critical_threshold"], 5)


if __name__ == "__main__":
    unittest.main()
