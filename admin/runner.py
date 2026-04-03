"""Reusable command runner helper.

Executes CLI commands via subprocess and captures output (stdout + stderr).
"""

from __future__ import annotations

import subprocess
from dataclasses import dataclass


@dataclass(frozen=True)
class CommandResult:
    """Result of a CLI command execution."""

    command: list[str]
    returncode: int
    stdout: str
    stderr: str

    @property
    def success(self) -> bool:
        return self.returncode == 0

    @property
    def output(self) -> str:
        """Combined stdout and stderr for display."""
        parts: list[str] = []
        if self.stdout:
            parts.append(self.stdout)
        if self.stderr:
            parts.append(self.stderr)
        return "\n".join(parts)


def run_cli(args: list[str], *, timeout: int = 120, cwd: str | None = None) -> CommandResult:
    """Run a CLI command and return the captured result.

    Parameters
    ----------
    args:
        Command and arguments as a list of strings.
    timeout:
        Maximum seconds to wait for the command to finish.
    cwd:
        Working directory for the command.  When *None* the current
        process working directory is used.

    Returns
    -------
    CommandResult with captured stdout, stderr and return code.
    """
    try:
        proc = subprocess.run(
            args,
            capture_output=True,
            text=True,
            timeout=timeout,
            cwd=cwd,
        )
        return CommandResult(
            command=args,
            returncode=proc.returncode,
            stdout=proc.stdout,
            stderr=proc.stderr,
        )
    except subprocess.TimeoutExpired:
        return CommandResult(
            command=args,
            returncode=-1,
            stdout="",
            stderr=f"Command timed out after {timeout}s",
        )
    except FileNotFoundError:
        return CommandResult(
            command=args,
            returncode=-1,
            stdout="",
            stderr=f"Command not found: {args[0] if args else '(empty)'}",
        )
