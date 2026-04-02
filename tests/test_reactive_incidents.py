from __future__ import annotations

import unittest

from ops.runtime.incidents import build_reactive_incidents


def _base_context() -> dict[str, object]:
    return {
        "service_map": {
            "/n9-lb-nginx": "lb-nginx",
            "/n9-be-admin": "be-admin",
            "/n9-db-live": "db-live",
            "/n9-db-archive": "db-archive",
            "/n9-elastic": "elastic",
            "/n9-cron-master": "cron-master",
        },
        "host": {
            "checks": {
                "docker_daemon": {"status": "ok"},
                "memory": {"status": "ok", "used_pct": 20},
                "disk": {"status": "ok", "used_pct": 30},
                "load_average": {"status": "ok", "load_1": 0.5},
                "iowait": {"status": "not_supported", "pct": None},
            }
        },
        "runtime": {
            "containers": [
                {"container_name": "/n9-lb-nginx", "health_status": "healthy"},
                {"container_name": "/n9-be-admin", "health_status": "healthy"},
                {"container_name": "/n9-db-live", "health_status": "healthy"},
                {"container_name": "/n9-db-archive", "health_status": "healthy"},
                {"container_name": "/n9-elastic", "health_status": "healthy"},
                {"container_name": "/n9-cron-master", "health_status": "healthy"},
            ],
            "checks": {
                "lb_nginx_recent_4xx": {"status": "ok", "count": 0},
                "lb_nginx_recent_5xx": {"status": "ok", "count": 0},
            },
        },
        "app": {
            "checks": {
                "live_login": {"status": "ok", "http_code": 200},
                "archive_login": {"status": "ok", "http_code": 200},
                "unified_search_endpoint": {"status": "ok", "http_code": 200},
                "smoke_scripts": [
                    {"name": "routing", "status": "ok"},
                    {"name": "search", "status": "ok"},
                    {"name": "services", "status": "ok"},
                ],
            }
        },
        "mysql": {
            "databases": [
                {"service": "db-live", "ping": {"status": "ok"}, "processlist": {"status": "ok", "warning_count": 0, "critical_count": 0}},
                {"service": "db-archive", "ping": {"status": "ok"}, "processlist": {"status": "ok", "warning_count": 0, "critical_count": 0}},
            ]
        },
        "elastic": {"alias": {"status": "ok"}, "cluster_health": {"status": "green"}},
        "cron": {
            "jobs": [
                {"job_name": "sync-editorial-users", "status": "ok"},
                {"job_name": "sync-platform-config", "status": "ok"},
            ],
            "recent_log_errors": {"status": "ok", "count": 0},
        },
    }


class ReactiveIncidentsTests(unittest.TestCase):
    def test_build_reactive_incidents_returns_empty_for_nominal_context(self) -> None:
        incidents = build_reactive_incidents(_base_context())
        self.assertEqual(incidents, [])

    def test_build_reactive_incidents_covers_host_app_mysql_and_drift(self) -> None:
        context = _base_context()
        context["host"]["checks"]["memory"] = {"status": "critical", "used_pct": 95}
        context["app"]["checks"]["archive_login"] = {"status": "critical", "http_code": 500}
        context["app"]["checks"]["smoke_scripts"][0]["status"] = "critical"
        context["mysql"]["databases"][0]["processlist"] = {"status": "warning", "warning_count": 2, "critical_count": 0}
        context["cron"]["recent_log_errors"] = {"status": "warning", "count": 2}

        incidents = build_reactive_incidents(context, editorial_drift="yes", platform_drift="no")
        keys = {incident.key for incident in incidents}

        self.assertIn("host:memory:critical", keys)
        self.assertIn("be-admin:archive-login:critical", keys)
        self.assertIn("lb-nginx:smoke:routing", keys)
        self.assertIn("db-live:processlist:warning", keys)
        self.assertIn("cron-master:logs:warning", keys)
        self.assertIn("cron-master:drift:yes:no", keys)


if __name__ == "__main__":
    unittest.main()
