"""Covers the distinction PollingTransport.send draws between a transient
failure (5xx/network - should be retried) and a permanent rejection (4xx, e.g.
409 MESSAGE_OUT_OF_ORDER - retrying the same envelope will never succeed)."""

from pathlib import Path

import httpx
import pytest

from opshub_hub.config import HubConfig
from opshub_hub.transport import PermanentTransportError, TransportError
from opshub_hub.transport.polling_client import PollingTransport


def make_config() -> HubConfig:
    return HubConfig(
        backend_url="http://backend.local",
        hub_id="hub-1",
        hub_token="secret-hub-token",
        template_root=Path("/tmp/templates"),
        data_root=Path("/tmp/data"),
        wdio_project_root=Path("/tmp/wdio-project"),
        node_executable=Path("/usr/bin/node"),
    )


def transport_with_handler(handler) -> PollingTransport:
    client = httpx.Client(transport=httpx.MockTransport(handler))
    return PollingTransport(make_config(), http_client=client)


def test_send_raises_permanent_error_on_409_out_of_order():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(409, json={"code": "MESSAGE_OUT_OF_ORDER", "message": "stale"})

    transport = transport_with_handler(handler)

    with pytest.raises(PermanentTransportError):
        transport.send({"messageId": "1", "type": "TEST_RESULT"})


def test_send_raises_transient_error_on_500():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, text="boom")

    transport = transport_with_handler(handler)

    with pytest.raises(TransportError) as exc_info:
        transport.send({"messageId": "1", "type": "TEST_RESULT"})
    assert not isinstance(exc_info.value, PermanentTransportError)


def test_send_success_does_not_raise():
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["X-Hub-Token"] == "secret-hub-token"
        return httpx.Response(200, json={})

    transport = transport_with_handler(handler)

    transport.send({"messageId": "1", "type": "TEST_RESULT"})
