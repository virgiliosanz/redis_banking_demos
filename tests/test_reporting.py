from __future__ import annotations

import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from ops.reporting import write_json_report, write_text_report


class ReportingTests(unittest.TestCase):
    def test_write_text_report_creates_directory_and_file(self) -> None:
        with TemporaryDirectory() as tmp:
            report_root = Path(tmp) / "reports" / "ia-ops"
            report_file = write_text_report(report_root, "nightly.md", "# report\n")

            self.assertTrue(report_root.is_dir())
            self.assertEqual(report_file.read_text(encoding="utf-8"), "# report\n")

    def test_write_json_report_formats_payload_with_trailing_newline(self) -> None:
        with TemporaryDirectory() as tmp:
            report_root = Path(tmp) / "reports"
            report_file = write_json_report(report_root, "context.json", {"status": "ok", "count": 2})
            raw = report_file.read_text(encoding="utf-8")
            payload = json.loads(raw)

        self.assertEqual(payload, {"status": "ok", "count": 2})
        self.assertTrue(raw.endswith("\n"))


if __name__ == "__main__":
    unittest.main()
