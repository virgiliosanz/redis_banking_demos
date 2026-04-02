from __future__ import annotations

import socket
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


def get_status_code(url: str, *, timeout: float = 5.0) -> int:
    request = Request(url, method="GET")
    try:
        with urlopen(request, timeout=timeout) as response:  # nosec B310
            return int(response.status)
    except HTTPError as exc:
        return int(exc.code)
    except (URLError, TimeoutError, socket.timeout):
        return 0
