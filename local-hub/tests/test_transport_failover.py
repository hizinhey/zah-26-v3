import pytest

from opshub_hub.journal import ExecutionJournal
from opshub_hub.outbox import Outbox
from opshub_hub.transport import TransportError
from opshub_hub.transport.failover import FailoverTransport, Mode


class FakeClock:
    def __init__(self, start: float = 0.0):
        self.now = start

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class ScriptedTransport:
    """A fake transport whose receive_job()/connect() behavior is scripted per-call."""

    def __init__(self, script: list):
        self._script = list(script)
        self.sent: list[dict] = []
        self.heartbeats = 0
        self.connect_calls = 0
        self.closed = False

    def connect(self) -> None:
        self.connect_calls += 1
        if self._script:
            outcome = self._script.pop(0)
            if outcome == "fail":
                raise TransportError("connect failed")

    def receive_job(self):
        if not self._script:
            return None
        outcome = self._script.pop(0)
        if outcome == "fail":
            raise TransportError("receive failed")
        return outcome

    def send(self, envelope: dict) -> None:
        self.sent.append(envelope)

    def heartbeat(self) -> None:
        self.heartbeats += 1

    def close(self) -> None:
        self.closed = True


def test_three_consecutive_websocket_failures_activate_polling():
    ws = ScriptedTransport(["fail", "fail", "fail"])
    polling = ScriptedTransport([None])
    clock = FakeClock()

    transport = FailoverTransport(ws_transport=ws, polling_transport=polling, clock=clock)

    assert transport.mode is Mode.WEBSOCKET
    transport.receive_job()
    assert transport.mode is Mode.WEBSOCKET
    transport.receive_job()
    assert transport.mode is Mode.WEBSOCKET
    transport.receive_job()
    assert transport.mode is Mode.POLLING


def test_fewer_than_threshold_failures_stay_on_websocket():
    ws = ScriptedTransport(["fail", "fail", {"job": "ok"}])
    polling = ScriptedTransport([])
    clock = FakeClock()

    transport = FailoverTransport(ws_transport=ws, polling_transport=polling, clock=clock)
    transport.receive_job()
    transport.receive_job()
    result = transport.receive_job()

    assert transport.mode is Mode.WEBSOCKET
    assert result == {"job": "ok"}


def test_successful_probe_returns_to_websocket(monkeypatch):
    ws = ScriptedTransport(["fail", "fail", "fail"])
    polling = ScriptedTransport([None, None, None])
    clock = FakeClock()

    transport = FailoverTransport(
        ws_transport=ws,
        polling_transport=polling,
        clock=clock,
        probe_interval=10.0,
    )

    for _ in range(3):
        transport.receive_job()
    assert transport.mode is Mode.POLLING

    # Probe too soon: still polling.
    clock.advance(5.0)
    transport.receive_job()
    assert transport.mode is Mode.POLLING

    # Queue a successful WebSocket reconnect and advance past the probe interval.
    ws._script.append("ok")
    clock.advance(10.0)
    transport.receive_job()

    assert transport.mode is Mode.WEBSOCKET


def test_reconnect_delay_backs_off_exponentially_up_to_cap():
    ws = ScriptedTransport(["fail"] * 6)
    polling = ScriptedTransport([None] * 6)
    clock = FakeClock()

    transport = FailoverTransport(
        ws_transport=ws,
        polling_transport=polling,
        clock=clock,
        base_reconnect_delay=1.0,
        max_reconnect_delay=8.0,
    )

    delays = []
    for _ in range(6):
        delays.append(transport._reconnect_delay())
        transport.receive_job()

    assert delays == [1.0, 2.0, 4.0, 8.0, 8.0, 8.0]


def test_duplicate_job_claims_are_rejected_across_transport_redelivery(tmp_path):
    """A JOB_OFFERED redelivered after a WebSocket flap must not be executed twice."""
    journal = ExecutionJournal(tmp_path / "journal.sqlite3")
    ws = ScriptedTransport(
        [
            {"executionId": "exec-1", "idempotencyKey": "idem-1"},
            "fail",
            "fail",
            "fail",
        ]
    )
    polling = ScriptedTransport([{"executionId": "exec-1", "idempotencyKey": "idem-1"}])
    clock = FakeClock()
    transport = FailoverTransport(ws_transport=ws, polling_transport=polling, clock=clock)

    first = transport.receive_job()
    assert journal.claim(first["executionId"], first["idempotencyKey"]) is True

    # WebSocket drops three times, Hub fails over to polling, and the backend
    # redelivers the same job offer (e.g. because the ack never arrived).
    for _ in range(3):
        transport.receive_job()
    redelivered = {"executionId": "exec-1", "idempotencyKey": "idem-1"}
    assert journal.claim(redelivered["executionId"], redelivered["idempotencyKey"]) is False


def test_queued_results_preserve_order_across_restart_and_failover(tmp_path):
    db_path = tmp_path / "outbox.sqlite3"
    outbox = Outbox(db_path)
    outbox.enqueue({"messageId": "1", "type": "TEST_RESULT"})
    outbox.enqueue({"messageId": "2", "type": "TEST_RESULT"})
    outbox.close()

    reopened = Outbox(db_path)
    reopened.enqueue({"messageId": "3", "type": "TEST_RESULT"})

    ws = ScriptedTransport(["fail", "fail", "fail"])
    polling = ScriptedTransport([])
    clock = FakeClock()
    transport = FailoverTransport(ws_transport=ws, polling_transport=polling, clock=clock)
    for _ in range(3):
        transport.receive_job()
    assert transport.mode is Mode.POLLING

    reopened.flush(transport)

    assert [e["messageId"] for e in polling.sent] == ["1", "2", "3"]
    assert len(reopened) == 0


def test_send_and_heartbeat_use_active_transport():
    ws = ScriptedTransport(["fail", "fail", "fail"])
    polling = ScriptedTransport([])
    clock = FakeClock()
    transport = FailoverTransport(ws_transport=ws, polling_transport=polling, clock=clock)

    for _ in range(3):
        transport.receive_job()
    assert transport.mode is Mode.POLLING

    transport.send({"messageId": "x"})
    transport.heartbeat()

    assert polling.sent == [{"messageId": "x"}]
    assert polling.heartbeats == 1
    assert ws.sent == []


def test_close_closes_both_transports():
    ws = ScriptedTransport([])
    polling = ScriptedTransport([])
    transport = FailoverTransport(ws_transport=ws, polling_transport=polling, clock=FakeClock())

    transport.close()

    assert ws.closed is True
    assert polling.closed is True
