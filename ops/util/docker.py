from __future__ import annotations

from pathlib import Path

from .process import CommandResult, run_command


def compose_command(args: list[str], *, cwd: Path | None = None, check: bool = True) -> CommandResult:
    return run_command(["docker", "compose", *args], cwd=cwd, check=check)


def compose_exec(service: str, args: list[str], *, cwd: Path | None = None, check: bool = True) -> CommandResult:
    return compose_command(["exec", "-T", service, *args], cwd=cwd, check=check)


def service_logs(service: str, *, tail_lines: int, cwd: Path | None = None) -> str:
    result = compose_command(["logs", "--tail", str(tail_lines), service], cwd=cwd, check=False)
    return f"{result.stdout}{result.stderr}"
