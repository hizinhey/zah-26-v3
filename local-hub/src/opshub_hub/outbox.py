"""Durable FIFO outbox for outbound envelopes (JOB_PROGRESS, TEST_RESULT, ...).

Backed by SQLite so queued results survive a Hub restart. An entry is only
removed after the transport has acknowledged it — a crash between send and ack
simply causes a harmless resend, never a silent drop.
"""

from __future__ import annotations

import json
import logging
import sqlite3
from pathlib import Path
from typing import Protocol

from opshub_hub.transport import PermanentTransportError

logger = logging.getLogger("opshub_hub")


class SendsEnvelopes(Protocol):
    def send(self, envelope: dict) -> None: ...


class Outbox:
    def __init__(self, db_path: str | Path):
        self._db_path = str(db_path)
        self._conn = sqlite3.connect(self._db_path)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._init_schema()

    def _init_schema(self) -> None:
        self._conn.execute(
            """
            CREATE TABLE IF NOT EXISTS outbox (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                envelope_json TEXT NOT NULL
            )
            """
        )
        self._conn.commit()

    def enqueue(self, envelope: dict) -> int:
        cursor = self._conn.execute(
            "INSERT INTO outbox (envelope_json) VALUES (?)",
            (json.dumps(envelope),),
        )
        self._conn.commit()
        return cursor.lastrowid

    def pending(self) -> list[tuple[int, dict]]:
        """Return queued (seq, envelope) pairs in FIFO order, oldest first."""
        cursor = self._conn.execute("SELECT seq, envelope_json FROM outbox ORDER BY seq ASC")
        return [(seq, json.loads(payload)) for seq, payload in cursor.fetchall()]

    def _remove(self, seq: int) -> None:
        self._conn.execute("DELETE FROM outbox WHERE seq = ?", (seq,))
        self._conn.commit()

    def flush(self, transport: SendsEnvelopes) -> int:
        """Send every queued envelope, oldest first, stopping at the first transient
        failure so order is preserved. Returns the number of envelopes successfully
        sent. An entry is removed only after transport.send() returns without
        raising, OR after transport.send() raises PermanentTransportError - a
        permanent rejection (e.g. HTTP 409 MESSAGE_OUT_OF_ORDER) will never
        succeed on retry, so the entry is dropped (and logged) instead of being
        retried forever."""
        sent = 0
        for seq, envelope in self.pending():
            try:
                transport.send(envelope)
            except PermanentTransportError:
                logger.warning(
                    "Dropping outbox entry seq=%s (messageId=%s): permanently rejected by transport",
                    seq,
                    envelope.get("messageId"),
                )
                self._remove(seq)
                continue
            self._remove(seq)
            sent += 1
        return sent

    def __len__(self) -> int:
        cursor = self._conn.execute("SELECT COUNT(*) FROM outbox")
        return cursor.fetchone()[0]

    def close(self) -> None:
        self._conn.close()
