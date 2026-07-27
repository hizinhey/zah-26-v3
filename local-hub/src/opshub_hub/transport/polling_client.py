"""HTTPS long-polling fallback transport.

Uses `GET /api/v1/hubs/{hubId}/jobs/next?waitSeconds=25`,
`POST /api/v1/hubs/{hubId}/heartbeat`, `POST /api/v1/leases/{leaseToken}/renew`,
`POST /api/v1/hubs/{hubId}/progress`, and `POST /api/v1/hubs/{hubId}/results`.
Exchanges the same HubEnvelopeV1 JSON payloads as the WebSocket transport.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone

import httpx

from opshub_hub.config import HubConfig
from opshub_hub.transport import TransportError

WAIT_SECONDS = 25


class PollingTransport:
    def __init__(self, config: HubConfig, http_client: httpx.Client | None = None):
        self._config = config
        self._client = http_client or httpx.Client(timeout=WAIT_SECONDS + 10)

    def connect(self) -> None:
        # Stateless over HTTP - "connecting" is just proving the backend is reachable.
        try:
            self._client.post(
                self._config.heartbeat_url,
                headers=self._headers(),
                json=self._heartbeat_envelope(),
            ).raise_for_status()
        except httpx.HTTPError as exc:
            raise TransportError(f"Polling connect probe failed: {exc}") from exc

    def receive_job(self) -> dict | None:
        try:
            response = self._client.get(
                self._config.poll_next_job_url,
                headers=self._headers(),
                params={"waitSeconds": WAIT_SECONDS},
            )
            response.raise_for_status()
        except httpx.HTTPError as exc:
            raise TransportError(f"Polling receive failed: {exc}") from exc
        if response.status_code == 204 or not response.content:
            return None
        return response.json()

    def send(self, envelope: dict) -> None:
        url = self._config.results_url if envelope.get("type") == "TEST_RESULT" else self._config.progress_url
        try:
            self._client.post(url, headers=self._headers(), json=envelope).raise_for_status()
        except httpx.HTTPError as exc:
            raise TransportError(f"Polling send failed: {exc}") from exc

    def heartbeat(self) -> None:
        try:
            self._client.post(
                self._config.heartbeat_url,
                headers=self._headers(),
                json=self._heartbeat_envelope(),
            ).raise_for_status()
        except httpx.HTTPError as exc:
            raise TransportError(f"Polling heartbeat failed: {exc}") from exc

    def renew_lease(self, lease_token: str) -> bool:
        try:
            response = self._client.post(self._config.lease_renew_url(lease_token), headers=self._headers())
        except httpx.HTTPError as exc:
            raise TransportError(f"Lease renewal failed: {exc}") from exc
        return response.status_code == 200

    def close(self) -> None:
        self._client.close()

    def _headers(self) -> dict:
        return {"X-Hub-Token": self._config.hub_token}

    def _heartbeat_envelope(self) -> dict:
        return {
            "messageId": str(uuid.uuid4()),
            "version": 1,
            "type": "HEARTBEAT",
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "payload": {"deviceReady": True, "runnerReady": True},
        }
