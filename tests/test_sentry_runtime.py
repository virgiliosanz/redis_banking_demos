from __future__ import annotations

import unittest

from ops.runtime.sentry import diagnose_sentry_service, render_sentry_report, render_sentry_telegram_message


def _base_context() -> dict[str, object]:
    return {
        "generated_at": "2026-04-02T20:00:00Z",
        "host": {
            "checks": {
                "docker_daemon": {"status": "ok"},
                "memory": {"status": "ok", "used_pct": 40},
                "disk": {"status": "ok", "used_pct": 50},
                "load_average": {"status": "ok", "load_1": 0.3},
                "iowait": {"status": "ok", "pct": 0.5},
            }
        },
        "runtime": {
            "checks": {
                "lb_nginx_recent_4xx": {"status": "ok", "count": 0, "warning_threshold": 20},
                "lb_nginx_recent_5xx": {"status": "ok", "count": 0},
            },
            "containers": [],
        },
        "app": {
            "checks": {
                "live_login": {"status": "ok", "http_code": 200},
                "archive_login": {"status": "ok", "http_code": 200},
                "unified_search_endpoint": {"status": "ok"},
                "smoke_scripts": [
                    {"name": "routing", "status": "ok"},
                    {"name": "search", "status": "ok"},
                ],
            }
        },
        "mysql": {
            "databases": [
                {
                    "service": "db-live",
                    "ping": {"status": "ok"},
                    "processlist": {"status": "ok", "warning_count": 0, "critical_count": 0, "queries": []},
                },
                {
                    "service": "db-archive",
                    "ping": {"status": "ok"},
                    "processlist": {"status": "ok", "warning_count": 0, "critical_count": 0, "queries": []},
                },
            ]
        },
        "elastic": {"alias": {"status": "ok"}, "cluster_health": {"status": "green"}},
        "cron": {
            "jobs": [],
            "recent_log_errors": {"status": "ok", "count": 0},
        },
    }


class SentryRuntimeTests(unittest.TestCase):
    def test_diagnose_sentry_service_reports_host_pressure(self) -> None:
        context = _base_context()
        context["host"]["checks"]["memory"] = {"status": "critical", "used_pct": 97}

        diagnosis = diagnose_sentry_service("host", context)

        self.assertEqual(diagnosis.severity, "critical")
        self.assertEqual(diagnosis.summary, "host con umbrales criticos de recursos")
        self.assertIn("- docker_daemon_status: ok", diagnosis.evidence)
        self.assertIn("- liberar presion local o recuperar Docker antes de continuar con diagnostico de plataforma", diagnosis.actions)

    def test_diagnose_sentry_service_covers_cron_and_database_cases(self) -> None:
        context = _base_context()
        context["cron"]["jobs"] = [{"job_name": "sync-editorial-users", "status": "warning"}]
        context["mysql"]["databases"][0]["processlist"] = {
            "status": "warning",
            "warning_count": 2,
            "critical_count": 0,
            "queries": [{"id": 123}],
        }

        cron_diagnosis = diagnose_sentry_service(
            "cron-master",
            context,
            container_health="healthy",
            drift_report_file="/tmp/drift.md",
            editorial_drift="yes",
            platform_drift="no",
            editorial_drift_summary=["- only_in_live_logins: alice", "- changed_users: alice(email)"],
            platform_drift_summary=["- hash_mismatches: none"],
            editorial_drift_brief="1 login solo en live, 0 logins solo en archive, 1 usuario cambiado(s)",
            platform_drift_brief="sin diferencias de plataforma",
        )
        db_diagnosis = diagnose_sentry_service("db-live", context, container_health="healthy")

        self.assertEqual(cron_diagnosis.severity, "warning")
        self.assertIn("- delayed_jobs: sync-editorial-users", cron_diagnosis.evidence)
        self.assertIn("- drift_report: /tmp/drift.md", cron_diagnosis.evidence)
        self.assertIn("- editorial_drift_brief: 1 login solo en live, 0 logins solo en archive, 1 usuario cambiado(s)", cron_diagnosis.evidence)
        self.assertEqual(db_diagnosis.severity, "warning")
        self.assertIn("- candidate_query_ids: 123", db_diagnosis.evidence)

    def test_render_sentry_outputs_report_and_telegram_message(self) -> None:
        diagnosis = diagnose_sentry_service("lb-nginx", _base_context(), container_health="healthy")

        report = render_sentry_report(diagnosis)
        telegram = render_sentry_telegram_message(diagnosis, report_file="/tmp/sentry.md")

        self.assertIn("# Sentry Agent", report)
        self.assertIn("## Logs acotados", report)
        self.assertIn("[Sentry Agent][INFO]", telegram)
        self.assertIn("report: /tmp/sentry.md", telegram)


    def test_diagnose_sentry_unknown_service_unhealthy(self) -> None:
        diagnosis = diagnose_sentry_service("fe-live", _base_context(), container_health="unhealthy")
        self.assertEqual(diagnosis.severity, "critical")
        self.assertIn("fe-live no esta sano", diagnosis.summary)

    def test_diagnose_sentry_unknown_service_healthy_no_logs(self) -> None:
        diagnosis = diagnose_sentry_service("fe-live", _base_context(), container_health="healthy")
        self.assertEqual(diagnosis.severity, "info")

    def test_diagnose_sentry_unknown_service_with_logs(self) -> None:
        diagnosis = diagnose_sentry_service("fe-live", _base_context(), container_health="healthy", service_logs="ERROR: something")
        self.assertEqual(diagnosis.severity, "warning")

    def test_diagnose_sentry_elastic_alias_missing(self) -> None:
        context = _base_context()
        context["elastic"]["alias"]["status"] = "critical"
        diagnosis = diagnose_sentry_service("elastic", context, container_health="healthy")
        self.assertEqual(diagnosis.severity, "critical")


if __name__ == "__main__":
    unittest.main()
