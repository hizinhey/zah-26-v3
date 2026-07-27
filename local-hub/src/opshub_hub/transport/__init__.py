"""Transport layer: WebSocket primary, HTTPS long-polling fallback, failover orchestration."""

from __future__ import annotations


class TransportError(Exception):
    """Raised when a transport fails to connect, send, or receive."""


__all__ = ["TransportError"]
