#!/bin/sh
set -eu

check_container_health() {
  container="$1"
  expected="$2"

  status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container")"
  echo "==> $container: $status"
  [ "$status" = "$expected" ]
}

check_container_health n9-lb-nginx healthy
check_container_health n9-fe-live healthy
check_container_health n9-fe-archive healthy
check_container_health n9-be-admin healthy
check_container_health n9-db-live healthy
check_container_health n9-db-archive healthy
check_container_health n9-elastic healthy
check_container_health n9-cron-master healthy

echo "==> mysql live ping"
docker exec n9-db-live sh -lc 'mysqladmin ping -h 127.0.0.1 -uroot -p"$(cat /run/secrets/db_live_mysql_root_password)" --silent'

echo "==> mysql archive ping"
docker exec n9-db-archive sh -lc 'mysqladmin ping -h 127.0.0.1 -uroot -p"$(cat /run/secrets/db_archive_mysql_root_password)" --silent'

echo "==> elastic cluster health"
docker exec n9-elastic sh -lc 'curl -fsS http://127.0.0.1:9200/_cluster/health >/dev/null'

echo "==> compose ps"
docker compose ps
