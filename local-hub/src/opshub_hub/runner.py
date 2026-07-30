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

from opshub_hub.classification import FailureCategory, classify_failure, extract_failure_summary
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
    error_message: str | None
    duration_ms: int
    log_path: Path


@dataclass
class ExecutionSummary:
    execution_id: UUID
    results: list[TestResultPayload] = field(default_factory=list)
    attempts: list[AttemptRecord] = field(default_factory=list)


def default_command_builder(spec_path: Path) -> list[str]:
    """Cold-bootstraps WebdriverIO via `npx` using whichever `node`/`npm` happen to be on
    `PATH`. Never use this for real device execution: with no `wdio.conf.ts` in the execution
    directory, `npx wdio run` has nothing to run and falls back to its interactive `wdio config`
    setup wizard, and it has no guarantee `PATH`'s `node` is new enough for the pinned
    WebdriverIO/Appium dependencies (see `build_wdio_command_builder` for the real one, wired in
    by `main.build_runner` from `OPSHUB_NODE_EXECUTABLE`/`OPSHUB_WDIO_PROJECT_DIR`). Kept only as
    the default for callers/tests that inject their own launcher and never actually invoke it.
    """
    return ["npx", "wdio", "run", "wdio.conf.ts", "--spec", str(spec_path)]


def build_wdio_command_builder(
    node_executable: Path, wdio_project_root: Path, config_filename: str = "wdio.conf.ts"
) -> Callable[[Path], list[str]]:
    """Builds the real command: the pinned `node_executable` running the pinned project's own
    `node_modules/.bin/wdio` CLI script directly (bypassing `npx` and any cold install/bootstrap)
    against the `config_filename` that `materialize_execution_dir` copies into every execution
    directory (`wdio.conf.ts` for ANDROID, `wdio.web.conf.ts` for WEB - see that function's
    docstring), targeting exactly the one freshly rendered spec for this attempt."""
    wdio_bin = wdio_project_root / "node_modules" / ".bin" / "wdio"

    def build(spec_path: Path) -> list[str]:
        return [str(node_executable), str(wdio_bin), "run", config_filename, "--spec", str(spec_path)]

    return build


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
        wdio_project_root: Path | None = None,
        wdio_config_filename: str = "wdio.conf.ts",
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
        self._wdio_project_root = wdio_project_root
        self._wdio_config_filename = wdio_config_filename
        self._spec_timeout = spec_timeout
        self._clock = clock
        self._now = now

    def run(self, job: JobOfferedPayload) -> ExecutionSummary:
        execution_dir = self._execution_root / str(job.executionId)
        spec_paths = materialize_execution_dir(
            self._catalog,
            execution_dir,
            job.testCases,
            wdio_project_root=self._wdio_project_root,
            config_filename=self._wdio_config_filename,
        )
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
                errorMessage=record.error_message,
            )
            self._send_result(result_payload)
            summary.results.append(result_payload)

            self._capture_and_upload_evidence(job.executionId, test_case, record, evidence_dir)

            self._send_progress(
                job.executionId,
                test_case.testCaseId,
                _TERMINAL_PROGRESS_STATUS[record.status],
                record.error_message or "Completed",
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
            message: str | None = None
        else:
            failure = classify_failure(
                returncode=process.returncode, stdout=process.stdout, stderr=process.stderr,
                timed_out=process.timed_out,
            )
            message = extract_failure_summary(
                stdout=process.stdout, stderr=process.stderr, timed_out=process.timed_out
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
            error_message=message,
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
        test_result_id = deterministic_test_result_id(execution_id, test_case.testCaseId, record.attempt)

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
        else:
            destination = evidence_dir / f"{test_case.testCaseId}-attempt{record.attempt}.png"
            try:
                screenshot_path = self._screenshot_capturer(destination)
            except Exception:
                screenshot_path = None
            if screenshot_path is not None and self._evidence_uploader is not None:
                evidence = EvidenceFile(path=screenshot_path, evidence_type=EvidenceType.SCREENSHOT)
                try:
                    self._evidence_uploader.upload(test_result_id, evidence)
                except EvidenceUploadError:
                    # Local file is preserved on disk until a future acknowledged upload.
                    pass

        # Independent of the generic screenshot above (and of whether it succeeded): a spec page
        # may have saved its own comparison evidence directly, which this captures separately.
        self._upload_and_clear_page_evidence(test_result_id, evidence_dir)

    # Fixed filenames a spec page saves directly, at the moment of a comparison it made itself
    # (e.g. zbusiness-chat.page.ts's isLastCardThumbnailMatching, on a thumbnail mismatch) - not
    # this method's own post-hoc screenshot, which fires after the after() hook has already
    # terminated the app and so cannot show what was actually being compared. Execution runs one
    # test case at a time, so only the test case that just finished could have written these.
    _PAGE_EVIDENCE_FILENAMES = ("thumbnail-mismatch-actual.png", "thumbnail-mismatch-reference.png")

    def _upload_and_clear_page_evidence(self, test_result_id: UUID, evidence_dir: Path) -> None:
        if self._evidence_uploader is None:
            return
        for filename in self._PAGE_EVIDENCE_FILENAMES:
            path = evidence_dir / filename
            if not path.exists():
                continue
            try:
                self._evidence_uploader.upload(
                    test_result_id, EvidenceFile(path=path, evidence_type=EvidenceType.SCREENSHOT)
                )
            except EvidenceUploadError:
                logger.warning("Failed to upload page evidence %s for test result %s", filename, test_result_id)
            finally:
                # Always remove, upload outcome notwithstanding: these filenames are shared
                # across every test case that runs this spec, so leaving a failed-to-upload
                # file in place would let it be mistakenly attributed to a later test case
                # that never actually hit a mismatch (a worse outcome than losing one
                # supplementary comparison image on a rare upload failure).
                path.unlink(missing_ok=True)
