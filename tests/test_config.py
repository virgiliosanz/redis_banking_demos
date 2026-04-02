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


    def test_load_settings_handles_malformed_lines(self) -> None:
        with TemporaryDirectory() as tmp:
            config_file = Path(tmp) / "ia-ops.env"
            config_file.write_text(
                "\n".join(
                    [
                        "VALID_KEY=valid_value",
                        "no_equals_sign",
                        "",
                        "   ",
                        "# pure comment",
                        "ANOTHER_KEY=",
                        "KEY_WITH_EQUALS=value=with=equals",
                    ]
                ),
                encoding="utf-8",
            )
            settings = load_settings(str(config_file))

            self.assertEqual(settings.get("VALID_KEY"), "valid_value")
            self.assertIsNone(settings.get("ANOTHER_KEY"))  # empty value treated as absent
            self.assertEqual(settings.get("KEY_WITH_EQUALS"), "value=with=equals")
            self.assertIsNone(settings.get("no_equals_sign"))

    def test_load_settings_raises_on_missing_explicit_path(self) -> None:
        with self.assertRaises(FileNotFoundError):
            load_settings("/nonexistent/path/to/config.env")

    def test_settings_require_raises_on_missing_key(self) -> None:
        with TemporaryDirectory() as tmp:
            config_file = Path(tmp) / "ia-ops.env"
            config_file.write_text("KEY=value\n", encoding="utf-8")
            settings = load_settings(str(config_file))
            with self.assertRaises(KeyError):
                settings.require("NONEXISTENT_KEY")

    def test_settings_get_int_returns_default_on_empty(self) -> None:
        with TemporaryDirectory() as tmp:
            config_file = Path(tmp) / "ia-ops.env"
            config_file.write_text("EMPTY_KEY=\n", encoding="utf-8")
            settings = load_settings(str(config_file))
            self.assertEqual(settings.get_int("EMPTY_KEY", 42), 42)
            self.assertEqual(settings.get_int("MISSING_KEY", 99), 99)


    def test_get_bool_truthy_and_falsy_values(self) -> None:
        with TemporaryDirectory() as tmp:
            config_file = Path(tmp) / "ia-ops.env"
            config_file.write_text(
                "\n".join([
                    "A=1", "B=true", "C=yes", "D=on",
                    "E=0", "F=false", "G=no", "H=off", "I=random",
                ]),
                encoding="utf-8",
            )
            settings = load_settings(str(config_file))

            for key in ("A", "B", "C", "D"):
                self.assertTrue(settings.get_bool(key), f"{key} should be truthy")
            for key in ("E", "F", "G", "H", "I"):
                self.assertFalse(settings.get_bool(key), f"{key} should be falsy")

    def test_get_int_raises_on_non_numeric(self) -> None:
        with TemporaryDirectory() as tmp:
            config_file = Path(tmp) / "ia-ops.env"
            config_file.write_text("BAD_INT=abc\n", encoding="utf-8")
            settings = load_settings(str(config_file))
            with self.assertRaises(ValueError):
                settings.get_int("BAD_INT", 0)

    def test_load_settings_comments_only_file(self) -> None:
        with TemporaryDirectory() as tmp:
            config_file = Path(tmp) / "ia-ops.env"
            config_file.write_text("# only comments\n# another comment\n\n", encoding="utf-8")
            settings = load_settings(str(config_file))
            self.assertIsNone(settings.get("anything"))

    def test_require_raises_on_empty_value(self) -> None:
        with TemporaryDirectory() as tmp:
            config_file = Path(tmp) / "ia-ops.env"
            config_file.write_text("EMPTY_VAL=\n", encoding="utf-8")
            settings = load_settings(str(config_file))
            with self.assertRaises(KeyError):
                settings.require("EMPTY_VAL")


if __name__ == "__main__":
    unittest.main()
