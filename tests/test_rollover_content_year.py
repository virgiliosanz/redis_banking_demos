from __future__ import annotations

import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import mock

from ops.config import Settings
from ops.rollover.content_year import (
    VALID_ROLLOVER_MODES,
    _parse_cutover_config,
    _rollover_env,
    run,
)


def _settings(tmp: str, **overrides: str) -> Settings:
    base = {"PROJECT_ROOT": tmp, "CRON_HEARTBEAT_DIR": f"{tmp}/heartbeats"}
    base.update(overrides)
    return Settings(config_file=Path(tmp) / "fake.env", values=base)


def _write_routing_config(tmp: str) -> Path:
    routing = Path(tmp) / "routing-cutover.env"
    routing.write_text(
        "ARCHIVE_MIN_YEAR=2015\nARCHIVE_MAX_YEAR=2022\n"
        "LIVE_MIN_YEAR=2023\nLIVE_MAX_YEAR=2025\n",
        encoding="utf-8",
    )
    return routing


LIVE_SUMMARY = json.dumps({
    "selected_post_count": 5,
    "selected_term_count": 2,
    "selected_attachment_count": 1,
    "slugs_csv": "post-a,post-b",
})
ARCHIVE_COLLISIONS = json.dumps({"collision_count": 0})
SNAPSHOT_JSON = json.dumps({"posts": []})


# ── Pure function tests ──────────────────────────────────────────────


class ParseCutoverConfigTests(unittest.TestCase):
    def test_parses_all_required_keys(self) -> None:
        with TemporaryDirectory() as tmp:
            f = Path(tmp) / "routing.env"
            f.write_text(
                "ARCHIVE_MIN_YEAR=2015\nARCHIVE_MAX_YEAR=2022\n"
                "LIVE_MIN_YEAR=2023\nLIVE_MAX_YEAR=2025\n"
            )
            result = _parse_cutover_config(f)
            self.assertEqual(result["ARCHIVE_MIN_YEAR"], 2015)
            self.assertEqual(result["LIVE_MAX_YEAR"], 2025)

    def test_skips_comments_and_blanks(self) -> None:
        with TemporaryDirectory() as tmp:
            f = Path(tmp) / "routing.env"
            f.write_text(
                "# comment\n\nARCHIVE_MIN_YEAR=2015\n"
                "ARCHIVE_MAX_YEAR=2022\nLIVE_MIN_YEAR=2023\nLIVE_MAX_YEAR=2025\n"
            )
            result = _parse_cutover_config(f)
            self.assertEqual(len(result), 4)

    def test_raises_on_missing_keys(self) -> None:
        with TemporaryDirectory() as tmp:
            f = Path(tmp) / "routing.env"
            f.write_text("ARCHIVE_MIN_YEAR=2015\n")
            with self.assertRaises(RuntimeError) as ctx:
                _parse_cutover_config(f)
            self.assertIn("Missing routing cutover keys", str(ctx.exception))


class RolloverEnvTests(unittest.TestCase):
    def test_generates_correct_env_list(self) -> None:
        result = _rollover_env("dry-run", 2022)
        self.assertEqual(result, ["env", "ROLLOVER_TARGET_YEAR=2022", "ROLLOVER_MODE=dry-run"])

    def test_mode_appears_in_env(self) -> None:
        for mode in VALID_ROLLOVER_MODES:
            result = _rollover_env(mode, 2020)
            self.assertIn(f"ROLLOVER_MODE={mode}", result)


# ── Mode validation tests ────────────────────────────────────────────


class ModeValidationTests(unittest.TestCase):
    def test_valid_modes_constant(self) -> None:
        self.assertEqual(VALID_ROLLOVER_MODES, {"dry-run", "report-only", "execute"})

    def test_run_rejects_invalid_mode(self) -> None:
        with TemporaryDirectory() as tmp:
            settings = _settings(tmp)
            with self.assertRaises(ValueError) as ctx:
                run(settings, mode="invalid", target_year=2020)
            self.assertIn("Unsupported mode", str(ctx.exception))

    def test_run_rejects_future_target_year(self) -> None:
        with TemporaryDirectory() as tmp:
            routing = _write_routing_config(tmp)
            settings = _settings(tmp)
            with self.assertRaises(RuntimeError) as ctx:
                run(settings, mode="dry-run", target_year=2099, routing_config_file=routing)
            self.assertIn("earlier than current year", str(ctx.exception))

    def test_run_rejects_missing_routing_config(self) -> None:
        with TemporaryDirectory() as tmp:
            settings = _settings(tmp)
            missing = Path(tmp) / "nonexistent.env"
            with self.assertRaises(RuntimeError) as ctx:
                run(settings, mode="dry-run", target_year=2020, routing_config_file=missing)
            self.assertIn("Missing routing config", str(ctx.exception))


# ── run() with mocks ─────────────────────────────────────────────────


class RunDryRunTests(unittest.TestCase):
    @mock.patch("ops.rollover.content_year.wait_for_service_keys")
    @mock.patch("ops.rollover.content_year.compose_exec")
    def test_dry_run_creates_report(self, mock_exec: mock.MagicMock, mock_wait: mock.MagicMock) -> None:
        mock_exec.return_value = mock.MagicMock(stdout=LIVE_SUMMARY)
        # compose_exec is called 4 times: live_summary, archive_collisions, source_snapshot, archive_backup
        mock_exec.side_effect = [
            mock.MagicMock(stdout=LIVE_SUMMARY),
            mock.MagicMock(stdout=ARCHIVE_COLLISIONS),
            mock.MagicMock(stdout=SNAPSHOT_JSON),
            mock.MagicMock(stdout=SNAPSHOT_JSON),
        ]
        with TemporaryDirectory() as tmp:
            routing = _write_routing_config(tmp)
            settings = _settings(tmp)
            report_dir = Path(tmp) / "reports"
            report = run(settings, mode="dry-run", target_year=2020, report_dir=report_dir, routing_config_file=routing)
            self.assertTrue(report.exists())
            content = report.read_text(encoding="utf-8")
            self.assertIn("# Rollover dry-run 2020", content)
            self.assertIn("execute_enabled: no", content)
            self.assertNotIn("## Import result", content)

    @mock.patch("ops.rollover.content_year.wait_for_service_keys")
    @mock.patch("ops.rollover.content_year.compose_exec")
    def test_report_only_includes_cutover_warning_below(self, mock_exec: mock.MagicMock, mock_wait: mock.MagicMock) -> None:
        mock_exec.side_effect = [
            mock.MagicMock(stdout=LIVE_SUMMARY),
            mock.MagicMock(stdout=ARCHIVE_COLLISIONS),
            mock.MagicMock(stdout=SNAPSHOT_JSON),
            mock.MagicMock(stdout=SNAPSHOT_JSON),
        ]
        with TemporaryDirectory() as tmp:
            routing = _write_routing_config(tmp)  # LIVE_MIN_YEAR=2023
            settings = _settings(tmp)
            report_dir = Path(tmp) / "reports"
            report = run(settings, mode="report-only", target_year=2020, report_dir=report_dir, routing_config_file=routing)
            content = report.read_text(encoding="utf-8")
            self.assertIn("cutover_warning: target_year_is_already_below_live_cutover", content)

    @mock.patch("ops.rollover.content_year.wait_for_service_keys")
    @mock.patch("ops.rollover.content_year.compose_exec")
    def test_report_contains_json_sections(self, mock_exec: mock.MagicMock, mock_wait: mock.MagicMock) -> None:
        mock_exec.side_effect = [
            mock.MagicMock(stdout=LIVE_SUMMARY),
            mock.MagicMock(stdout=ARCHIVE_COLLISIONS),
            mock.MagicMock(stdout=SNAPSHOT_JSON),
            mock.MagicMock(stdout=SNAPSHOT_JSON),
        ]
        with TemporaryDirectory() as tmp:
            routing = _write_routing_config(tmp)
            settings = _settings(tmp)
            report_dir = Path(tmp) / "reports"
            report = run(settings, mode="dry-run", target_year=2022, report_dir=report_dir, routing_config_file=routing)
            content = report.read_text(encoding="utf-8")
            self.assertIn("## Live summary JSON", content)
            self.assertIn("## Archive collision JSON", content)
            self.assertIn("## Source snapshot JSON", content)
            self.assertIn("## Archive backup snapshot JSON", content)

    @mock.patch("ops.rollover.content_year.wait_for_service_keys")
    @mock.patch("ops.rollover.content_year.compose_exec")
    def test_compose_exec_call_count_dry_run(self, mock_exec: mock.MagicMock, mock_wait: mock.MagicMock) -> None:
        mock_exec.side_effect = [
            mock.MagicMock(stdout=LIVE_SUMMARY),
            mock.MagicMock(stdout=ARCHIVE_COLLISIONS),
            mock.MagicMock(stdout=SNAPSHOT_JSON),
            mock.MagicMock(stdout=SNAPSHOT_JSON),
        ]
        with TemporaryDirectory() as tmp:
            routing = _write_routing_config(tmp)
            settings = _settings(tmp)
            report_dir = Path(tmp) / "reports"
            run(settings, mode="dry-run", target_year=2020, report_dir=report_dir, routing_config_file=routing)
            self.assertEqual(mock_exec.call_count, 4)

    def test_execute_mode_rejects_mismatched_target_year(self) -> None:
        with TemporaryDirectory() as tmp:
            routing = _write_routing_config(tmp)  # LIVE_MIN_YEAR=2023
            settings = _settings(tmp)
            with self.assertRaises(RuntimeError) as ctx:
                run(settings, mode="execute", target_year=2020, routing_config_file=routing)
            self.assertIn("Mode execute requires target year to match LIVE_MIN_YEAR", str(ctx.exception))

    @mock.patch("ops.rollover.content_year.write_sync_heartbeat")
    @mock.patch("ops.rollover.content_year._publish_read_alias")
    @mock.patch("ops.rollover.content_year._get_index_name", return_value="test-index")
    @mock.patch("ops.rollover.content_year._reindex_site")
    @mock.patch("ops.rollover.content_year._advance_cutover")
    @mock.patch("ops.rollover.content_year._copy_to_container")
    @mock.patch("ops.rollover.content_year.wait_for_service_keys")
    @mock.patch("ops.rollover.content_year.compose_exec")
    def test_execute_mode_writes_heartbeat_and_report(
        self,
        mock_exec: mock.MagicMock,
        mock_wait: mock.MagicMock,
        mock_copy: mock.MagicMock,
        mock_advance: mock.MagicMock,
        mock_reindex: mock.MagicMock,
        mock_get_idx: mock.MagicMock,
        mock_alias: mock.MagicMock,
        mock_hb: mock.MagicMock,
    ) -> None:
        mock_exec.side_effect = [
            mock.MagicMock(stdout=LIVE_SUMMARY),      # live_summary
            mock.MagicMock(stdout=ARCHIVE_COLLISIONS), # archive_collisions
            mock.MagicMock(stdout=SNAPSHOT_JSON),       # source_snapshot
            mock.MagicMock(stdout=SNAPSHOT_JSON),       # archive_backup
            mock.MagicMock(stdout='{"imported": 5}'),   # import_result
            mock.MagicMock(stdout='{"deleted": 5}'),    # delete_result
        ]
        with TemporaryDirectory() as tmp:
            routing = _write_routing_config(tmp)  # LIVE_MIN_YEAR=2023
            settings = _settings(tmp)
            report_dir = Path(tmp) / "reports"
            report = run(settings, mode="execute", target_year=2023, report_dir=report_dir, routing_config_file=routing)
            content = report.read_text(encoding="utf-8")
            self.assertIn("execute_enabled: yes", content)
            self.assertIn("## Import result JSON", content)
            self.assertIn("## Delete result JSON", content)
            mock_hb.assert_called_once()
            mock_copy.assert_called_once()
            self.assertEqual(mock_reindex.call_count, 2)


if __name__ == "__main__":
    unittest.main()
