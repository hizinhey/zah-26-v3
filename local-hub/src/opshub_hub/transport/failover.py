"""Failover orchestration between the primary WebSocket transport and the HTTPS
long-polling fallback.

Rules (per the Task 7 brief):
- WebSocket is primary; three consecutive WebSocket failures activate polling.
- While on polling, periodic WebSocket probes are attempted; a successful probe
  returns the Hub to WebSocket.
- Reconnect attempts use an exponential backoff delay with a cap.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, Protocol

from opshub_hub.transport import TransportError


class Mode(str, Enum):
    WEBSOCKET = "WEBSOCKET"
    POLLING = "POLLING"


class Transport(Protocol):
    def connect(self) -> None: ...
    def receive_job(self) -> dict | None: ...
    def send(self, envelope: dict) -> None: ...
    def heartbeat(self) -> None: ...
    def close(self) -> None: ...


@dataclass
class FailoverTransport:
    ws_transport: Transport
    polling_transport: Transport
    clock: Callable[[], float] = time.monotonic
    failure_threshold: int = 3
    base_reconnect_delay: float = 1.0
    max_reconnect_delay: float = 30.0
    probe_interval: float = 15.0

    mode: Mode = field(default=Mode.WEBSOCKET, init=False)
    _consecutive_ws_failures: int = field(default=0, init=False)
    _last_probe_at: float = field(default=float("-inf"), init=False)
    _reconnect_attempt: int = field(default=0, init=False)

    def _active(self) -> Transport:
        return self.ws_transport if self.mode is Mode.WEBSOCKET else self.polling_transport

    def _reconnect_delay(self) -> float:
        delay = self.base_reconnect_delay * (2**self._reconnect_attempt)
        return min(delay, self.max_reconnect_delay)

    def _record_ws_failure(self) -> None:
        self._consecutive_ws_failures += 1
        self._reconnect_attempt += 1
        if self.mode is Mode.WEBSOCKET and self._consecutive_ws_failures >= self.failure_threshold:
            self.mode = Mode.POLLING
            self._last_probe_at = self.clock()

    def _record_ws_success(self) -> None:
        self._consecutive_ws_failures = 0
        self._reconnect_attempt = 0
        if self.mode is Mode.POLLING:
            self.mode = Mode.WEBSOCKET

    def _maybe_probe_websocket(self) -> None:
        """While on polling, periodically attempt to reconnect over WebSocket."""
        if self.mode is not Mode.POLLING:
            return
        now = self.clock()
        if now - self._last_probe_at < self.probe_interval:
            return
        self._last_probe_at = now
        try:
            self.ws_transport.connect()
        except TransportError:
            return
        self._record_ws_success()

    def receive_job(self) -> dict | None:
        """Receive the next job offer (or None if none is available right now),
        transparently failing over between transports."""
        self._maybe_probe_websocket()
        transport = self._active()
        try:
            job = transport.receive_job()
        except TransportError:
            if self.mode is Mode.WEBSOCKET:
                self._record_ws_failure()
                if self.mode is Mode.POLLING:
                    # Just tripped over to polling: try immediately so a caller
                    # doesn't have to wait a full cycle to get a job.
                    try:
                        return self.polling_transport.receive_job()
                    except TransportError:
                        return None
                return None
            raise
        else:
            if self.mode is Mode.WEBSOCKET:
                self._record_ws_success()
            return job

    def send(self, envelope: dict) -> None:
        self._active().send(envelope)

    def heartbeat(self) -> None:
        self._active().heartbeat()

    def close(self) -> None:
        self.ws_transport.close()
        self.polling_transport.close()
