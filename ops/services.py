from __future__ import annotations

from dataclasses import dataclass
from typing import Iterable

from .config import Settings
from .util.docker import wait_for_container_health


@dataclass(frozen=True)
class ServiceDefinition:
    key: str
    compose_service: str
    container_env_key: str
    container_default: str


SERVICE_DEFINITIONS: tuple[ServiceDefinition, ...] = (
    ServiceDefinition("lb-nginx", "lb-nginx", "CONTAINER_LB_NGINX", "n9-lb-nginx"),
    ServiceDefinition("fe-live", "fe-live", "CONTAINER_FE_LIVE", "n9-fe-live"),
    ServiceDefinition("fe-archive", "fe-archive", "CONTAINER_FE_ARCHIVE", "n9-fe-archive"),
    ServiceDefinition("be-admin", "be-admin", "CONTAINER_BE_ADMIN", "n9-be-admin"),
    ServiceDefinition("db-live", "db-live", "CONTAINER_DB_LIVE", "n9-db-live"),
    ServiceDefinition("db-archive", "db-archive", "CONTAINER_DB_ARCHIVE", "n9-db-archive"),
    ServiceDefinition("elastic", "elastic", "CONTAINER_ELASTIC", "n9-elastic"),
    ServiceDefinition("cron-master", "cron-master", "CONTAINER_CRON_MASTER", "n9-cron-master"),
)


def service_definition(service_key: str) -> ServiceDefinition:
    for row in SERVICE_DEFINITIONS:
        if row.key == service_key:
            return row
    raise KeyError(f"Unknown service key: {service_key}")


def service_keys() -> tuple[str, ...]:
    return tuple(row.key for row in SERVICE_DEFINITIONS)


def compose_service_name(service_key: str) -> str:
    return service_definition(service_key).compose_service


def container_name(settings: Settings, service_key: str) -> str:
    definition = service_definition(service_key)
    return settings.get(definition.container_env_key, definition.container_default) or definition.container_default


def inspect_container_name(settings: Settings, service_key: str) -> str:
    return f"/{container_name(settings, service_key)}"


def inspect_name_map(settings: Settings, service_keys_to_map: Iterable[str] | None = None) -> dict[str, str]:
    keys = tuple(service_keys_to_map) if service_keys_to_map is not None else service_keys()
    return {inspect_container_name(settings, key): key for key in keys}


def wait_for_service_keys(settings: Settings, keys: Iterable[str], *, timeout_seconds: int = 120) -> None:
    for key in keys:
        wait_for_container_health(container_name(settings, key), timeout_seconds=timeout_seconds)
