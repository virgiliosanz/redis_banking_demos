from __future__ import annotations

from pathlib import Path
import unittest
from unittest import mock

from ops.config import Settings
from ops.services import compose_service_name, container_name, inspect_container_name, inspect_name_map, wait_for_service_keys


class ServicesTests(unittest.TestCase):
    def setUp(self) -> None:
        self.settings = Settings(
            config_file=Path("config/ia-ops-sources.env.example"),
            values={
                "PROJECT_ROOT": ".",
                "CONTAINER_LB_NGINX": "custom-lb",
                "CONTAINER_DB_LIVE": "custom-db-live",
            },
        )

    def test_compose_service_name_uses_stable_service_keys(self) -> None:
        self.assertEqual(compose_service_name("cron-master"), "cron-master")
        self.assertEqual(compose_service_name("elastic"), "elastic")

    def test_container_name_honours_overrides(self) -> None:
        self.assertEqual(container_name(self.settings, "lb-nginx"), "custom-lb")
        self.assertEqual(container_name(self.settings, "db-live"), "custom-db-live")
        self.assertEqual(container_name(self.settings, "db-archive"), "n9-db-archive")

    def test_inspect_helpers_build_prefixed_container_names(self) -> None:
        self.assertEqual(inspect_container_name(self.settings, "lb-nginx"), "/custom-lb")
        mapping = inspect_name_map(self.settings, ("lb-nginx", "db-archive"))
        self.assertEqual(mapping, {"/custom-lb": "lb-nginx", "/n9-db-archive": "db-archive"})

    def test_wait_for_service_keys_uses_container_names(self) -> None:
        with mock.patch("ops.services.wait_for_container_health") as mocked:
            wait_for_service_keys(self.settings, ("lb-nginx", "db-archive"), timeout_seconds=30)

        mocked.assert_any_call("custom-lb", timeout_seconds=30)
        mocked.assert_any_call("n9-db-archive", timeout_seconds=30)
        self.assertEqual(mocked.call_count, 2)


if __name__ == "__main__":
    unittest.main()
