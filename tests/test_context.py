from __future__ import annotations

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest import mock

from ops.config import Settings
from ops.context import collect_statuses, load_drift_status
from ops.runtime.drift import DriftSection, DriftStatus


class ContextTests(unittest.TestCase):
    def test_collect_statuses_walks_nested_dicts_and_lists(self) -> None:
        payload = {
            "host": {"status": "ok"},
            "checks": [
                {"name": "a", "status": "warning"},
                {"name": "b", "children": [{"status": "critical"}]},
            ],
            "metadata": {"value": 1},
        }

        self.assertEqual(collect_statuses(payload), ["ok", "warning", "critical"])

    def test_load_drift_status_returns_structured_status(self) -> None:
        with TemporaryDirectory() as tmp:
            report_file = Path(tmp) / "drift.md"
            settings = Settings(config_file=report_file, values={"PROJECT_ROOT": "."})
            drift = DriftStatus(
                report_file=str(report_file),
                content="# Drift report\n",
                editorial=DriftSection(
                    status="yes",
                    summary=["- changed_users: alice(email)"],
                    details={"changed_users": [{"login": "alice", "changed_fields": ["email"]}]},
                    live_snapshot={},
                    archive_snapshot={},
                ),
                platform=DriftSection(
                    status="no",
                    summary=["- hash_mismatches: none"],
                    details={"hash_mismatches": []},
                    live_snapshot={},
                    archive_snapshot={},
                ),
            )

            with mock.patch("ops.context.build_drift_report", return_value=drift):
                loaded = load_drift_status(settings)

        self.assertEqual(loaded.report_file, str(report_file))
        self.assertEqual(loaded.editorial.status, "yes")
        self.assertEqual(loaded.platform.status, "no")


if __name__ == "__main__":
    unittest.main()
