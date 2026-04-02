from __future__ import annotations

import unittest

from ops.util.thresholds import severity_for_load, severity_from_thresholds


class ThresholdTests(unittest.TestCase):
    def test_severity_from_thresholds_uses_warning_and_critical_bounds(self) -> None:
        self.assertEqual(severity_from_thresholds(10, warning=20, critical=40), "ok")
        self.assertEqual(severity_from_thresholds(20, warning=20, critical=40), "warning")
        self.assertEqual(severity_from_thresholds(40, warning=20, critical=40), "critical")

    def test_severity_for_load_uses_cpu_relative_thresholds(self) -> None:
        self.assertEqual(severity_for_load(4.0, logical_cpus=4), "ok")
        self.assertEqual(severity_for_load(4.1, logical_cpus=4), "warning")
        self.assertEqual(severity_for_load(6.1, logical_cpus=4), "critical")
        self.assertEqual(severity_for_load(0.1, logical_cpus=0), "ok")


if __name__ == "__main__":
    unittest.main()
