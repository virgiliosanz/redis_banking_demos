"""Tests for ops.metrics.storage.MetricsStore."""

from __future__ import annotations

import time
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from ops.metrics.storage import MetricsStore


class MetricsStoreTests(unittest.TestCase):
    """Unit tests for MetricsStore."""

    def setUp(self) -> None:
        self._tmpdir = TemporaryDirectory()
        self.db_path = Path(self._tmpdir.name) / "test.db"
        self.store = MetricsStore(db_path=self.db_path)

    def tearDown(self) -> None:
        self.store.close()
        self._tmpdir.cleanup()

    # ------------------------------------------------------------------
    # Schema / init
    # ------------------------------------------------------------------

    def test_creates_db_and_table(self) -> None:
        self.assertTrue(self.db_path.exists())
        cur = self.store._conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='samples'"
        )
        self.assertEqual(cur.fetchone()[0], "samples")

    def test_creates_parent_directories(self) -> None:
        nested = Path(self._tmpdir.name) / "a" / "b" / "metrics.db"
        store2 = MetricsStore(db_path=nested)
        try:
            self.assertTrue(nested.parent.exists())
        finally:
            store2.close()

    # ------------------------------------------------------------------
    # write_sample
    # ------------------------------------------------------------------

    def test_write_sample_inserts_row(self) -> None:
        self.store.write_sample("host", "cpu", 42.5, ts=1000.0)
        cur = self.store._conn.execute("SELECT ts, group_name, metric_name, value FROM samples")
        row = cur.fetchone()
        self.assertEqual(row, (1000.0, "host", "cpu", 42.5))

    def test_write_sample_default_ts(self) -> None:
        before = time.time()
        self.store.write_sample("host", "mem", 80.0)
        after = time.time()
        cur = self.store._conn.execute("SELECT ts FROM samples")
        ts = cur.fetchone()[0]
        self.assertGreaterEqual(ts, before)
        self.assertLessEqual(ts, after)

    # ------------------------------------------------------------------
    # query
    # ------------------------------------------------------------------

    def test_query_filters_by_group_and_range(self) -> None:
        now = time.time()
        self.store.write_sample("host", "cpu", 10.0, ts=now - 30)
        self.store.write_sample("host", "cpu", 20.0, ts=now - 90)
        self.store.write_sample("mysql", "qps", 99.0, ts=now - 30)

        rows = self.store.query("host", range_minutes=1)
        self.assertEqual(len(rows), 1)
        self.assertAlmostEqual(rows[0][2], 10.0)

    def test_query_returns_ordered_by_ts(self) -> None:
        now = time.time()
        self.store.write_sample("g", "m", 3.0, ts=now - 10)
        self.store.write_sample("g", "m", 1.0, ts=now - 30)
        self.store.write_sample("g", "m", 2.0, ts=now - 20)

        rows = self.store.query("g", range_minutes=1)
        values = [r[2] for r in rows]
        self.assertEqual(values, [1.0, 2.0, 3.0])

    def test_query_empty(self) -> None:
        rows = self.store.query("nonexistent", range_minutes=60)
        self.assertEqual(rows, [])

    def test_query_prefix_matching(self) -> None:
        """Querying 'mysql' returns rows from 'mysql' and 'mysql.db-live'."""
        now = time.time()
        self.store.write_sample("mysql", "global_metric", 1.0, ts=now - 10)
        self.store.write_sample("mysql.db-live", "threads_connected", 5.0, ts=now - 10)
        self.store.write_sample("mysql.db-archive", "threads_connected", 3.0, ts=now - 10)
        self.store.write_sample("phpfpm", "unrelated", 99.0, ts=now - 10)

        rows = self.store.query("mysql", range_minutes=1)
        metrics = {name: val for _, name, val in rows}
        self.assertEqual(len(rows), 3)
        self.assertAlmostEqual(metrics["global_metric"], 1.0)
        self.assertIn("threads_connected", metrics)

    def test_query_prefix_no_false_positives(self) -> None:
        """Querying 'mysql' must not return 'mysqld' or 'mysql_extra'."""
        now = time.time()
        self.store.write_sample("mysql", "m1", 1.0, ts=now - 10)
        self.store.write_sample("mysqld", "m2", 2.0, ts=now - 10)

        rows = self.store.query("mysql", range_minutes=1)
        self.assertEqual(len(rows), 1)

    # ------------------------------------------------------------------
    # purge
    # ------------------------------------------------------------------

    def test_purge_removes_old_samples(self) -> None:
        now = time.time()
        self.store.write_sample("g", "m", 1.0, ts=now - 7200)  # 2h ago
        self.store.write_sample("g", "m", 2.0, ts=now - 60)    # 1min ago

        deleted = self.store.purge(max_age_hours=1)
        self.assertEqual(deleted, 1)

        cur = self.store._conn.execute("SELECT COUNT(*) FROM samples")
        self.assertEqual(cur.fetchone()[0], 1)

    def test_purge_nothing_to_delete(self) -> None:
        now = time.time()
        self.store.write_sample("g", "m", 1.0, ts=now)
        deleted = self.store.purge(max_age_hours=1)
        self.assertEqual(deleted, 0)


if __name__ == "__main__":
    unittest.main()
