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

    # ------------------------------------------------------------------
    # samples_hourly table
    # ------------------------------------------------------------------

    def test_creates_samples_hourly_table(self) -> None:
        cur = self.store._conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='samples_hourly'"
        )
        self.assertEqual(cur.fetchone()[0], "samples_hourly")

    # ------------------------------------------------------------------
    # aggregate
    # ------------------------------------------------------------------

    def test_aggregate_downsamples_old_data(self) -> None:
        now = time.time()
        base_ts = now - 48 * 3600  # 48h ago, well past default 24h threshold
        # Insert 3 samples in the same hour
        self.store.write_sample("host", "cpu", 10.0, ts=base_ts)
        self.store.write_sample("host", "cpu", 20.0, ts=base_ts + 60)
        self.store.write_sample("host", "cpu", 30.0, ts=base_ts + 120)

        consumed = self.store.aggregate()
        self.assertEqual(consumed, 3)

        # Raw samples should be gone
        cur = self.store._conn.execute("SELECT COUNT(*) FROM samples")
        self.assertEqual(cur.fetchone()[0], 0)

        # Hourly table should have 1 row
        cur = self.store._conn.execute(
            "SELECT avg_value, min_value, max_value, sample_count FROM samples_hourly"
        )
        row = cur.fetchone()
        self.assertAlmostEqual(row[0], 20.0)  # avg
        self.assertAlmostEqual(row[1], 10.0)  # min
        self.assertAlmostEqual(row[2], 30.0)  # max
        self.assertEqual(row[3], 3)            # count

    def test_aggregate_preserves_recent_data(self) -> None:
        now = time.time()
        self.store.write_sample("host", "cpu", 50.0, ts=now - 60)  # 1min ago
        consumed = self.store.aggregate()
        self.assertEqual(consumed, 0)

        cur = self.store._conn.execute("SELECT COUNT(*) FROM samples")
        self.assertEqual(cur.fetchone()[0], 1)

    def test_aggregate_groups_by_metric(self) -> None:
        now = time.time()
        base_ts = now - 48 * 3600
        self.store.write_sample("host", "cpu", 10.0, ts=base_ts)
        self.store.write_sample("host", "mem", 80.0, ts=base_ts)

        self.store.aggregate()

        cur = self.store._conn.execute(
            "SELECT metric_name, avg_value FROM samples_hourly ORDER BY metric_name"
        )
        rows = cur.fetchall()
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0][0], "cpu")
        self.assertEqual(rows[1][0], "mem")

    def test_aggregate_custom_age(self) -> None:
        now = time.time()
        self.store.write_sample("g", "m", 1.0, ts=now - 7200)  # 2h ago
        consumed = self.store.aggregate(age_hours=1)
        self.assertEqual(consumed, 1)

    # ------------------------------------------------------------------
    # query_extended
    # ------------------------------------------------------------------

    def test_query_extended_returns_raw_for_recent(self) -> None:
        now = time.time()
        self.store.write_sample("host", "cpu", 42.0, ts=now - 30)

        rows = self.store.query_extended("host", range_minutes=1)
        self.assertEqual(len(rows), 1)
        self.assertAlmostEqual(rows[0][2], 42.0)

    def test_query_extended_returns_hourly_for_old(self) -> None:
        now = time.time()
        base_ts = now - 48 * 3600
        self.store.write_sample("host", "cpu", 10.0, ts=base_ts)
        self.store.write_sample("host", "cpu", 30.0, ts=base_ts + 60)

        self.store.aggregate()

        # Query with a wide range that covers the aggregated data
        rows = self.store.query_extended("host", range_minutes=72 * 60)
        self.assertEqual(len(rows), 1)
        self.assertAlmostEqual(rows[0][2], 20.0)  # avg_value

    def test_query_extended_merges_raw_and_hourly(self) -> None:
        now = time.time()
        base_ts = now - 48 * 3600

        # Old data (will be aggregated)
        self.store.write_sample("host", "cpu", 10.0, ts=base_ts)
        # Recent data (stays raw)
        self.store.write_sample("host", "cpu", 99.0, ts=now - 30)

        self.store.aggregate()

        rows = self.store.query_extended("host", range_minutes=72 * 60)
        self.assertEqual(len(rows), 2)
        # First should be the old aggregated point, second the recent raw
        self.assertAlmostEqual(rows[0][2], 10.0)
        self.assertAlmostEqual(rows[1][2], 99.0)

    def test_query_extended_prefix_matching(self) -> None:
        now = time.time()
        base_ts = now - 48 * 3600
        self.store.write_sample("mysql.db-live", "threads", 5.0, ts=base_ts)
        self.store.aggregate()

        rows = self.store.query_extended("mysql", range_minutes=72 * 60)
        self.assertEqual(len(rows), 1)

    # ------------------------------------------------------------------
    # purge hourly
    # ------------------------------------------------------------------

    def test_purge_removes_old_hourly_data(self) -> None:
        now = time.time()
        old_ts = now - 10 * 24 * 3600  # 10 days ago
        self.store.write_sample("g", "m", 1.0, ts=old_ts)
        self.store.aggregate(age_hours=1)

        # Hourly data exists
        cur = self.store._conn.execute("SELECT COUNT(*) FROM samples_hourly")
        self.assertGreater(cur.fetchone()[0], 0)

        # Purge with 7-day hourly retention
        deleted = self.store.purge(max_age_hours=1, hourly_max_age_hours=7 * 24)
        self.assertGreater(deleted, 0)

        cur = self.store._conn.execute("SELECT COUNT(*) FROM samples_hourly")
        self.assertEqual(cur.fetchone()[0], 0)

    def test_purge_preserves_recent_hourly_data(self) -> None:
        now = time.time()
        recent_ts = now - 48 * 3600  # 2 days ago
        self.store.write_sample("g", "m", 1.0, ts=recent_ts)
        self.store.aggregate(age_hours=1)

        deleted = self.store.purge(max_age_hours=1, hourly_max_age_hours=7 * 24)
        # Raw was already deleted by aggregate, hourly is recent
        cur = self.store._conn.execute("SELECT COUNT(*) FROM samples_hourly")
        self.assertEqual(cur.fetchone()[0], 1)


if __name__ == "__main__":
    unittest.main()
