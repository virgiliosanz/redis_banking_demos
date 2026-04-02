from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import os
from typing import Mapping


DEFAULT_CONFIG_CANDIDATES = (
    Path("./config/ia-ops-sources.env"),
    Path("./config/ia-ops-sources.env.example"),
)


def _parse_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def _resolve_config_path(explicit: str | None = None) -> Path:
    if explicit:
        path = Path(explicit)
        if not path.exists():
            raise FileNotFoundError(f"Missing IA-Ops config file: {path}")
        return path

    env_path = os.environ.get("IA_OPS_CONFIG_FILE")
    if env_path:
        path = Path(env_path)
        if not path.exists():
            raise FileNotFoundError(f"Missing IA-Ops config file: {path}")
        return path

    for candidate in DEFAULT_CONFIG_CANDIDATES:
        if candidate.exists():
            return candidate

    raise FileNotFoundError("No IA-Ops config file found in default locations")


@dataclass(frozen=True)
class Settings:
    config_file: Path
    values: Mapping[str, str]

    def get(self, key: str, default: str | None = None) -> str | None:
        return self.values.get(key, default)

    def require(self, key: str) -> str:
        value = self.values.get(key)
        if value is None or value == "":
            raise KeyError(f"Missing required config key: {key}")
        return value

    def get_int(self, key: str, default: int) -> int:
        value = self.values.get(key)
        if value is None or value == "":
            return default
        return int(value)

    def get_bool(self, key: str, default: bool = False) -> bool:
        value = self.values.get(key)
        if value is None or value == "":
            return default
        return value.strip().lower() in {"1", "true", "yes", "on"}

    def get_path(self, key: str, default: str) -> Path:
        value = self.values.get(key, default)
        return Path(value)

    @property
    def project_root(self) -> Path:
        return self.get_path("PROJECT_ROOT", ".")


def load_settings(explicit_path: str | None = None) -> Settings:
    config_file = _resolve_config_path(explicit_path)
    values = _parse_env_file(config_file)
    merged = dict(values)

    # Runtime env vars override file values.
    for key, value in os.environ.items():
        if key in merged or key.startswith("IA_OPS_"):
            merged[key] = value

    return Settings(config_file=config_file, values=merged)
