from __future__ import annotations

from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from ops.runtime.heartbeats import read_heartbeat, write_heartbeat


class HeartbeatTests(unittest.TestCase):
    def test_missing_heartbeat_returns_non_existing_status(self) -> None:
        with TemporaryDirectory() as tmp:
            status = read_heartbeat(Path(tmp), "nightly")

        self.assertFalse(status.exists)
        self.assertIsNone(status.last_success_epoch)
        self.assertIsNone(status.age_minutes())

    def test_write_then_read_heartbeat_round_trips_epoch(self) -> None:
        with TemporaryDirectory() as tmp:
            target = write_heartbeat(Path(tmp), "nightly", epoch=1_700_000_000)
            status = read_heartbeat(Path(tmp), "nightly")

        self.assertEqual(target.name, "nightly.success")
        self.assertTrue(status.exists)
        self.assertEqual(status.last_success_epoch, 1_700_000_000)
        self.assertEqual(status.age_minutes(now_epoch=1_700_000_180), 3)

    def test_invalid_heartbeat_contents_are_treated_as_missing(self) -> None:
        with TemporaryDirectory() as tmp:
            target = Path(tmp) / "nightly.success"
            target.write_text("not-an-epoch\n", encoding="utf-8")
            status = read_heartbeat(Path(tmp), "nightly")

        self.assertFalse(status.exists)
        self.assertIsNone(status.last_success_epoch)


if __name__ == "__main__":
    unittest.main()
