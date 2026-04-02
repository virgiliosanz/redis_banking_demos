from __future__ import annotations

import unittest
from unittest import mock

from ops.config import Settings
from ops.collectors.mysql import collect


def _settings(**overrides: str) -> Settings:
    base = {"PROJECT_ROOT": "/tmp/test-project"}
    base.update(overrides)
    from pathlib import Path
    return Settings(config_file=Path("/tmp/fake.env"), values=base)


class MysqlCollectorTests(unittest.TestCase):
    @mock.patch("ops.collectors.mysql.compose_exec")
    def test_collect_returns_two_database_snapshots(self, mock_exec: mock.MagicMock) -> None:
        def fake_exec(service, command, *, cwd=None, check=True):
            cmd_str = " ".join(command)
            if "mysqladmin ping" in cmd_str:
                return mock.Mock(returncode=0, stdout="mysqld is alive\n", stderr="")
            if "information_schema.PROCESSLIST" in cmd_str:
                return mock.Mock(returncode=0, stdout="", stderr="")
            return mock.Mock(returncode=0, stdout="", stderr="")

        mock_exec.side_effect = fake_exec
        settings = _settings()
        result = collect(settings)

        self.assertIn("generated_at", result)
        self.assertEqual(len(result["databases"]), 2)
        for db_snapshot in result["databases"]:
            self.assertIn(db_snapshot["service"], ("db-live", "db-archive"))
            self.assertEqual(db_snapshot["ping"]["status"], "ok")
            self.assertEqual(db_snapshot["processlist"]["warning_count"], 0)

    @mock.patch("ops.collectors.mysql.compose_exec")
    def test_collect_with_ping_failure_marks_critical(self, mock_exec: mock.MagicMock) -> None:
        def fake_exec(service, command, *, cwd=None, check=True):
            cmd_str = " ".join(command)
            if "mysqladmin ping" in cmd_str:
                return mock.Mock(returncode=1, stdout="", stderr="connect failed")
            return mock.Mock(returncode=0, stdout="", stderr="")

        mock_exec.side_effect = fake_exec
        settings = _settings()
        result = collect(settings)

        for db_snapshot in result["databases"]:
            self.assertEqual(db_snapshot["ping"]["status"], "critical")
            self.assertEqual(db_snapshot["processlist"]["warning_count"], 0)

    @mock.patch("ops.collectors.mysql.compose_exec")
    def test_collect_with_long_queries(self, mock_exec: mock.MagicMock) -> None:
        processlist_output = "42\troot\twp_live\tQuery\t60\texecuting\tSELECT * FROM wp_posts"

        def fake_exec(service, command, *, cwd=None, check=True):
            cmd_str = " ".join(command)
            if "mysqladmin ping" in cmd_str:
                return mock.Mock(returncode=0, stdout="mysqld is alive\n", stderr="")
            if "information_schema.PROCESSLIST" in cmd_str:
                return mock.Mock(returncode=0, stdout=processlist_output + "\n", stderr="")
            return mock.Mock(returncode=0, stdout="", stderr="")

        mock_exec.side_effect = fake_exec
        settings = _settings()
        result = collect(settings)

        db_live = next(db for db in result["databases"] if db["service"] == "db-live")
        self.assertGreater(db_live["processlist"]["warning_count"], 0)
        self.assertEqual(db_live["processlist"]["queries"][0]["id"], 42)
        self.assertEqual(db_live["processlist"]["queries"][0]["time_seconds"], 60)


if __name__ == "__main__":
    unittest.main()
