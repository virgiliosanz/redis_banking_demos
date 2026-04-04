"""SQLite-backed storage for operational metrics samples."""

from __future__ import annotations

import sqlite3
import time
from pathlib import Path
from typing import List, Tuple

DEFAULT_DB_PATH = Path("runtime/metrics/metrics.db")

_SCHEMA = """\
CREATE TABLE IF NOT EXISTS samples (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    ts         REAL    NOT NULL,
    group_name TEXT    NOT NULL,
    metric_name TEXT   NOT NULL,
    value      REAL    NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_samples_ts_group
    ON samples (ts, group_name);

CREATE TABLE IF NOT EXISTS samples_hourly (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    ts_hour      REAL    NOT NULL,
    group_name   TEXT    NOT NULL,
    metric_name  TEXT    NOT NULL,
    avg_value    REAL    NOT NULL,
    min_value    REAL    NOT NULL,
    max_value    REAL    NOT NULL,
    sample_count INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_samples_hourly_ts_group
    ON samples_hourly (ts_hour, group_name);
"""

# Default thresholds
_DEFAULT_AGGREGATE_AGE_HOURS = 24
_DEFAULT_HOURLY_RETENTION_HOURS = 7 * 24  # 7 days


class MetricsStore:
    """Thin wrapper around a SQLite database for time-series metric samples."""

    def __init__(self, db_path: str | Path | None = None) -> None:
        self._db_path = Path(db_path) if db_path else DEFAULT_DB_PATH
        self._db_path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(self._db_path))
        self._conn.executescript(_SCHEMA)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def write_sample(
        self, group: str, metric: str, value: float, ts: float | None = None
    ) -> None:
        """Insert a single metric sample.

        Args:
            group: Logical group (e.g. ``"host"``, ``"mysql"``).
            metric: Metric name inside the group.
            value: Numeric value of the sample.
            ts: Unix epoch timestamp; defaults to ``time.time()``.
        """
        ts = ts if ts is not None else time.time()
        self._conn.execute(
            "INSERT INTO samples (ts, group_name, metric_name, value) VALUES (?, ?, ?, ?)",
            (ts, group, metric, value),
        )
        self._conn.commit()

    def query(
        self, group: str, range_minutes: int
    ) -> List[Tuple[float, str, float]]:
        """Return samples for *group* within the last *range_minutes*.

        Returns:
            List of ``(ts, metric_name, value)`` tuples ordered by ``ts``.
        """
        cutoff = time.time() - range_minutes * 60
        cur = self._conn.execute(
            "SELECT ts, metric_name, value FROM samples "
            "WHERE (group_name = ? OR group_name LIKE ?) AND ts >= ? ORDER BY ts",
            (group, group + ".%", cutoff),
        )
        return cur.fetchall()

    def aggregate(self, age_hours: int = _DEFAULT_AGGREGATE_AGE_HOURS) -> int:
        """Downsample raw samples older than *age_hours* into hourly averages.

        Groups raw rows by hour, group_name and metric_name, inserts
        avg/min/max/count into ``samples_hourly``, then deletes the
        aggregated raw rows.

        Returns:
            Number of raw rows consumed.
        """
        cutoff = time.time() - age_hours * 3600
        # Insert aggregates
        self._conn.execute(
            """
            INSERT INTO samples_hourly
                (ts_hour, group_name, metric_name, avg_value, min_value, max_value, sample_count)
            SELECT
                CAST(strftime('%s',
                    strftime('%Y-%m-%d %H:00', ts, 'unixepoch')
                ) AS REAL) AS ts_hour,
                group_name,
                metric_name,
                AVG(value),
                MIN(value),
                MAX(value),
                COUNT(*)
            FROM samples
            WHERE ts < ?
            GROUP BY ts_hour, group_name, metric_name
            """,
            (cutoff,),
        )
        # Delete the raw rows that were aggregated
        cur = self._conn.execute(
            "DELETE FROM samples WHERE ts < ?", (cutoff,)
        )
        self._conn.commit()
        return cur.rowcount

    def query_extended(
        self, group: str, range_minutes: int, offset_seconds: int = 0
    ) -> List[Tuple[float, str, float]]:
        """Return merged raw + hourly data for *group* over *range_minutes*.

        Recent data (still in ``samples``) is returned as-is.  Older data
        that has been aggregated into ``samples_hourly`` is returned using
        ``avg_value`` as the value.  Both sets are merged and ordered by
        timestamp.

        Args:
            offset_seconds: when non-zero the query window is shifted back
                by this many seconds.  Useful for fetching a historical
                comparison window (e.g. yesterday's data).

        Returns:
            List of ``(ts, metric_name, value)`` tuples ordered by ``ts``.
        """
        now = time.time()
        cutoff = now - range_minutes * 60 - offset_seconds
        upper = now - offset_seconds

        # Hourly aggregates for older data
        cur_hourly = self._conn.execute(
            "SELECT ts_hour, metric_name, avg_value FROM samples_hourly "
            "WHERE (group_name = ? OR group_name LIKE ?) AND ts_hour >= ? AND ts_hour <= ? "
            "ORDER BY ts_hour",
            (group, group + ".%", cutoff, upper),
        )
        hourly_rows = cur_hourly.fetchall()

        # Raw samples (whatever is still in the raw table within range)
        cur_raw = self._conn.execute(
            "SELECT ts, metric_name, value FROM samples "
            "WHERE (group_name = ? OR group_name LIKE ?) AND ts >= ? AND ts <= ? "
            "ORDER BY ts",
            (group, group + ".%", cutoff, upper),
        )
        raw_rows = cur_raw.fetchall()

        # Merge both series by timestamp
        merged = sorted(hourly_rows + raw_rows, key=lambda r: r[0])
        return merged

    def purge(
        self, max_age_hours: int, hourly_max_age_hours: int = _DEFAULT_HOURLY_RETENTION_HOURS
    ) -> int:
        """Delete samples older than *max_age_hours* and hourly aggregates
        older than *hourly_max_age_hours*.

        Returns:
            Total number of rows deleted (raw + hourly).
        """
        cutoff = time.time() - max_age_hours * 3600
        cur = self._conn.execute(
            "DELETE FROM samples WHERE ts < ?", (cutoff,)
        )
        raw_deleted = cur.rowcount

        hourly_cutoff = time.time() - hourly_max_age_hours * 3600
        cur_h = self._conn.execute(
            "DELETE FROM samples_hourly WHERE ts_hour < ?", (hourly_cutoff,)
        )
        hourly_deleted = cur_h.rowcount

        self._conn.commit()
        return raw_deleted + hourly_deleted

    def close(self) -> None:
        """Close the underlying database connection."""
        self._conn.close()
