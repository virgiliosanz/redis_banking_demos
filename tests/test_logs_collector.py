from __future__ import annotations

import unittest
from pathlib import Path
from unittest import mock

from ops.config import Settings
from ops.collectors.logs import collect_service_logs
from ops.util.process import CommandResult


def _settings(**overrides: str) -> Settings:
    base = {"PROJECT_ROOT": "/tmp/test-project", "LOG_TAIL_LINES": "100"}
    base.update(overrides)
    return Settings(config_file=Path("/tmp/fake.env"), values=base)


class LogsCollectorTests(unittest.TestCase):
    @mock.patch("ops.collectors.logs.run_command")
    @mock.patch("ops.collectors.logs.service_logs")
    def test_collect_service_logs_filters_and_redacts(self, mock_svc_logs: mock.MagicMock, mock_run_cmd: mock.MagicMock) -> None:
        mock_svc_logs.return_value = "INFO: ok\nERROR: something broke\nINFO: fine\nFATAL: crash\n"
        mock_run_cmd.return_value = CommandResult(
            args=["redact"], returncode=0, stdout="ERROR: something broke\nFATAL: crash", stderr="",
        )

        result = collect_service_logs(_settings(), "lb-nginx")
        self.assertIn("ERROR", result)
        self.assertIn("FATAL", result)
        self.assertNotIn("INFO", result)
        mock_run_cmd.assert_called_once()

    @mock.patch("ops.collectors.logs.run_command")
    @mock.patch("ops.collectors.logs.service_logs")
    def test_collect_service_logs_returns_empty_when_no_matches(self, mock_svc_logs: mock.MagicMock, mock_run_cmd: mock.MagicMock) -> None:
        mock_svc_logs.return_value = "INFO: all good\n"

        result = collect_service_logs(_settings(), "lb-nginx")
        self.assertEqual(result, "")
        mock_run_cmd.assert_not_called()

    @mock.patch("ops.collectors.logs.run_command")
    @mock.patch("ops.collectors.logs.service_logs")
    def test_collect_service_logs_uses_custom_pattern(self, mock_svc_logs: mock.MagicMock, mock_run_cmd: mock.MagicMock) -> None:
        mock_svc_logs.return_value = "WARN: something\nINFO: ok\n"
        mock_run_cmd.return_value = CommandResult(
            args=["redact"], returncode=0, stdout="WARN: something", stderr="",
        )

        result = collect_service_logs(_settings(), "lb-nginx", pattern="WARN|NOTICE")
        self.assertIn("WARN", result)
        self.assertNotIn("INFO", result)

    @mock.patch("ops.collectors.logs.run_command")
    @mock.patch("ops.collectors.logs.service_logs")
    def test_collect_falls_back_to_filtered_on_redact_failure(self, mock_svc_logs: mock.MagicMock, mock_run_cmd: mock.MagicMock) -> None:
        mock_svc_logs.return_value = "ERROR: secret data\n"
        mock_run_cmd.return_value = CommandResult(
            args=["redact"], returncode=1, stdout="", stderr="script not found",
        )

        result = collect_service_logs(_settings(), "lb-nginx")
        self.assertIn("ERROR: secret data", result)


if __name__ == "__main__":
    unittest.main()
