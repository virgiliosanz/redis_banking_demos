from __future__ import annotations

from pathlib import Path
import time
from typing import IO

from .process import CommandResult, run_command


def compose_command(
    args: list[str],
    *,
    cwd: Path | None = None,
    check: bool = True,
    stdin: IO[bytes] | None = None,
) -> CommandResult:
    return run_command(["docker", "compose", *args], cwd=cwd, check=check, stdin=stdin)


def compose_exec(
    service: str,
    args: list[str],
    *,
    cwd: Path | None = None,
    check: bool = True,
    exec_args: list[str] | None = None,
    stdin: IO[bytes] | None = None,
) -> CommandResult:
    extra = exec_args or []
    return compose_command(["exec", "-T", *extra, service, *args], cwd=cwd, check=check, stdin=stdin)


def service_logs(service: str, *, tail_lines: int, cwd: Path | None = None) -> str:
    result = compose_command(["logs", "--tail", str(tail_lines), service], cwd=cwd, check=False)
    return f"{result.stdout}{result.stderr}"


def inspect_container_health(container: str, *, allow_no_healthcheck: bool = False) -> str:
    result = run_command(
        ["docker", "inspect", "--format", "{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}", container],
        check=False,
    )
    if result.returncode != 0:
        return "missing"
    value = result.stdout.strip()
    if value == "none" and allow_no_healthcheck:
        return "healthy"
    return value or "unknown"


def wait_for_container_health(
    container: str,
    *,
    timeout_seconds: int = 120,
    allow_no_healthcheck: bool = False,
) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        health = inspect_container_health(container, allow_no_healthcheck=allow_no_healthcheck)
        if health == "healthy":
            return
        time.sleep(2)
    raise RuntimeError(f"Container {container} did not become healthy within {timeout_seconds} seconds")
