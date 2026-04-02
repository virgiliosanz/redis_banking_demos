from __future__ import annotations

from dataclasses import dataclass
import json
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

from ..config import Settings


@dataclass(frozen=True)
class TelegramConfig:
    enabled: bool
    bot_token: str
    chat_id: str
    thread_id: str | None
    disable_notification: bool
    notify_on_nightly: bool
    notify_on_sentry: bool

    @property
    def is_configured(self) -> bool:
        return bool(self.bot_token and self.chat_id)


def load_telegram_config(settings: Settings) -> TelegramConfig:
    return TelegramConfig(
        enabled=settings.get_bool("TELEGRAM_NOTIFY_ENABLED", False),
        bot_token=settings.get("TELEGRAM_BOT_TOKEN", ""),
        chat_id=settings.get("TELEGRAM_CHAT_ID", ""),
        thread_id=settings.get("TELEGRAM_MESSAGE_THREAD_ID"),
        disable_notification=settings.get_bool("TELEGRAM_DISABLE_NOTIFICATION", False),
        notify_on_nightly=settings.get_bool("TELEGRAM_NOTIFY_ON_NIGHTLY", True),
        notify_on_sentry=settings.get_bool("TELEGRAM_NOTIFY_ON_SENTRY", True),
    )


def _truncate(text: str, *, limit: int = 3500) -> str:
    stripped = text.strip()
    if len(stripped) <= limit:
        return stripped
    return f"{stripped[: limit - 3].rstrip()}..."


def send_message(config: TelegramConfig, text: str) -> dict[str, object]:
    if not config.enabled:
        raise RuntimeError("Telegram notifications are disabled")
    if not config.is_configured:
        raise RuntimeError("Telegram bot token or chat id is missing")

    payload: dict[str, object] = {
        "chat_id": config.chat_id,
        "text": _truncate(text),
        "disable_notification": config.disable_notification,
    }
    if config.thread_id:
        payload["message_thread_id"] = config.thread_id

    body = urlencode(payload).encode("utf-8")
    request = Request(
        f"https://api.telegram.org/bot{config.bot_token}/sendMessage",
        data=body,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )

    try:
        with urlopen(request, timeout=10) as response:  # nosec B310
            raw = response.read().decode("utf-8")
    except HTTPError as exc:  # pragma: no cover - exercised only with live credentials
        detail = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"Telegram API returned HTTP {exc.code}: {detail}") from exc
    except URLError as exc:  # pragma: no cover - network dependent
        raise RuntimeError(f"Unable to reach Telegram API: {exc}") from exc

    payload = json.loads(raw)
    if not payload.get("ok"):
        raise RuntimeError(f"Telegram API rejected the message: {payload}")
    return payload
