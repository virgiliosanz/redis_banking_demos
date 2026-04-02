from __future__ import annotations

from datetime import datetime, timezone
import unittest
from unittest import mock

from ops.collectors import runtime as runtime_collector
from ops.config import Settings


class RuntimeCollectorTests(unittest.TestCase):
    def test_nginx_status_count_filters_by_window(self) -> None:
        log_tail = "\n".join(
            [
                'n9-lb-nginx  | 192.168.65.1 - - [02/Apr/2026:20:48:00 +0000] "GET / HTTP/1.1" 504 160 request_id="a"',
                'n9-lb-nginx  | 192.168.65.1 - - [02/Apr/2026:20:30:00 +0000] "GET / HTTP/1.1" 504 160 request_id="b"',
                'n9-lb-nginx  | 192.168.65.1 - - [02/Apr/2026:20:49:00 +0000] "GET /missing HTTP/1.1" 404 160 request_id="c"',
            ]
        )

        now = datetime(2026, 4, 2, 20, 52, 0, tzinfo=timezone.utc)

        self.assertEqual(runtime_collector._nginx_status_count(log_tail, status_prefix="5", window_minutes=15, now=now), 1)
        self.assertEqual(runtime_collector._nginx_status_count(log_tail, status_prefix="4", window_minutes=15, now=now), 1)

    def test_collect_includes_health_urls_and_log_sources(self) -> None:
        settings = Settings(config_file=__file__, values={"PROJECT_ROOT": ".", "BASE_URL": "http://live.test", "ARCHIVE_URL": "http://archive.test"})
        log_tail = 'n9-lb-nginx  | 192.168.65.1 - - [02/Apr/2026:20:48:00 +0000] "GET / HTTP/1.1" 504 160 request_id="a"'

        with mock.patch("ops.collectors.runtime.service_keys", return_value=["lb-nginx"]), mock.patch(
            "ops.collectors.runtime.container_name", return_value="/n9-lb-nginx"
        ), mock.patch("ops.collectors.runtime._inspect_container", return_value={"container_name": "/n9-lb-nginx", "health_status": "healthy"}), mock.patch(
            "ops.collectors.runtime.get_status_code", side_effect=[200, 200]
        ), mock.patch("ops.collectors.runtime.service_logs", return_value=log_tail):
            payload = runtime_collector.collect(settings)

        self.assertEqual(payload["checks"]["live_healthz"]["url"], "http://live.test/healthz")
        self.assertEqual(payload["checks"]["live_healthz"]["expected_http_code"], 200)
        self.assertEqual(payload["checks"]["lb_nginx_recent_5xx"]["source"], "lb-nginx logs")


if __name__ == "__main__":
    unittest.main()
