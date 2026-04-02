from __future__ import annotations

import unittest
from unittest import mock

from ops.collectors import elastic as elastic_collector
from ops.config import Settings


class ElasticCollectorTests(unittest.TestCase):
    def test_collect_normalizes_cluster_health_and_alias_metadata(self) -> None:
        settings = Settings(config_file=__file__, values={"PROJECT_ROOT": ".", "EP_SEARCH_ALIAS": "n9-search-posts"})

        with mock.patch(
            "ops.collectors.elastic._curl_json",
            side_effect=[
                {"status": "yellow", "number_of_nodes": 1},
                [{"index": "n9-live"}],
            ],
        ), mock.patch("ops.collectors.elastic.compose_exec") as compose_exec:
            compose_exec.return_value.returncode = 0
            compose_exec.return_value.stdout = '[{"alias":"n9-search-posts","index":"n9-live"}]'
            compose_exec.return_value.json.return_value = [{"alias": "n9-search-posts", "index": "n9-live"}]
            payload = elastic_collector.collect(settings)

        self.assertEqual(payload["cluster_health"]["status"], "yellow")
        self.assertEqual(payload["cluster_health"]["collector_status"], "warning")
        self.assertEqual(payload["alias"]["alias_name"], "n9-search-posts")
        self.assertEqual(payload["alias"]["status"], "ok")


if __name__ == "__main__":
    unittest.main()
