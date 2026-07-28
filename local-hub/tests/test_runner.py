from pathlib import Path
from uuid import UUID, uuid4

import pytest

from opshub_hub.evidence import EvidenceFile, EvidenceUploadError, deterministic_test_result_id
from opshub_hub.models import (
    ErrorCategory,
    JobOfferedPayload,
    ORDERED_TEST_CASE_TEMPLATE_IDS,
    TemplateParametersV1,
    TestCase,
    TestResultStatus,
)
from opshub_hub.outbox import Outbox
from opshub_hub.runner import ProcessResult, Runner, build_wdio_command_builder, default_command_builder
from opshub_hub.templates import TemplateCatalog

TEMPLATE_ROOT = Path(__file__).resolve().parents[1] / "templates" / "android"

SAMPLE_PARAMETERS = TemplateParametersV1(
    oaName="Sample OA",
    thumbnailUrl="https://cdn.example.test/thumb.png",
    expectedHeader="Header",
    expectedBody="Body",
    expectedButtonText="Open",
    expectedRedirectUrl="https://business.example.test/offer",
    expectedRedirectDomain="business.example.test",
)


def make_job() -> JobOfferedPayload:
    test_cases = [
        TestCase(
            testCaseId=uuid4(),
            oaOrder=1,
            oaName="Sample OA",
            order=index + 1,
            templateId=template_id,
            templateVersion=1,
            parameters=SAMPLE_PARAMETERS,
        )
        for index, template_id in enumerate(ORDERED_TEST_CASE_TEMPLATE_IDS)
    ]
    return JobOfferedPayload(
        executionId=uuid4(),
        idempotencyKey="idem-1",
        revision=1,
        platform="ANDROID",
        testCases=test_cases,
        leaseToken=uuid4(),
    )


class FakeLauncher:
    """Returns scripted ProcessResults in call order; records every invocation."""

    def __init__(self, results: list[ProcessResult]):
        self._results = list(results)
        self.calls: list[tuple[list[str], Path, float]] = []

    def run(self, command, cwd, timeout):
        self.calls.append((command, cwd, timeout))
        if not self._results:
            raise AssertionError("FakeLauncher ran out of scripted results")
        return self._results.pop(0)


class FakeTransport:
    def __init__(self, fail_after: int | None = None):
        self.sent: list[dict] = []
        self._fail_after = fail_after

    def send(self, envelope: dict) -> None:
        if self._fail_after is not None and len(self.sent) >= self._fail_after:
            raise RuntimeError("simulated send failure")
        self.sent.append(envelope)


class FakeScreenshotCapturer:
    def __init__(self):
        self.calls: list[Path] = []

    def __call__(self, destination: Path) -> Path:
        self.calls.append(destination)
        destination.write_bytes(b"fake-png-bytes")
        return destination


class FakeEvidenceUploader:
    def __init__(self, fail: bool = False):
        self.uploads: list[tuple] = []
        self._fail = fail

    def upload(self, test_result_id, evidence: EvidenceFile):
        if self._fail:
            raise EvidenceUploadError("upload rejected")
        self.uploads.append((test_result_id, evidence))
        return uuid4()


PASS = ProcessResult(returncode=0, stdout="5 passing", stderr="")
ASSERTION_FAIL = ProcessResult(
    returncode=1,
    stdout="1 failing\nAssertionError: expected 'A' to equal 'B'",
    stderr="",
)
INFRA_FAIL = ProcessResult(
    returncode=1,
    stdout="",
    stderr="Error: Could not create a new session. Appium unreachable at http://127.0.0.1:4723",
)
TIMEOUT_FAIL = ProcessResult(
    returncode=-1,
    stdout="",
    stderr="\nTimed out after 30s waiting for the spec to finish.",
    timed_out=True,
)


def make_runner(tmp_path, launcher, *, transport=None, outbox=None, reset_session=None,
                 screenshot_capturer=None, evidence_uploader=None):
    catalog = TemplateCatalog(TEMPLATE_ROOT)
    outbox = outbox if outbox is not None else Outbox(tmp_path / "outbox.sqlite3")
    transport = transport if transport is not None else FakeTransport()
    return Runner(
        catalog=catalog,
        execution_root=tmp_path / "executions",
        launcher=launcher,
        outbox=outbox,
        transport=transport,
        reset_appium_session=reset_session,
        screenshot_capturer=screenshot_capturer,
        evidence_uploader=evidence_uploader,
        clock=lambda: 0.0,
    ), outbox, transport


def test_fixed_oa_test_ordering(tmp_path):
    job = make_job()
    launcher = FakeLauncher([PASS] * 5)
    runner, outbox, transport = make_runner(tmp_path, launcher)

    summary = runner.run(job)

    assert [result.testCaseId for result in summary.results] == [tc.testCaseId for tc in job.testCases]
    assert all(result.status == TestResultStatus.PASSED for result in summary.results)
    # Spec files were launched in the fixed template order too.
    launched_specs = [str(call[0][-1]) for call in launcher.calls]
    for template_id, spec in zip(ORDERED_TEST_CASE_TEMPLATE_IDS, launched_specs):
        assert spec.endswith(f"{template_id}.spec.ts")


def test_continues_through_every_test_after_assertion_failure(tmp_path):
    job = make_job()
    # Second test case (thumbnail) fails on assertion; everything else passes.
    launcher = FakeLauncher([PASS, ASSERTION_FAIL, PASS, PASS, PASS])
    runner, outbox, transport = make_runner(tmp_path, launcher)

    summary = runner.run(job)

    assert len(summary.results) == 5
    statuses = [result.status for result in summary.results]
    assert statuses == [
        TestResultStatus.PASSED,
        TestResultStatus.FAILED,
        TestResultStatus.PASSED,
        TestResultStatus.PASSED,
        TestResultStatus.PASSED,
    ]
    assert summary.results[1].errorCategory == ErrorCategory.ASSERTION_FAILURE


def test_no_retry_after_assertion_failure(tmp_path):
    job = make_job()
    launcher = FakeLauncher([ASSERTION_FAIL, PASS, PASS, PASS, PASS])
    runner, outbox, transport = make_runner(tmp_path, launcher)

    summary = runner.run(job)

    first_case_attempts = [a for a in summary.attempts if a.test_case_id == job.testCases[0].testCaseId]
    assert len(first_case_attempts) == 1
    assert summary.results[0].attempt == 1
    assert launcher.calls.__len__() == 5  # no extra retry call was made


def test_one_retry_after_infrastructure_error(tmp_path):
    job = make_job()
    launcher = FakeLauncher([INFRA_FAIL, PASS, PASS, PASS, PASS, PASS])
    reset_calls = []
    runner, outbox, transport = make_runner(
        tmp_path, launcher, reset_session=lambda: reset_calls.append(True)
    )

    summary = runner.run(job)

    first_case_attempts = [a for a in summary.attempts if a.test_case_id == job.testCases[0].testCaseId]
    assert [a.attempt for a in first_case_attempts] == [1, 2]
    assert reset_calls == [True]
    assert summary.results[0].attempt == 2
    assert summary.results[0].status == TestResultStatus.PASSED
    assert summary.results[0].errorCategory is None
    assert launcher.calls.__len__() == 6  # one retry call was made


def test_infrastructure_error_retries_only_once_even_if_retry_also_fails(tmp_path):
    job = make_job()
    launcher = FakeLauncher([INFRA_FAIL, INFRA_FAIL, PASS, PASS, PASS, PASS])
    runner, outbox, transport = make_runner(tmp_path, launcher, reset_session=lambda: None)

    summary = runner.run(job)

    first_case_attempts = [a for a in summary.attempts if a.test_case_id == job.testCases[0].testCaseId]
    assert len(first_case_attempts) == 2
    assert summary.results[0].attempt == 2
    assert summary.results[0].status == TestResultStatus.ERROR
    assert summary.results[0].errorCategory == ErrorCategory.INFRASTRUCTURE


def test_subprocess_timeout_is_classified_as_timeout_and_retries_once(tmp_path):
    """I3 regression: a subprocess.TimeoutExpired (surfaced as ProcessResult(timed_out=True))
    must retry exactly once, like an infrastructure error, and report ErrorCategory.TIMEOUT -
    not fall through to ASSERTION (never retried)."""
    job = make_job()
    launcher = FakeLauncher([TIMEOUT_FAIL, PASS, PASS, PASS, PASS, PASS])
    reset_calls = []
    runner, outbox, transport = make_runner(
        tmp_path, launcher, reset_session=lambda: reset_calls.append(True)
    )

    summary = runner.run(job)

    first_case_attempts = [a for a in summary.attempts if a.test_case_id == job.testCases[0].testCaseId]
    assert [a.attempt for a in first_case_attempts] == [1, 2]
    assert reset_calls == [True]
    assert summary.results[0].attempt == 2
    assert summary.results[0].status == TestResultStatus.PASSED


def test_per_attempt_logs_are_written(tmp_path):
    job = make_job()
    launcher = FakeLauncher([INFRA_FAIL, PASS, PASS, PASS, PASS, PASS])
    runner, outbox, transport = make_runner(tmp_path, launcher, reset_session=lambda: None)

    summary = runner.run(job)

    first_case_attempts = [a for a in summary.attempts if a.test_case_id == job.testCases[0].testCaseId]
    assert len(first_case_attempts) == 2
    for attempt_record in first_case_attempts:
        assert attempt_record.log_path.is_file()
    log_texts = [record.log_path.read_text() for record in first_case_attempts]
    assert "exit=1" in log_texts[0]
    assert "Appium unreachable" in log_texts[0]
    assert "exit=0" in log_texts[1]


def test_final_evidence_is_captured_and_uploaded(tmp_path):
    job = make_job()
    launcher = FakeLauncher([PASS] * 5)
    capturer = FakeScreenshotCapturer()
    uploader = FakeEvidenceUploader()
    runner, outbox, transport = make_runner(
        tmp_path, launcher, screenshot_capturer=capturer, evidence_uploader=uploader
    )

    runner.run(job)

    # One screenshot captured per test case (the final screen for each).
    assert len(capturer.calls) == 5
    assert len(uploader.uploads) == 5
    for test_result_id, evidence in uploader.uploads:
        assert evidence.path.is_file()
        assert evidence.sha256  # computed successfully
        assert evidence.size > 0
        # The uploaded id must be the deterministic id the backend independently
        # computes for (executionId, testCaseId, attempt) - not testCaseId itself -
        # or the real backend rejects the upload (test_results row doesn't exist yet).
        test_case_id = UUID(evidence.path.stem.rsplit("-attempt", 1)[0])
        attempt = int(evidence.path.stem.rsplit("-attempt", 1)[1])
        assert test_result_id == deterministic_test_result_id(job.executionId, test_case_id, attempt)


def test_evidence_upload_failure_preserves_local_file(tmp_path):
    job = make_job()
    launcher = FakeLauncher([PASS] * 5)
    capturer = FakeScreenshotCapturer()
    uploader = FakeEvidenceUploader(fail=True)
    runner, outbox, transport = make_runner(
        tmp_path, launcher, screenshot_capturer=capturer, evidence_uploader=uploader
    )

    runner.run(job)

    assert len(capturer.calls) == 5
    assert uploader.uploads == []
    for destination in capturer.calls:
        assert destination.is_file()


def test_outbox_retains_envelopes_when_transport_flush_fails(tmp_path):
    job = make_job()
    launcher = FakeLauncher([PASS] * 5)
    # Fail every send: nothing should ever be acknowledged, so all envelopes stay queued.
    transport = FakeTransport(fail_after=0)
    runner, outbox, _ = make_runner(tmp_path, launcher, transport=transport)

    summary = runner.run(job)

    # The run itself completes even though every progress/result send fails.
    assert len(summary.results) == 5
    assert transport.sent == []
    # 2 JOB_PROGRESS + 1 TEST_RESULT per test case, all still queued.
    assert len(outbox) == 15


def test_default_command_builder_uses_npx_wdio():
    command = default_command_builder(Path("/tmp/exec/tests/example.spec.ts"))
    assert command == ["npx", "wdio", "run", "wdio.conf.ts", "--spec", "/tmp/exec/tests/example.spec.ts"]


def test_build_wdio_command_builder_uses_the_pinned_node_and_project_wdio_cli(tmp_path):
    """Node-version fix regression test: the real command must invoke the pinned
    node_executable running the pinned project's own node_modules/.bin/wdio directly, never
    `npx` (which has no guarantee about which `node` it resolves, or that a `wdio.conf.ts`
    even exists to run - see runner.default_command_builder's docstring)."""
    node_executable = Path("/opt/node22/bin/node")
    wdio_project_root = tmp_path / "wdio-project"
    spec_path = Path("/tmp/exec/tests/example.spec.ts")

    command_builder = build_wdio_command_builder(node_executable, wdio_project_root)
    command = command_builder(spec_path)

    assert command == [
        str(node_executable),
        str(wdio_project_root / "node_modules" / ".bin" / "wdio"),
        "run",
        "wdio.conf.ts",
        "--spec",
        str(spec_path),
    ]
    assert "npx" not in command
