"""Execution history storage.

Persists the last N CLI executions to a JSON file so the admin panel
can display a run log.
"""

from __future__ import annotations

import json
import threading
from datetime import datetime, timezone
from pathlib import Path

MAX_ENTRIES = 100
_HISTORY_DIR = Path(__file__).parent.parent / "runtime"
_HISTORY_FILE = _HISTORY_DIR / "admin-history.json"
_lock = threading.Lock()


def _ensure_dir() -> None:
    """Create the runtime directory if it does not exist."""
    _HISTORY_DIR.mkdir(parents=True, exist_ok=True)


def save_entry(
    command: list[str],
    returncode: int,
    duration_seconds: float,
    success: bool,
) -> None:
    """Append an execution entry and trim to *MAX_ENTRIES*.

    Thread-safe via a module-level lock.
    """
    entry = {
        "timestamp": datetime.now(tz=timezone.utc).isoformat(),
        "command": command,
        "returncode": returncode,
        "duration_s": round(duration_seconds, 3),
        "success": success,
    }
    with _lock:
        _ensure_dir()
        entries = _read_raw()
        entries.append(entry)
        if len(entries) > MAX_ENTRIES:
            entries = entries[-MAX_ENTRIES:]
        _HISTORY_FILE.write_text(
            json.dumps(entries, indent=2, ensure_ascii=False),
            encoding="utf-8",
        )


def load_entries(limit: int = MAX_ENTRIES) -> list[dict]:
    """Return stored entries, newest first.

    Parameters
    ----------
    limit:
        Maximum number of entries to return.
    """
    with _lock:
        entries = _read_raw()
    entries.reverse()
    return entries[:limit]


def clear_entries() -> None:
    """Remove all history entries."""
    with _lock:
        _ensure_dir()
        _HISTORY_FILE.write_text("[]", encoding="utf-8")


def _read_raw() -> list[dict]:
    """Read the JSON file returning a list (oldest first)."""
    if not _HISTORY_FILE.is_file():
        return []
    try:
        data = json.loads(_HISTORY_FILE.read_text(encoding="utf-8"))
        if isinstance(data, list):
            return data
    except (json.JSONDecodeError, OSError):
        pass
    return []
