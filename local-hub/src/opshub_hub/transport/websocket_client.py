"""Primary WebSocket transport: `/ws/v1/hubs/{hubId}`.

Exchanges the same HubEnvelopeV1 JSON payloads as the HTTPS polling fallback
(opshub_hub.transport.polling_client) so failover.FailoverTransport can switch
between the two without either side noticing a difference in message shape.
"""

from __future__ import annotations

import json

from websockets.sync.client import ClientConnection, connect

from opshub_hub.config import HubConfig
from opshub_hub.transport import TransportError


class WebSocketTransport:
    def __init__(self, config: HubConfig, platform: str, connect_timeout: float = 10.0):
        self._config = config
        self._platform = platform
        self._connect_timeout = connect_timeout
        self._connection: ClientConnection | None = None

    def connect(self) -> None:
        try:
            self._connection = connect(
                self._config.websocket_url,
                additional_headers={"X-Hub-Token": self._config.hub_token, "X-Hub-Platform": self._platform},
                open_timeout=self._connect_timeout,
            )
        except Exception as exc:  # noqa: BLE001 - any connect failure is a transport failure
            self._connection = None
            raise TransportError(f"WebSocket connect failed: {exc}") from exc

    def receive_job(self) -> dict | None:
        if self._connection is None:
            self.connect()
        assert self._connection is not None
        try:
            raw = self._connection.recv(timeout=0.01)
        except TimeoutError:
            return None
        except Exception as exc:  # noqa: BLE001
            self._connection = None
            raise TransportError(f"WebSocket receive failed: {exc}") from exc
        return json.loads(raw)

    def send(self, envelope: dict) -> None:
        if self._connection is None:
            self.connect()
        assert self._connection is not None
        try:
            self._connection.send(json.dumps(envelope))
        except Exception as exc:  # noqa: BLE001
            self._connection = None
            raise TransportError(f"WebSocket send failed: {exc}") from exc

    def heartbeat(self) -> None:
        self.send(
            {
                "messageId": _new_message_id(),
                "version": 1,
                "type": "HEARTBEAT",
                "timestamp": _now_iso(),
                "payload": {"deviceReady": True, "runnerReady": True},
            }
        )

    def close(self) -> None:
        if self._connection is not None:
            try:
                self._connection.close()
            finally:
                self._connection = None


def _new_message_id() -> str:
    import uuid

    return str(uuid.uuid4())


def _now_iso() -> str:
    from datetime import datetime, timezone

    return datetime.now(timezone.utc).isoformat()
