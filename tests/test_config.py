from __future__ import annotations

import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest import mock

from ops.config import load_settings


class LoadSettingsTests(unittest.TestCase):
    def test_load_settings_reads_file_and_overrides_known_env_keys(self) -> None:
        with TemporaryDirectory() as tmp:
            config_file = Path(tmp) / "ia-ops.env"
            config_file.write_text(
                "\n".join(
                    [
                        "# comment",
                        "PROJECT_ROOT=.",
                        "TELEGRAM_NOTIFY_ENABLED=0",
                        "CUSTOM_VALUE=file-value",
                        "",
                    ]
                ),
                encoding="utf-8",
            )
            with mock.patch.dict(os.environ, {"TELEGRAM_NOTIFY_ENABLED": "1", "UNRELATED_ENV": "ignored"}, clear=False):
                settings = load_settings(str(config_file))

            self.assertEqual(settings.get("CUSTOM_VALUE"), "file-value")
            self.assertTrue(settings.get_bool("TELEGRAM_NOTIFY_ENABLED"))
            self.assertIsNone(settings.get("UNRELATED_ENV"))

    def test_load_settings_keeps_prefixed_ia_ops_env_vars(self) -> None:
        with TemporaryDirectory() as tmp:
            config_file = Path(tmp) / "ia-ops.env"
            config_file.write_text("PROJECT_ROOT=./runtime\n", encoding="utf-8")

            with mock.patch.dict(os.environ, {"IA_OPS_RUNTIME_LABEL": "lab"}, clear=False):
                settings = load_settings(str(config_file))

            self.assertEqual(settings.get("IA_OPS_RUNTIME_LABEL"), "lab")
            self.assertEqual(settings.project_root, Path("./runtime"))


if __name__ == "__main__":
    unittest.main()
