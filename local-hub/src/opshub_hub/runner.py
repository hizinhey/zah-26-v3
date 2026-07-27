"""Sequential execution of the five ordered WebdriverIO/TypeScript specs for an OA.

Execution continues through every test and OA after an assertion failure.
Assertion failures never retry; infrastructure errors retry exactly once, with the
Appium session reset in between. Progress/result envelopes are enqueued onto the
durable Task 7 Outbox (surviving transport failures/restarts) and evidence is
captured to the filesystem and uploaded separately via Task 6's evidence endpoint,
with local files preserved until the upload is acknowledged.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Protocol
from uuid import UUID, uuid4

from opshub_hub.classification import FailureCategory, classify_failure
from opshub_hub.evidence import (
    EvidenceFile,
    EvidenceType,
    EvidenceUploadError,
    EvidenceUploader,
    deterministic_test_result_id,
)
from opshub_hub.models import (
    ErrorCategory,
    JobOfferedPayload,
    JobProgressEnvelope,
    JobProgressPayload,
    MessageType,
    TestCase,
    TestCaseStatus,
    TestResultEnvelope,
    TestResultPayload,
    TestResultStatus,
    envelope_to_wire_dict,
)
from opshub_hub.outbox import Outbox
from opshub_hub.templates import TemplateCatalog, materialize_execution_dir

logger = logging.getLogger("opshub_hub.runner")


@dataclass(frozen=True)
class ProcessResult:
    returncode: int
    stdout: str = ""
    stderr: str = ""
    timed_out: bool = False
    """Structural signal (I3 fix) that the launcher killed the subprocess for exceeding its
    timeout, rather than the process exiting on its own with a non-zero code. Classification
    checks this first, so a timeout is never at the mercy of whether its synthetic stderr
    message happens to match one of classification.py's INFRASTRUCTURE regex patterns."""


class SubprocessLauncher(Protocol):
    def run(self, command: list[str], cwd: Path, timeout: float) -> ProcessResult: ...


class AppiumSessionResetter(Protocol):
    def __call__(self) -> None: ...


@dataclass
class AttemptRecord:
    test_case_id: UUID
    attempt: int
    status: TestResultStatus
    error_category: ErrorCategory | None
    duration_ms: int
    log_path: Path


@dataclass
class ExecutionSummary:
    execution_id: UUID
    results: list[TestResultPayload] = field(default_factory=list)
    attempts: list[AttemptRecord] = field(default_factory=list)


def default_command_builder(spec_path: Path) -> list[str]:
    return ["npx", "wdio", "run", "wdio.conf.ts", "--spec", str(spec_path)]


_TERMINAL_PROGRESS_STATUS: dict[TestResultStatus, TestCaseStatus] = {
    TestResultStatus.PASSED: TestCaseStatus.PASSED,
    TestResultStatus.FAILED: TestCaseStatus.FAILED,
    TestResultStatus.ERROR: TestCaseStatus.ERROR,
}


class Runner:
    def __init__(
        self,
        *,
        catalog: TemplateCatalog,
        execution_root: Path,
        launcher: SubprocessLauncher,
        outbox: Outbox,
        transport,
        evidence_uploader: EvidenceUploader | None = None,
        screenshot_capturer: Callable[[Path], Path] | None = None,
        reset_appium_session: AppiumSessionResetter | None = None,
        command_builder: Callable[[Path], list[str]] = default_command_builder,
        spec_timeout: float = 300.0,
        clock: Callable[[], float] = time.monotonic,
        now: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
    ):
        self._catalog = catalog
        self._execution_root = Path(execution_root)
        self._launcher = launcher
        self._outbox = outbox
        self._transport = transport
        self._evidence_uploader = evidence_uploader
        self._screenshot_capturer = screenshot_capturer
        self._reset_appium_session = reset_appium_session
        self._command_builder = command_builder
        self._spec_timeout = spec_timeout
        self._clock = clock
        self._now = now

    def run(self, job: JobOfferedPayload) -> ExecutionSummary:
        execution_dir = self._execution_root / str(job.executionId)
        spec_paths = materialize_execution_dir(self._catalog, execution_dir, job.testCases)
        logs_dir = execution_dir / "logs"
        logs_dir.mkdir(parents=True, exist_ok=True)
        evidence_dir = execution_dir / "evidence"
        evidence_dir.mkdir(parents=True, exist_ok=True)

        summary = ExecutionSummary(execution_id=job.executionId)

        # Iterate testCases in the fixed order validated by JobOfferedPayload
        # (Task 7); execution never skips or reorders on failure.
        for test_case in job.testCases:
            self._send_progress(job.executionId, test_case.testCaseId, TestCaseStatus.RUNNING, "Executing")
            spec_path = spec_paths[str(test_case.testCaseId)]

            record = self._execute_attempt(test_case, spec_path, attempt=1, logs_dir=logs_dir)
            summary.attempts.append(record)

            # TIMEOUT is retryable exactly like INFRASTRUCTURE (I3 fix) - both are
            # infra/environment trouble unrelated to the OA under test, eligible for the one
            # allowed retry, with an Appium session reset in between.
            if record.error_category in (ErrorCategory.INFRASTRUCTURE, ErrorCategory.TIMEOUT):
                if self._reset_appium_session is not None:
                    self._reset_appium_session()
                record = self._execute_attempt(test_case, spec_path, attempt=2, logs_dir=logs_dir)
                summary.attempts.append(record)

            result_payload = TestResultPayload(
                executionId=job.executionId,
                testCaseId=test_case.testCaseId,
                attempt=record.attempt,
                status=record.status,
                durationMs=record.duration_ms,
                errorCategory=record.error_category,
            )
            self._send_result(result_payload)
            summary.results.append(result_payload)

            self._capture_and_upload_evidence(job.executionId, test_case, record, evidence_dir)

            self._send_progress(
                job.executionId,
                test_case.testCaseId,
                _TERMINAL_PROGRESS_STATUS[record.status],
                "Completed",
            )

        return summary

    def _execute_attempt(
        self, test_case: TestCase, spec_path: Path, *, attempt: int, logs_dir: Path
    ) -> AttemptRecord:
        command = self._command_builder(spec_path)
        started = self._clock()
        process = self._launcher.run(command, cwd=spec_path.parent.parent, timeout=self._spec_timeout)
        duration_ms = max(0, int((self._clock() - started) * 1000))

        log_path = logs_dir / f"{test_case.testCaseId}-attempt{attempt}.log"
        log_path.write_text(
            f"$ {' '.join(command)}\nexit={process.returncode}\n\n"
            f"--- stdout ---\n{process.stdout}\n\n--- stderr ---\n{process.stderr}\n"
        )

        if process.returncode == 0:
            status = TestResultStatus.PASSED
            category: ErrorCategory | None = None
        else:
            failure = classify_failure(
                returncode=process.returncode, stdout=process.stdout, stderr=process.stderr,
                timed_out=process.timed_out,
            )
            if failure is FailureCategory.INFRASTRUCTURE:
                status = TestResultStatus.ERROR
                category = ErrorCategory.INFRASTRUCTURE
            elif failure is FailureCategory.TIMEOUT:
                status = TestResultStatus.ERROR
                category = ErrorCategory.TIMEOUT
            else:
                status = TestResultStatus.FAILED
                category = ErrorCategory.ASSERTION_FAILURE

        return AttemptRecord(
            test_case_id=test_case.testCaseId,
            attempt=attempt,
            status=status,
            error_category=category,
            duration_ms=duration_ms,
            log_path=log_path,
        )

    def _send_progress(self, execution_id: UUID, test_case_id: UUID, status: TestCaseStatus, message: str) -> None:
        envelope = JobProgressEnvelope(
            messageId=uuid4(),
            version=1,
            type=MessageType.JOB_PROGRESS,
            timestamp=self._now(),
            payload=JobProgressPayload(
                executionId=execution_id, testCaseId=test_case_id, status=status, message=message
            ),
        )
        self._enqueue_and_flush(envelope)

    def _send_result(self, payload: TestResultPayload) -> None:
        envelope = TestResultEnvelope(
            messageId=uuid4(),
            version=1,
            type=MessageType.TEST_RESULT,
            timestamp=self._now(),
            payload=payload,
        )
        self._enqueue_and_flush(envelope)

    def _enqueue_and_flush(self, envelope) -> None:
        self._outbox.enqueue(envelope_to_wire_dict(envelope))
        try:
            self._outbox.flush(self._transport)
        except Exception:
            # The transport failed mid-flush; the envelope (and anything queued
            # after it) stays durably in the outbox for the next flush attempt
            # (e.g. after failover or reconnect) rather than being dropped.
            pass

    def _capture_and_upload_evidence(
        self, execution_id: UUID, test_case: TestCase, record: AttemptRecord, evidence_dir: Path
    ) -> None:
        """Capture the final screen on pass, or the failure screen on fail/error,
        and upload it separately from the TEST_RESULT envelope. The local file is
        left on disk if the upload fails or no uploader is configured, so it can
        be retried without losing evidence."""
        if self._screenshot_capturer is None:
            # Not a silent no-op (C3): make it visible in the Hub's own logs that evidence is
            # not being captured for this test result, so a misconfigured/omitted capturer is
            # diagnosable from Hub logs alone rather than showing up only as "no evidence" much
            # later, in the backend or UI.
            logger.warning(
                "No screenshot_capturer configured; skipping evidence capture for test case %s attempt %s.",
                test_case.testCaseId,
                record.attempt,
            )
            return
        destination = evidence_dir / f"{test_case.testCaseId}-attempt{record.attempt}.png"
        try:
            screenshot_path = self._screenshot_capturer(destination)
        except Exception:
            return
        if self._evidence_uploader is None:
            return
        evidence = EvidenceFile(path=screenshot_path, evidence_type=EvidenceType.SCREENSHOT)
        test_result_id = deterministic_test_result_id(execution_id, test_case.testCaseId, record.attempt)
        try:
            self._evidence_uploader.upload(test_result_id, evidence)
        except EvidenceUploadError:
            # Local file is preserved on disk until a future acknowledged upload.
            return
