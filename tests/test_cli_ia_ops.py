"""Tests for ops.cli.ia_ops — parser construction, argument parsing, and dispatch."""

from __future__ import annotations

import argparse
import io
import unittest

from unittest.mock import patch, MagicMock

from ops.cli.ia_ops import (
    build_parser,
    cmd_cleanup_data,
    cmd_collect_host,
    cmd_collect_metrics,
    cmd_collect_service_logs,
    cmd_collect_nightly_context,
    cmd_render_cleanup_crontab,
    cmd_report_drift,
    cmd_render_nightly_crontab,
    cmd_run_nightly,
    cmd_run_sentry,
    cmd_run_reactive_watch,
    cmd_send_telegram_test,
    cmd_sync_editorial,
    cmd_sync_platform,
    cmd_rollover_content_year,
)


class TestBuildParser(unittest.TestCase):
    """build_parser() returns a valid ArgumentParser."""

    def test_returns_argument_parser(self):
        parser = build_parser()
        self.assertIsInstance(parser, argparse.ArgumentParser)

    def test_subparsers_are_required(self):
        parser = build_parser()
        with self.assertRaises(SystemExit):
            parser.parse_args([])


class TestHelpDoesNotFail(unittest.TestCase):
    """--help on the root parser and selected subcommands exits 0."""

    def _help_exits_zero(self, argv: list[str]):
        parser = build_parser()
        with self.assertRaises(SystemExit) as ctx:
            parser.parse_args(argv)
        self.assertEqual(ctx.exception.code, 0)

    def test_root_help(self):
        self._help_exits_zero(["--help"])

    def test_subcommand_help(self):
        self._help_exits_zero(["run-sentry-agent", "--help"])


class TestParsingSimpleCollectors(unittest.TestCase):
    """Subcommands without extra arguments parse correctly."""

    SIMPLE_SUBCOMMANDS = [
        "collect-host-health",
        "collect-cron-health",
        "collect-elastic-health",
        "collect-runtime-health",
        "collect-app-health",
        "collect-mysql-health",
        "report-live-archive-sync-drift",
        "cleanup-data",
    ]

    def test_simple_subcommands_parse(self):
        parser = build_parser()
        for subcmd in self.SIMPLE_SUBCOMMANDS:
            with self.subTest(subcmd=subcmd):
                args = parser.parse_args([subcmd])
                self.assertEqual(args.command, subcmd)
                self.assertTrue(callable(args.func))


class TestParsingSentryAgent(unittest.TestCase):
    """run-sentry-agent requires --service and accepts optional flags."""

    def test_requires_service(self):
        parser = build_parser()
        with self.assertRaises(SystemExit):
            parser.parse_args(["run-sentry-agent"])

    def test_minimal(self):
        parser = build_parser()
        args = parser.parse_args(["run-sentry-agent", "--service", "nginx"])
        self.assertEqual(args.service, "nginx")
        self.assertFalse(args.notify_telegram)
        self.assertIsNone(args.pattern)
        self.assertIsNone(args.summary)

    def test_all_flags(self):
        parser = build_parser()
        args = parser.parse_args([
            "run-sentry-agent",
            "--service", "mysql",
            "--pattern", "OOM",
            "--summary", "disk full",
            "--notify-telegram",
            "--telegram-preview",
            "--no-write-report",
        ])
        self.assertEqual(args.service, "mysql")
        self.assertEqual(args.pattern, "OOM")
        self.assertEqual(args.summary, "disk full")
        self.assertTrue(args.notify_telegram)
        self.assertTrue(args.telegram_preview)
        self.assertTrue(args.no_write_report)


class TestParsingNightlyAuditor(unittest.TestCase):
    def test_defaults(self):
        parser = build_parser()
        args = parser.parse_args(["run-nightly-auditor"])
        self.assertFalse(args.no_write_report)
        self.assertFalse(args.notify_telegram)


class TestParsingSyncEditorial(unittest.TestCase):
    def test_requires_mode(self):
        parser = build_parser()
        with self.assertRaises(SystemExit):
            parser.parse_args(["sync-editorial-users"])

    def test_valid_mode(self):
        parser = build_parser()
        args = parser.parse_args(["sync-editorial-users", "--mode", "dry-run"])
        self.assertEqual(args.mode, "dry-run")


class TestParsingRollover(unittest.TestCase):
    def test_requires_mode_and_year(self):
        parser = build_parser()
        with self.assertRaises(SystemExit):
            parser.parse_args(["rollover-content-year"])

    def test_valid_args(self):
        parser = build_parser()
        args = parser.parse_args(["rollover-content-year", "--mode", "dry-run", "--year", "2024"])
        self.assertEqual(args.mode, "dry-run")
        self.assertEqual(args.year, 2024)


class TestDispatchFunctions(unittest.TestCase):
    """The func default set on each subparser points to the expected handler."""

    EXPECTED_DISPATCH = {
        "collect-host-health": cmd_collect_host,
        "collect-service-logs": (cmd_collect_service_logs, ["collect-service-logs", "nginx"]),
        "collect-nightly-context": cmd_collect_nightly_context,
        "report-live-archive-sync-drift": cmd_report_drift,
        "render-nightly-crontab": cmd_render_nightly_crontab,
        "render-cleanup-crontab": cmd_render_cleanup_crontab,
        "cleanup-data": cmd_cleanup_data,
        "run-nightly-auditor": cmd_run_nightly,
        "run-sentry-agent": (cmd_run_sentry, ["run-sentry-agent", "--service", "x"]),
        "run-reactive-watch": cmd_run_reactive_watch,
        "send-telegram-test": cmd_send_telegram_test,
        "sync-editorial-users": (cmd_sync_editorial, ["sync-editorial-users", "--mode", "dry-run"]),
        "sync-platform-config": (cmd_sync_platform, ["sync-platform-config", "--mode", "dry-run"]),
        "rollover-content-year": (cmd_rollover_content_year, ["rollover-content-year", "--mode", "dry-run", "--year", "2024"]),
    }

    def test_dispatch_targets(self):
        parser = build_parser()
        for subcmd, expected in self.EXPECTED_DISPATCH.items():
            with self.subTest(subcmd=subcmd):
                if isinstance(expected, tuple):
                    expected_func, argv = expected
                else:
                    expected_func = expected
                    argv = [subcmd]
                args = parser.parse_args(argv)
                self.assertIs(args.func, expected_func)


class TestCollectServiceLogsParsing(unittest.TestCase):
    def test_service_required(self):
        parser = build_parser()
        with self.assertRaises(SystemExit):
            parser.parse_args(["collect-service-logs"])

    def test_service_and_optional_pattern(self):
        parser = build_parser()
        args = parser.parse_args(["collect-service-logs", "nginx", "error"])
        self.assertEqual(args.service, "nginx")
        self.assertEqual(args.pattern, "error")



class TestCmdCleanupData(unittest.TestCase):
    """cmd_cleanup_data aggregates metrics, purges, and cleans reports."""

    @patch("ops.cli.ia_ops.write_heartbeat")
    @patch("ops.cli.ia_ops.load_settings")
    @patch("admin.reports.cleanup_old_reports", return_value={"deleted": 3, "remaining": 10})
    @patch("ops.metrics.storage.MetricsStore")
    def test_cleanup_data_returns_json_summary(self, mock_store_cls, mock_cleanup, mock_load_settings, mock_write_hb):
        from pathlib import Path
        mock_settings = MagicMock()
        mock_settings.get_path.return_value = Path("/tmp/heartbeats")
        mock_settings.project_root.resolve.return_value = Path("/tmp/project")
        mock_load_settings.return_value = mock_settings

        mock_store = MagicMock()
        mock_store.aggregate.return_value = 42
        mock_store.purge.return_value = 5
        mock_store_cls.return_value = mock_store

        import io as _io
        import sys as _sys
        captured = _io.StringIO()
        old_stdout = _sys.stdout
        _sys.stdout = captured
        try:
            args = build_parser().parse_args(["cleanup-data"])
            rc = cmd_cleanup_data(args)
        finally:
            _sys.stdout = old_stdout

        self.assertEqual(rc, 0)
        mock_store.aggregate.assert_called_once()
        mock_store.purge.assert_called_once_with(max_age_hours=24)
        mock_store.close.assert_called_once()
        mock_cleanup.assert_called_once()
        mock_write_hb.assert_called_once()
        self.assertEqual(mock_write_hb.call_args[0][1], "cleanup-data")

        import json
        output = json.loads(captured.getvalue())
        self.assertEqual(output["aggregated"], 42)
        self.assertEqual(output["purged_metrics"], 5)
        self.assertEqual(output["purged_reports"], 3)


class TestCmdCollectMetricsHeartbeat(unittest.TestCase):
    """cmd_collect_metrics writes a heartbeat on success."""

    @patch("ops.cli.ia_ops.write_heartbeat")
    @patch("ops.cli.ia_ops.load_settings")
    @patch("ops.collectors.metrics.collect_and_store", return_value={"samples": 10, "purged": 0})
    @patch("ops.metrics.storage.MetricsStore")
    def test_collect_metrics_writes_heartbeat(self, mock_store_cls, mock_collect, mock_load_settings, mock_write_hb):
        from pathlib import Path
        mock_settings = MagicMock()
        mock_settings.get_path.return_value = Path("/tmp/heartbeats")
        mock_settings.project_root.resolve.return_value = Path("/tmp/project")
        mock_load_settings.return_value = mock_settings

        mock_store = MagicMock()
        mock_store_cls.return_value = mock_store

        import io as _io
        import sys as _sys
        captured = _io.StringIO()
        old_stdout = _sys.stdout
        _sys.stdout = captured
        try:
            args = build_parser().parse_args(["collect-metrics"])
            rc = cmd_collect_metrics(args)
        finally:
            _sys.stdout = old_stdout

        self.assertEqual(rc, 0)
        mock_write_hb.assert_called_once()
        self.assertEqual(mock_write_hb.call_args[0][1], "collect-metrics")
