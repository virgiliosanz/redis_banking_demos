from __future__ import annotations

import unittest

from ops.runtime.drift import _compare_editorial_snapshots, _compare_platform_snapshots, format_drift_summary


class DriftRuntimeTests(unittest.TestCase):
    def test_compare_editorial_snapshots_summarizes_login_and_field_changes(self) -> None:
        live = {
            "site": "https://live.test",
            "excluded_logins": ["n9liveadmin", "n9archiveadmin"],
            "users": [
                {"login": "alice", "email": "alice-live@example.com", "display_name": "Alice", "nicename": "alice", "status": 0, "roles": ["editor"], "caps_hash": "a", "password_hash_digest": "p1"},
                {"login": "bob", "email": "bob@example.com", "display_name": "Bob", "nicename": "bob", "status": 0, "roles": ["author"], "caps_hash": "b", "password_hash_digest": "p2"},
            ],
        }
        archive = {
            "site": "https://live.test",
            "excluded_logins": ["n9liveadmin", "n9archiveadmin"],
            "users": [
                {"login": "alice", "email": "alice-archive@example.com", "display_name": "Alice", "nicename": "alice", "status": 0, "roles": ["editor"], "caps_hash": "a", "password_hash_digest": "p1"},
                {"login": "carol", "email": "carol@example.com", "display_name": "Carol", "nicename": "carol", "status": 0, "roles": ["author"], "caps_hash": "c", "password_hash_digest": "p3"},
            ],
        }

        section = _compare_editorial_snapshots(live, archive)

        self.assertEqual(section.status, "yes")
        self.assertEqual(section.details["only_in_live_logins"], ["bob"])
        self.assertEqual(section.details["only_in_archive_logins"], ["carol"])
        self.assertEqual(section.details["changed_users"], [{"login": "alice", "changed_fields": ["email"]}])
        self.assertEqual(format_drift_summary(section), "1 login solo en live, 1 login solo en archive, 1 usuario cambiado(s)")

    def test_compare_platform_snapshots_summarizes_plugins_and_hashes(self) -> None:
        live = {
            "site": "https://live.test",
            "stylesheet": "child-a",
            "template": "parent-a",
            "active_plugins": ["a/a.php", "b/b.php"],
            "allowlist_option_names": ["sidebars_widgets", "nav_menu_locations", "theme_mods_child-a"],
            "theme_mods_hash": "hash-1",
            "sidebars_widgets_hash": "hash-2",
            "nav_menu_locations_hash": "hash-3",
        }
        archive = {
            "site": "https://archive.test",
            "stylesheet": "child-a",
            "template": "parent-b",
            "active_plugins": ["a/a.php", "c/c.php"],
            "allowlist_option_names": ["sidebars_widgets", "nav_menu_locations", "theme_mods_child-a"],
            "theme_mods_hash": "hash-9",
            "sidebars_widgets_hash": "hash-2",
            "nav_menu_locations_hash": "hash-8",
        }

        section = _compare_platform_snapshots(live, archive)

        self.assertEqual(section.status, "yes")
        self.assertEqual(section.details["scalar_mismatches"], ["site", "template"])
        self.assertEqual(section.details["active_plugins_only_in_live"], ["b/b.php"])
        self.assertEqual(section.details["active_plugins_only_in_archive"], ["c/c.php"])
        self.assertEqual(section.details["hash_mismatches"], ["theme_mods_hash", "nav_menu_locations_hash"])
        self.assertEqual(format_drift_summary(section), "2 campos base, 1 plugin solo en live, 1 plugin solo en archive, 2 hashes distinto(s)")


if __name__ == "__main__":
    unittest.main()
