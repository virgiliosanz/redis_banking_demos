from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import subprocess
from typing import IO, Mapping, Sequence

from .jsonio import loads_json


@dataclass(frozen=True)
class CommandResult:
    args: Sequence[str]
    returncode: int
    stdout: str
    stderr: str

    def json(self) -> object:
        return loads_json(self.stdout)


def run_command(
    args: Sequence[str],
    *,
    cwd: Path | None = None,
    env: Mapping[str, str] | None = None,
    check: bool = True,
    input: str | None = None,
    stdin: IO[bytes] | None = None,
) -> CommandResult:
    # capture_output is incompatible with explicit stdin file handles;
    # when stdin is provided we set stdout/stderr PIPE explicitly and
    # disable text mode (binary stdin would conflict with text=True on
    # the same descriptor).
    if stdin is not None:
        completed = subprocess.run(
            args,
            cwd=str(cwd) if cwd else None,
            env=dict(env) if env else None,
            stdin=stdin,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=False,
            check=False,
        )
        result = CommandResult(
            args=args,
            returncode=completed.returncode,
            stdout=completed.stdout.decode() if completed.stdout else "",
            stderr=completed.stderr.decode() if completed.stderr else "",
        )
    else:
        completed = subprocess.run(
            args,
            cwd=str(cwd) if cwd else None,
            env=dict(env) if env else None,
            input=input,
            capture_output=True,
            text=True,
            check=False,
        )
        result = CommandResult(
            args=args,
            returncode=completed.returncode,
            stdout=completed.stdout,
            stderr=completed.stderr,
        )
    if check and result.returncode != 0:
        raise subprocess.CalledProcessError(
            result.returncode,
            list(args),
            output=result.stdout,
            stderr=result.stderr,
        )
    return result
