from __future__ import annotations

import unittest
from pathlib import Path
from unittest import mock

from ops.config import Settings
from ops.collectors.logs import collect_service_logs


def _settings(**overrides: str) -> Settings:
    base = {"PROJECT_ROOT": "/tmp/test-project", "LOG_TAIL_LINES": "100"}
    base.update(overrides)
    return Settings(config_file=Path("/tmp/fake.env"), values=base)


class LogsCollectorTests(unittest.TestCase):
    @mock.patch("ops.collectors.logs.subprocess.run")
    @mock.patch("ops.collectors.logs.service_logs")
    def test_collect_service_logs_filters_and_redacts(self, mock_svc_logs: mock.MagicMock, mock_run: mock.MagicMock) -> None:
        mock_svc_logs.return_value = "INFO: ok\nERROR: something broke\nINFO: fine\nFATAL: crash\n"

        def fake_run(args, *, input=None, capture_output=False, text=False, check=False):
            if "grep" in args:
                filtered = "\n".join(line for line in (input or "").splitlines() if "ERROR" in line or "FATAL" in line)
                return mock.Mock(returncode=0, stdout=filtered + "\n" if filtered else "", stderr="")
            if "redact" in " ".join(args):
                return mock.Mock(returncode=0, stdout=input or "", stderr="")
            return mock.Mock(returncode=0, stdout="", stderr="")

        mock_run.side_effect = fake_run
        result = collect_service_logs(_settings(), "lb-nginx")
        self.assertIn("ERROR", result)
        self.assertIn("FATAL", result)
        self.assertNotIn("INFO", result)

    @mock.patch("ops.collectors.logs.subprocess.run")
    @mock.patch("ops.collectors.logs.service_logs")
    def test_collect_service_logs_returns_empty_when_no_matches(self, mock_svc_logs: mock.MagicMock, mock_run: mock.MagicMock) -> None:
        mock_svc_logs.return_value = "INFO: all good\n"

        def fake_run(args, *, input=None, capture_output=False, text=False, check=False):
            if "grep" in args:
                return mock.Mock(returncode=1, stdout="", stderr="")
            return mock.Mock(returncode=0, stdout="", stderr="")

        mock_run.side_effect = fake_run
        result = collect_service_logs(_settings(), "lb-nginx")
        self.assertEqual(result, "")

    @mock.patch("ops.collectors.logs.subprocess.run")
    @mock.patch("ops.collectors.logs.service_logs")
    def test_collect_service_logs_uses_custom_pattern(self, mock_svc_logs: mock.MagicMock, mock_run: mock.MagicMock) -> None:
        mock_svc_logs.return_value = "WARN: something\n"

        patterns_seen: list[str] = []

        def fake_run(args, *, input=None, capture_output=False, text=False, check=False):
            if "grep" in args:
                patterns_seen.append(args[args.index("-E") + 1] if "-E" in args else "unknown")
                return mock.Mock(returncode=1, stdout="", stderr="")
            return mock.Mock(returncode=0, stdout="", stderr="")

        mock_run.side_effect = fake_run
        collect_service_logs(_settings(), "lb-nginx", pattern="WARN|NOTICE")
        self.assertEqual(patterns_seen[0], "WARN|NOTICE")


if __name__ == "__main__":
    unittest.main()
