"""Admin panel configuration."""

from __future__ import annotations

import os


ADMIN_PORT = int(os.environ.get("ADMIN_PORT", "9941"))
ADMIN_HOST = os.environ.get("ADMIN_HOST", "127.0.0.1")
DEBUG = os.environ.get("ADMIN_DEBUG", "1") == "1"
