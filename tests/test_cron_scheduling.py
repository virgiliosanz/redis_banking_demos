from __future__ import annotations

from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from ops.config import Settings
from ops.scheduling.cron import remove_nightly_auditor_crontab, render_cleanup_block, render_sync_jobs_block


class CronSchedulingTests(unittest.TestCase):
    def test_render_sync_jobs_block_uses_documented_defaults(self) -> None:
        settings = Settings(config_file=Path("/tmp/ia-ops.env"), values={"PROJECT_ROOT": "."})

        block = render_sync_jobs_block(settings, project_root=Path("/srv/project"), python_bin="/usr/bin/python3")

        self.assertIn("# BEGIN NUEVECUATROUNO_IA_OPS_SYNC", block)
        self.assertIn("15 4 * * * cd /srv/project && IA_OPS_CONFIG_FILE=", block)
        self.assertIn("/usr/bin/python3 -m ops.cli.ia_ops sync-editorial-users --mode apply", block)
        self.assertIn("45 4 * * * cd /srv/project && IA_OPS_CONFIG_FILE=", block)
        self.assertIn("/usr/bin/python3 -m ops.cli.ia_ops sync-platform-config --mode apply", block)
        self.assertIn("/srv/project/runtime/reports/sync/editorial-sync.cron.log", block)
        self.assertIn("/srv/project/runtime/reports/sync/platform-sync.cron.log", block)

    def test_remove_nightly_auditor_crontab_clears_user_crontab_when_managed_block_is_only_content(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            project_root = Path(tmpdir)
            settings = Settings(config_file=project_root / "ia-ops.env", values={"PROJECT_ROOT": "."})
            existing = "\n".join(
                [
                    "# BEGIN NUEVECUATROUNO_IA_OPS_NIGHTLY",
                    "SHELL=/bin/sh",
                    "PATH=/usr/bin:/bin",
                    "15 5 * * * /usr/bin/true",
                    "# END NUEVECUATROUNO_IA_OPS_NIGHTLY",
                    "",
                ]
            )

            with patch("ops.scheduling.cron.read_user_crontab", return_value=existing), patch(
                "ops.scheduling.cron._remove_user_crontab"
            ) as remove_crontab, patch("ops.scheduling.cron._install_crontab_file") as install_crontab:
                crontab_file = remove_nightly_auditor_crontab(settings, project_root=project_root)

            self.assertTrue(crontab_file.name.startswith("crontab-remove-"))
            self.assertEqual(crontab_file.read_text(encoding="utf-8"), "")
            remove_crontab.assert_called_once_with()
            install_crontab.assert_not_called()


    def test_render_cleanup_block_uses_defaults(self) -> None:
        settings = Settings(config_file=Path("/tmp/ia-ops.env"), values={"PROJECT_ROOT": "."})

        block = render_cleanup_block(settings, project_root=Path("/srv/project"), python_bin="/usr/bin/python3")

        self.assertIn("# BEGIN NUEVECUATROUNO_IA_OPS_CLEANUP", block)
        self.assertIn("# END NUEVECUATROUNO_IA_OPS_CLEANUP", block)
        self.assertIn("0 3 * * * cd /srv/project && IA_OPS_CONFIG_FILE=", block)
        self.assertIn("/usr/bin/python3 -m ops.cli.ia_ops cleanup-data", block)
        self.assertIn("cleanup-data.cron.log", block)

    def test_render_cleanup_block_custom_schedule(self) -> None:
        settings = Settings(
            config_file=Path("/tmp/ia-ops.env"),
            values={"PROJECT_ROOT": ".", "CLEANUP_CRON_HOUR": "4", "CLEANUP_CRON_MINUTE": "30"},
        )

        block = render_cleanup_block(settings, project_root=Path("/srv/project"))

        self.assertIn("30 4 * * *", block)


if __name__ == "__main__":
    unittest.main()
