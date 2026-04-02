from __future__ import annotations

import unittest
from unittest import mock

from ops.collectors import host as host_collector
from ops.config import Settings


class HostCollectorTests(unittest.TestCase):
    def test_parse_darwin_memory_pressure_prefers_system_free_percentage(self) -> None:
        output = """
The system has 17179869184 (1048576 pages with a page size of 16384).
System-wide memory free percentage: 42%
"""
        self.assertEqual(host_collector._parse_darwin_memory_pressure(output), 58.0)

    def test_collect_on_darwin_uses_memory_pressure_when_available(self) -> None:
        settings = Settings(config_file=__file__, values={"PROJECT_ROOT": ".", "HOST_MEMORY_WARNING_PCT": "85", "HOST_MEMORY_CRITICAL_PCT": "92"})

        def fake_run_command(args: list[str], check: bool = True):
            class Result:
                def __init__(self, stdout: str, returncode: int = 0):
                    self.stdout = stdout
                    self.stderr = ""
                    self.returncode = returncode

            if args == ["docker", "info"]:
                return Result("", 0)
            if args == ["sysctl", "-n", "hw.memsize"]:
                return Result("17179869184\n")
            if args == ["memory_pressure"]:
                return Result("System-wide memory free percentage: 42%\n", 0)
            if args == ["iostat", "-w", "1", "-c", "2"]:
                return Result("us sy id load\n1 2 97 0.50 0.40 0.30\n")
            raise AssertionError(f"unexpected command: {args}")

        with mock.patch("ops.collectors.host.platform.system", return_value="Darwin"):
            with mock.patch("ops.collectors.host.run_command", side_effect=fake_run_command):
                with mock.patch("ops.collectors.host.os.cpu_count", return_value=8):
                    with mock.patch("ops.collectors.host.shutil.disk_usage") as disk_usage:
                        disk_usage.return_value = mock.Mock(total=100, free=40)
                        payload = host_collector.collect(settings)

        self.assertEqual(payload["checks"]["memory"]["used_pct"], 58.0)
        self.assertEqual(payload["checks"]["memory"]["status"], "ok")
        self.assertEqual(payload["checks"]["memory"]["source"], "memory_pressure")
        self.assertEqual(payload["checks"]["memory"]["thresholds"], {"warning": 85, "critical": 92})
        self.assertEqual(payload["checks"]["docker_daemon"]["source"], "docker info")


if __name__ == "__main__":
    unittest.main()
