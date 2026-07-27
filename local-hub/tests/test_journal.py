from opshub_hub.journal import ExecutionJournal


def make_journal(tmp_path):
    return ExecutionJournal(tmp_path / "journal.sqlite3")


def test_claim_accepts_a_new_execution(tmp_path):
    journal = make_journal(tmp_path)
    assert journal.claim("exec-1", "idem-1") is True
    assert journal.is_claimed("exec-1") is True


def test_duplicate_job_claims_are_rejected_by_execution_id(tmp_path):
    journal = make_journal(tmp_path)
    assert journal.claim("exec-1", "idem-1") is True
    assert journal.claim("exec-1", "idem-1") is False


def test_duplicate_job_claims_are_rejected_by_idempotency_key(tmp_path):
    journal = make_journal(tmp_path)
    assert journal.claim("exec-1", "idem-1") is True
    # A redelivered offer with a different execution id but the same idempotency
    # key (e.g. server retried with a fresh envelope) must still be rejected.
    assert journal.claim("exec-2", "idem-1") is False


def test_claims_survive_restart(tmp_path):
    db_path = tmp_path / "journal.sqlite3"
    journal = ExecutionJournal(db_path)
    journal.claim("exec-1", "idem-1")
    journal.complete("exec-1")
    journal.close()

    reopened = ExecutionJournal(db_path)
    assert reopened.status("exec-1") == "COMPLETED"
    assert reopened.claim("exec-1", "idem-1") is False


def test_complete_marks_status(tmp_path):
    journal = make_journal(tmp_path)
    journal.claim("exec-1", "idem-1")
    assert journal.status("exec-1") == "CLAIMED"
    journal.complete("exec-1")
    assert journal.status("exec-1") == "COMPLETED"
