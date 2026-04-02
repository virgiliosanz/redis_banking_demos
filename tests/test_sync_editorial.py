from __future__ import annotations

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

from ops.config import Settings
from ops.sync.common import ensure_sync_mode, markdown_header, write_sync_heartbeat
from ops.sync.editorial import run as editorial_run


def _settings(tmp: str, **overrides: str) -> Settings:
    base = {"PROJECT_ROOT": tmp, "CRON_HEARTBEAT_DIR": f"{tmp}/heartbeats"}
    base.update(overrides)
    return Settings(config_file=Path(tmp) / "fake.env", values=base)


class SyncCommonTests(unittest.TestCase):
    def test_ensure_sync_mode_raises_on_invalid(self) -> None:
        with self.assertRaises(ValueError):
            ensure_sync_mode("invalid-mode")

    def test_ensure_sync_mode_accepts_valid_modes(self) -> None:
        for mode in ("report-only", "dry-run", "apply"):
            self.assertEqual(ensure_sync_mode(mode), mode)

    def test_markdown_header_includes_title_and_mode(self) -> None:
        header = markdown_header("Test Title", mode="report-only", excluded_logins="admin1")
        self.assertIn("# Test Title report-only", header)
        self.assertIn("- mode: report-only", header)
        self.assertIn("- excluded_bootstrap_logins: admin1", header)

    def test_write_sync_heartbeat_creates_file(self) -> None:
        with TemporaryDirectory() as tmp:
            settings = _settings(tmp)
            result = write_sync_heartbeat(settings, "CRON_JOB_EDITORIAL_SYNC", "sync-editorial-users")
            self.assertTrue(result.exists())
            content = result.read_text(encoding="utf-8").strip()
            self.assertTrue(content.isdigit())


class EditorialSyncTests(unittest.TestCase):
    @mock.patch("ops.sync.editorial.wait_for_sync_services")
    @mock.patch("ops.sync.editorial.wp_eval_json")
    def test_editorial_run_report_only(self, mock_wp: mock.MagicMock, mock_wait: mock.MagicMock) -> None:
        mock_wp.return_value = '{"users": []}'
        with TemporaryDirectory() as tmp:
            settings = _settings(tmp)
            report_dir = Path(tmp) / "reports"
            report = editorial_run(settings, mode="report-only", report_dir=report_dir)

            self.assertTrue(report.exists())
            content = report.read_text(encoding="utf-8")
            self.assertIn("# Editorial sync report-only", content)
            self.assertIn("## Source snapshot", content)
            self.assertIn("## Plan", content)
            self.assertNotIn("## Apply result", content)

    @mock.patch("ops.sync.editorial.write_sync_heartbeat")
    @mock.patch("ops.sync.editorial.wait_for_sync_services")
    @mock.patch("ops.sync.editorial.wp_eval_json")
    def test_editorial_run_apply_writes_heartbeat(self, mock_wp: mock.MagicMock, mock_wait: mock.MagicMock, mock_hb: mock.MagicMock) -> None:
        mock_wp.return_value = '{"users": []}'
        with TemporaryDirectory() as tmp:
            settings = _settings(tmp)
            report_dir = Path(tmp) / "reports"
            report = editorial_run(settings, mode="apply", report_dir=report_dir)

            content = report.read_text(encoding="utf-8")
            self.assertIn("## Apply result", content)
            mock_hb.assert_called_once()


if __name__ == "__main__":
    unittest.main()
