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
"""


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
            "WHERE group_name = ? AND ts >= ? ORDER BY ts",
            (group, cutoff),
        )
        return cur.fetchall()

    def purge(self, max_age_hours: int) -> int:
        """Delete samples older than *max_age_hours*.

        Returns:
            Number of rows deleted.
        """
        cutoff = time.time() - max_age_hours * 3600
        cur = self._conn.execute(
            "DELETE FROM samples WHERE ts < ?", (cutoff,)
        )
        self._conn.commit()
        return cur.rowcount

    def close(self) -> None:
        """Close the underlying database connection."""
        self._conn.close()
