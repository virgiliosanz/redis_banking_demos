from __future__ import annotations

import subprocess
import unittest
from unittest import mock

from ops.util.process import CommandResult, run_command


class CommandResultTests(unittest.TestCase):
    def test_json_parses_stdout(self) -> None:
        result = CommandResult(args=["echo"], returncode=0, stdout='{"key": "value"}', stderr="")
        self.assertEqual(result.json(), {"key": "value"})

    def test_json_raises_on_invalid_json(self) -> None:
        result = CommandResult(args=["echo"], returncode=0, stdout="not json", stderr="")
        with self.assertRaises(Exception):
            result.json()


class RunCommandTests(unittest.TestCase):
    def test_run_command_captures_stdout(self) -> None:
        result = run_command(["echo", "hello"])
        self.assertEqual(result.returncode, 0)
        self.assertEqual(result.stdout.strip(), "hello")

    def test_run_command_raises_on_failure_when_check_true(self) -> None:
        with self.assertRaises(subprocess.CalledProcessError) as ctx:
            run_command(["false"])
        self.assertNotEqual(ctx.exception.returncode, 0)

    def test_run_command_returns_result_on_failure_when_check_false(self) -> None:
        result = run_command(["false"], check=False)
        self.assertNotEqual(result.returncode, 0)

    def test_run_command_passes_cwd(self) -> None:
        result = run_command(["pwd"], cwd="/tmp")
        self.assertIn("/tmp", result.stdout.strip())


if __name__ == "__main__":
    unittest.main()
