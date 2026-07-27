import pytest

from opshub_hub.outbox import Outbox


class FakeTransport:
    def __init__(self, fail_after: int | None = None):
        self.sent: list[dict] = []
        self._fail_after = fail_after

    def send(self, envelope: dict) -> None:
        if self._fail_after is not None and len(self.sent) >= self._fail_after:
            raise RuntimeError("simulated send failure")
        self.sent.append(envelope)


def make_outbox(tmp_path):
    return Outbox(tmp_path / "outbox.sqlite3")


def test_enqueue_and_flush_sends_in_order(tmp_path):
    outbox = make_outbox(tmp_path)
    outbox.enqueue({"messageId": "1"})
    outbox.enqueue({"messageId": "2"})
    outbox.enqueue({"messageId": "3"})

    transport = FakeTransport()
    sent_count = outbox.flush(transport)

    assert sent_count == 3
    assert [e["messageId"] for e in transport.sent] == ["1", "2", "3"]
    assert len(outbox) == 0


def test_events_are_not_removed_before_acknowledgement(tmp_path):
    db_path = tmp_path / "outbox.sqlite3"
    outbox = Outbox(db_path)
    outbox.enqueue({"messageId": "1"})
    outbox.enqueue({"messageId": "2"})

    failing_transport = FakeTransport(fail_after=0)
    with pytest.raises(RuntimeError):
        outbox.flush(failing_transport)

    # Nothing was acknowledged, so both events must still be queued.
    assert len(outbox) == 2

    reopened = Outbox(db_path)
    assert len(reopened) == 2


def test_queued_results_preserve_order_across_restart(tmp_path):
    db_path = tmp_path / "outbox.sqlite3"
    outbox = Outbox(db_path)
    outbox.enqueue({"messageId": "1"})
    outbox.enqueue({"messageId": "2"})
    outbox.close()

    reopened = Outbox(db_path)
    reopened.enqueue({"messageId": "3"})

    transport = FakeTransport()
    reopened.flush(transport)

    assert [e["messageId"] for e in transport.sent] == ["1", "2", "3"]


def test_partial_flush_then_retry_preserves_fifo_order(tmp_path):
    outbox = make_outbox(tmp_path)
    outbox.enqueue({"messageId": "1"})
    outbox.enqueue({"messageId": "2"})
    outbox.enqueue({"messageId": "3"})

    failing_transport = FakeTransport(fail_after=1)
    with pytest.raises(RuntimeError):
        outbox.flush(failing_transport)
    assert [e["messageId"] for e in failing_transport.sent] == ["1"]
    assert len(outbox) == 2

    transport = FakeTransport()
    outbox.flush(transport)
    assert [e["messageId"] for e in transport.sent] == ["2", "3"]
