"""Transport layer: WebSocket primary, HTTPS long-polling fallback, failover orchestration."""

from __future__ import annotations


class TransportError(Exception):
    """Raised when a transport fails to connect, send, or receive because of a
    transient condition (network error, server error) that may succeed on retry."""


class PermanentTransportError(TransportError):
    """Raised when the backend permanently rejects a send (HTTP 4xx) - retrying
    the exact same envelope will never succeed, so callers should drop it
    rather than retry it forever."""


__all__ = ["TransportError", "PermanentTransportError"]
