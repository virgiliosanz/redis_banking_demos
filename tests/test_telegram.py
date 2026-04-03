from __future__ import annotations

import json
import unittest
from unittest import mock
from urllib.error import HTTPError, URLError

from ops.notifications.telegram import TelegramConfig, _truncate, load_telegram_config, send_message
from ops.config import Settings
from pathlib import Path


def _config(*, enabled: bool = True, bot_token: str = "test-token", chat_id: str = "123") -> TelegramConfig:
    return TelegramConfig(
        enabled=enabled,
        bot_token=bot_token,
        chat_id=chat_id,
        thread_id=None,
        disable_notification=False,
        notify_on_nightly=True,
        notify_on_sentry=True,
    )


class TelegramConfigTests(unittest.TestCase):
    def test_is_configured_requires_token_and_chat_id(self) -> None:
        self.assertTrue(_config().is_configured)
        self.assertFalse(_config(bot_token="").is_configured)
        self.assertFalse(_config(chat_id="").is_configured)

    def test_load_telegram_config_from_settings(self) -> None:
        settings = Settings(
            config_file=Path("/tmp/fake.env"),
            values={
                "TELEGRAM_NOTIFY_ENABLED": "1",
                "TELEGRAM_BOT_TOKEN": "my-token",
                "TELEGRAM_CHAT_ID": "456",
                "TELEGRAM_MESSAGE_THREAD_ID": "789",
                "TELEGRAM_DISABLE_NOTIFICATION": "true",
            },
        )
        config = load_telegram_config(settings)
        self.assertTrue(config.enabled)
        self.assertEqual(config.bot_token, "my-token")
        self.assertEqual(config.chat_id, "456")
        self.assertEqual(config.thread_id, "789")
        self.assertTrue(config.disable_notification)


class TruncateTests(unittest.TestCase):
    def test_truncate_short_text(self) -> None:
        self.assertEqual(_truncate("short text"), "short text")

    def test_truncate_long_text(self) -> None:
        long_text = "x" * 4000
        result = _truncate(long_text, limit=100)
        self.assertLessEqual(len(result), 100)
        self.assertTrue(result.endswith("..."))


class SendMessageTests(unittest.TestCase):
    def test_send_raises_when_disabled(self) -> None:
        config = _config(enabled=False)
        with self.assertRaises(RuntimeError) as ctx:
            send_message(config, "test")
        self.assertIn("disabled", str(ctx.exception))

    def test_send_raises_when_not_configured(self) -> None:
        config = _config(bot_token="")
        with self.assertRaises(RuntimeError) as ctx:
            send_message(config, "test")
        self.assertIn("missing", str(ctx.exception))

    @mock.patch("ops.notifications.telegram.urlopen")
    def test_send_message_success(self, mock_urlopen: mock.MagicMock) -> None:
        response_payload = json.dumps({"ok": True, "result": {}})
        mock_response = mock.MagicMock()
        mock_response.read.return_value = response_payload.encode("utf-8")
        mock_response.__enter__ = mock.Mock(return_value=mock_response)
        mock_response.__exit__ = mock.Mock(return_value=False)
        mock_urlopen.return_value = mock_response

        config = _config()
        result = send_message(config, "hello")
        self.assertTrue(result["ok"])
        mock_urlopen.assert_called_once()

    @mock.patch("ops.notifications.telegram.urlopen")
    def test_send_message_api_rejection(self, mock_urlopen: mock.MagicMock) -> None:
        response_payload = json.dumps({"ok": False, "description": "bad request"})
        mock_response = mock.MagicMock()
        mock_response.read.return_value = response_payload.encode("utf-8")
        mock_response.__enter__ = mock.Mock(return_value=mock_response)
        mock_response.__exit__ = mock.Mock(return_value=False)
        mock_urlopen.return_value = mock_response

        config = _config()
        with self.assertRaises(RuntimeError) as ctx:
            send_message(config, "hello")
        self.assertIn("rejected", str(ctx.exception))


    @mock.patch("ops.notifications.telegram.urlopen")
    def test_send_message_http_error(self, mock_urlopen: mock.MagicMock) -> None:
        import io

        fp = io.BytesIO(b"Bot was blocked")
        err = HTTPError(
            url="https://api.telegram.org/bot/sendMessage",
            code=403,
            msg="Forbidden",
            hdrs=None,  # type: ignore[arg-type]
            fp=fp,
        )
        self.addCleanup(err.close)
        mock_urlopen.side_effect = err
        config = _config()
        with self.assertRaises(RuntimeError) as ctx:
            send_message(config, "hello")
        self.assertIn("HTTP 403", str(ctx.exception))

    @mock.patch("ops.notifications.telegram.urlopen")
    def test_send_message_url_error(self, mock_urlopen: mock.MagicMock) -> None:
        mock_urlopen.side_effect = URLError("Name or service not known")
        config = _config()
        with self.assertRaises(RuntimeError) as ctx:
            send_message(config, "hello")
        self.assertIn("Unable to reach Telegram API", str(ctx.exception))

    @mock.patch("ops.notifications.telegram.urlopen")
    def test_send_message_with_thread_id(self, mock_urlopen: mock.MagicMock) -> None:
        response_payload = json.dumps({"ok": True, "result": {}})
        mock_response = mock.MagicMock()
        mock_response.read.return_value = response_payload.encode("utf-8")
        mock_response.__enter__ = mock.Mock(return_value=mock_response)
        mock_response.__exit__ = mock.Mock(return_value=False)
        mock_urlopen.return_value = mock_response

        config = TelegramConfig(
            enabled=True, bot_token="tok", chat_id="123",
            thread_id="99", disable_notification=True,
            notify_on_nightly=True, notify_on_sentry=True,
        )
        result = send_message(config, "hello")
        self.assertTrue(result["ok"])
        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        self.assertIn(b"message_thread_id=99", request.data)


if __name__ == "__main__":
    unittest.main()
