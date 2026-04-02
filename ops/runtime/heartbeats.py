from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from ..util.time import epoch_now


@dataclass(frozen=True)
class HeartbeatStatus:
    job_name: str
    heartbeat_file: Path
    last_success_epoch: int | None

    @property
    def exists(self) -> bool:
        return self.last_success_epoch is not None

    def age_minutes(self, now_epoch: int | None = None) -> int | None:
        if self.last_success_epoch is None:
            return None
        now = now_epoch if now_epoch is not None else epoch_now()
        delay = int((now - self.last_success_epoch) / 60)
        return max(delay, 0)


def heartbeat_file(heartbeat_dir: Path, job_name: str) -> Path:
    return heartbeat_dir / f"{job_name}.success"


def read_heartbeat(heartbeat_dir: Path, job_name: str) -> HeartbeatStatus:
    target = heartbeat_file(heartbeat_dir, job_name)
    if not target.exists():
        return HeartbeatStatus(job_name=job_name, heartbeat_file=target, last_success_epoch=None)

    raw = target.read_text(encoding="utf-8").strip()
    if not raw.isdigit():
        return HeartbeatStatus(job_name=job_name, heartbeat_file=target, last_success_epoch=None)

    return HeartbeatStatus(job_name=job_name, heartbeat_file=target, last_success_epoch=int(raw))


def write_heartbeat(heartbeat_dir: Path, job_name: str, epoch: int | None = None) -> Path:
    heartbeat_dir.mkdir(parents=True, exist_ok=True)
    target = heartbeat_file(heartbeat_dir, job_name)
    timestamp = epoch if epoch is not None else epoch_now()
    target.write_text(f"{timestamp}\n", encoding="utf-8")
    return target
