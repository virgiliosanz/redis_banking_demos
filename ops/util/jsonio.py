from __future__ import annotations

import json
from pathlib import Path
from typing import Any


def dumps_pretty(payload: Any) -> str:
    return json.dumps(payload, indent=2, sort_keys=True, ensure_ascii=True)


def dump_json(path: Path, payload: Any) -> Path:
    path.write_text(dumps_pretty(payload) + "\n", encoding="utf-8")
    return path


def loads_json(raw: str) -> Any:
    return json.loads(raw)
