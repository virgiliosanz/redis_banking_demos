from __future__ import annotations

import unittest

from ops.runtime.nightly import assess_nightly_context, render_nightly_report, render_nightly_telegram_message


def _base_context() -> dict[str, object]:
    return {
        "generated_at": "2026-04-02T20:00:00Z",
        "host": {
            "checks": {
                "docker_daemon": {"status": "ok"},
                "memory": {"status": "ok", "used_pct": 35},
                "disk": {"status": "ok", "used_pct": 55},
                "load_average": {"status": "ok", "load_1": 0.5},
                "iowait": {"status": "ok", "pct": 1.0},
            }
        },
        "runtime": {
            "checks": {
                "lb_nginx_recent_4xx": {"status": "ok", "count": 0},
                "lb_nginx_recent_5xx": {"status": "ok", "count": 0},
            },
            "containers": [],
        },
        "app": {
            "checks": {
                "smoke_scripts": [
                    {"name": "routing", "status": "ok"},
                    {"name": "search", "status": "ok"},
                ]
            }
        },
        "mysql": {
            "databases": [
                {
                    "service": "db-live",
                    "ping": {"status": "ok"},
                    "processlist": {"status": "ok", "warning_count": 0, "critical_count": 0},
                },
                {
                    "service": "db-archive",
                    "ping": {"status": "ok"},
                    "processlist": {"status": "ok", "warning_count": 0, "critical_count": 0},
                },
            ]
        },
        "elastic": {"alias": {"status": "ok"}},
        "cron": {"jobs": []},
    }


class NightlyRuntimeTests(unittest.TestCase):
    def test_assess_nightly_context_reports_nominal_state(self) -> None:
        assessment = assess_nightly_context(
            _base_context(),
            drift_report_file="/tmp/drift.md",
            editorial_drift="no",
            platform_drift="no",
        )

        self.assertEqual(assessment.severity, "info")
        self.assertEqual(assessment.summary, "plataforma sana sin hallazgos relevantes")
        self.assertEqual(assessment.risks, ["- Sin riesgos adicionales fuera de los checks ya reflejados."])
        self.assertEqual(
            assessment.actions,
            ["- Sin accion inmediata; mantener la observacion diaria y repetir smokes tras cambios de runtime."],
        )

    def test_assess_nightly_context_collects_risks_and_actions(self) -> None:
        context = _base_context()
        context["host"]["checks"]["memory"] = {"status": "critical", "used_pct": 94}
        context["runtime"]["checks"]["lb_nginx_recent_5xx"] = {"status": "warning", "count": 5}
        context["app"]["checks"]["smoke_scripts"][1]["status"] = "critical"
        context["cron"]["jobs"] = [{"job_name": "sync-platform-config", "status": "warning"}]

        assessment = assess_nightly_context(
            context,
            drift_report_file="/tmp/drift.md",
            editorial_drift="yes",
            platform_drift="no",
            editorial_drift_summary=["- only_in_live_logins: alice", "- changed_users: alice(email)"],
            editorial_drift_brief="1 login solo en live, 0 logins solo en archive, 1 usuario cambiado(s)",
        )

        self.assertEqual(assessment.severity, "critical")
        self.assertIn("- Memoria del host en umbral critico; el laboratorio puede falsear otros sintomas por presion local.", assessment.risks)
        self.assertIn("- Existen respuestas 5xx recientes en lb-nginx.", assessment.risks)
        self.assertIn("- Hay smokes fallidos: search.", assessment.risks)
        self.assertIn("- Hay jobs de cron fuera de ventana: sync-platform-config.", assessment.risks)
        self.assertIn("- Existe drift entre live y archive que puede invalidar operaciones anuales o tareas editoriales.", assessment.risks)
        self.assertIn("- Drift editorial resumido: 1 login solo en live, 0 logins solo en archive, 1 usuario cambiado(s).", assessment.risks)

    def test_render_nightly_outputs_report_and_telegram_message(self) -> None:
        assessment = assess_nightly_context(
            _base_context(),
            drift_report_file="/tmp/drift.md",
            editorial_drift="no",
            platform_drift="no",
        )

        report = render_nightly_report(assessment)
        telegram = render_nightly_telegram_message(assessment, report_file="/tmp/nightly.md")

        self.assertIn("# Nightly Auditor", report)
        self.assertIn("- drift_report: /tmp/drift.md", report)
        self.assertIn("[Nightly Auditor][INFO]", telegram)
        self.assertIn("report: /tmp/nightly.md", telegram)


if __name__ == "__main__":
    unittest.main()
