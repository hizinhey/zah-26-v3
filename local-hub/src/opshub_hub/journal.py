"""Durable SQLite-backed execution journal.

Tracks which executions this Hub has claimed so a JOB_OFFERED redelivered after a
reconnect (WebSocket flap, restart, duplicate poll response) is not executed twice.
"""

from __future__ import annotations

import sqlite3
from pathlib import Path


class ExecutionJournal:
    def __init__(self, db_path: str | Path):
        self._db_path = str(db_path)
        self._conn = sqlite3.connect(self._db_path)
        self._conn.execute("PRAGMA journal_mode=WAL")
        self._init_schema()

    def _init_schema(self) -> None:
        self._conn.execute(
            """
            CREATE TABLE IF NOT EXISTS executions (
                execution_id TEXT PRIMARY KEY,
                idempotency_key TEXT NOT NULL UNIQUE,
                status TEXT NOT NULL DEFAULT 'CLAIMED',
                claimed_at TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ','now')),
                completed_at TEXT
            )
            """
        )
        self._conn.commit()

    def claim(self, execution_id: str, idempotency_key: str) -> bool:
        """Record a claim for this execution. Returns False if already claimed
        (same execution_id already present, or same idempotency_key already
        claimed under a different execution_id) — the caller must not re-execute."""
        cursor = self._conn.execute(
            "SELECT idempotency_key FROM executions WHERE execution_id = ? OR idempotency_key = ?",
            (execution_id, idempotency_key),
        )
        existing = cursor.fetchone()
        if existing is not None:
            return False
        self._conn.execute(
            "INSERT INTO executions (execution_id, idempotency_key, status) VALUES (?, ?, 'CLAIMED')",
            (execution_id, idempotency_key),
        )
        self._conn.commit()
        return True

    def is_claimed(self, execution_id: str) -> bool:
        cursor = self._conn.execute(
            "SELECT 1 FROM executions WHERE execution_id = ?", (execution_id,)
        )
        return cursor.fetchone() is not None

    def complete(self, execution_id: str) -> None:
        self._conn.execute(
            "UPDATE executions SET status = 'COMPLETED', "
            "completed_at = strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE execution_id = ?",
            (execution_id,),
        )
        self._conn.commit()

    def status(self, execution_id: str) -> str | None:
        cursor = self._conn.execute(
            "SELECT status FROM executions WHERE execution_id = ?", (execution_id,)
        )
        row = cursor.fetchone()
        return row[0] if row else None

    def close(self) -> None:
        self._conn.close()
