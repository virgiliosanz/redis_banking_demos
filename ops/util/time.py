from __future__ import annotations

from datetime import datetime, timezone


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def utc_timestamp() -> str:
    return utc_now().strftime("%Y-%m-%dT%H:%M:%SZ")


def report_stamp() -> str:
    return utc_now().strftime("%Y%m%dT%H%M%SZ")


def epoch_now() -> int:
    return int(utc_now().timestamp())
