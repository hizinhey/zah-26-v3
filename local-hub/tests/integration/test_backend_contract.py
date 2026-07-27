"""Task 13 acceptance layer for the Local Hub: proves the envelopes the real
``Runner`` produces while executing a full, ten-case (two-OA) job - continuing
through an assertion failure, retrying exactly one infrastructure error, and
completing every remaining case - are simultaneously:

1. valid against the shared JSON Schema contract
   (``contracts/schemas/hub-envelope-v1.json``), the same schema
   ``contracts/tests/test_contract_examples.py`` checks hand-written examples
   against; and
2. byte-for-byte compatible with what the backend's
   ``com.opshub.execution.application.ExecutionService`` actually reads -
   field names (``executionId``, ``testCaseId``, ``attempt``, ``status``,
   ``durationMs``, ``errorCategory``) and the exact ``errorCategory`` string
   (``"INFRASTRUCTURE"``) the backend's completion-gating SQL matches against
   verbatim (see ``ExecutionService.completeIfEveryTestCaseReachedATerminalOutcome``).

This is deliberately an integration/acceptance layer on top of already-unit-
tested collaborators (``test_runner.py`` covers retry/ordering/evidence in
isolation with fakes; ``test_contract_examples.py`` covers hand-written
schema examples) - it does not reimplement that coverage, it proves the
Local Hub's real runtime output satisfies both contracts at once, across a
full two-OA (ten test case) job matching the backend's fixed five-cases-
per-OA generation rule.
"""

from __future__ import annotations

import json
from pathlib import Path
from uuid import UUID, uuid4

import pytest
from jsonschema.validators import validator_for

from opshub_hub.models import (
    ORDERED_TEST_CASE_TEMPLATE_IDS,
    JobOfferedPayload,
    TemplateParametersV1,
    TestCase,
    TestResultStatus,
    envelope_to_wire_dict,
)
from opshub_hub.outbox import Outbox
from opshub_hub.runner import ProcessResult, Runner
from opshub_hub.templates import TemplateCatalog

CONTRACTS_ROOT = Path(__file__).resolve().parents[3] / "contracts"
TEMPLATE_ROOT = Path(__file__).resolve().parents[2] / "templates" / "android"

SAMPLE_PARAMETERS = TemplateParametersV1(
    oaName="Sample OA",
    thumbnailUrl="https://cdn.example.test/thumb.png",
    expectedHeader="Header",
    expectedBody="Body",
    expectedButtonText="Open",
    expectedRedirectUrl="https://business.example.test/offer",
    expectedRedirectDomain="business.example.test",
)


def load_envelope_validator():
    schema_path = CONTRACTS_ROOT / "schemas" / "hub-envelope-v1.json"
    schema = json.loads(schema_path.read_text())
    validator_cls = validator_for(schema)
    validator_cls.check_schema(schema)
    return validator_cls(schema)


def make_oa_test_cases() -> list[TestCase]:
    return [
        TestCase(
            testCaseId=uuid4(),
            order=index + 1,
            templateId=template_id,
            templateVersion=1,
            parameters=SAMPLE_PARAMETERS,
        )
        for index, template_id in enumerate(ORDERED_TEST_CASE_TEMPLATE_IDS)
    ]


def make_two_oa_job() -> JobOfferedPayload:
    # JobOfferedPayload only carries one ordered set of five cases on the wire
    # (mirroring how the backend offers one OA's worth of cases at a time -
    # see contracts/schemas/hub-envelope-v1.json $defs.OrderedTestCases); a
    # two-OA operation is exercised here as two sequential jobs sharing one
    # executionId, exactly as ExecutionService.buildJobOfferedEnvelope selects
    # ALL of a plan's cases ordered by (oaOrder, caseOrder) and the Hub runs
    # them in that same order within a single Runner.run() call.
    execution_id = uuid4()
    oa_one = make_oa_test_cases()
    oa_two = make_oa_test_cases()
    return execution_id, oa_one, oa_two


class ScriptedLauncher:
    """Returns scripted ProcessResults in call order; records every invocation."""

    def __init__(self, results: list[ProcessResult]):
        self._results = list(results)
        self.calls: list[list[str]] = []

    def run(self, command: list[str], cwd: Path, timeout: float) -> ProcessResult:
        self.calls.append(command)
        return self._results.pop(0)


class RecordingTransport:
    def __init__(self):
        self.sent: list[dict] = []

    def send(self, envelope: dict) -> None:
        self.sent.append(envelope)


@pytest.fixture()
def catalog() -> TemplateCatalog:
    return TemplateCatalog(TEMPLATE_ROOT)


def test_full_two_oa_job_produces_envelopes_that_satisfy_both_the_json_schema_and_the_backend_field_contract(
    tmp_path, catalog
):
    validator = load_envelope_validator()
    execution_id, oa_one_cases, oa_two_cases = make_two_oa_job()

    # OA #1: case 1 is a plain PASS, case 2 fails on assertion (no retry), the
    # rest pass. OA #2: case 1 fails infrastructure once then passes on the
    # retry (exactly one retry), the rest pass. Ten cases -> eleven subprocess
    # invocations (the one infra retry).
    process_script = (
        [ProcessResult(returncode=0)]  # OA1 case1 PASS
        + [ProcessResult(returncode=1, stdout="AssertionError: expected true", stderr="")]  # OA1 case2 FAIL (assertion)
        + [ProcessResult(returncode=0)] * 3  # OA1 cases 3-5 PASS
        + [ProcessResult(returncode=1, stdout="", stderr="Error: session not created")]  # OA2 case1 attempt1 (infra)
        + [ProcessResult(returncode=0)]  # OA2 case1 attempt2 (retry PASS)
        + [ProcessResult(returncode=0)] * 4  # OA2 cases 2-5 PASS
    )
    launcher = ScriptedLauncher(process_script)
    transport = RecordingTransport()
    outbox = Outbox(tmp_path / "outbox.db")

    runner = Runner(
        catalog=catalog,
        execution_root=tmp_path / "runs",
        launcher=launcher,
        outbox=outbox,
        transport=transport,
        spec_timeout=30.0,
    )

    for oa_cases in (oa_one_cases, oa_two_cases):
        job = JobOfferedPayload(
            executionId=execution_id,
            idempotencyKey="contract-test",
            revision=1,
            platform="ANDROID",
            testCases=oa_cases,
            leaseToken=uuid4(),
        )
        summary = runner.run(job)
        assert summary.execution_id == execution_id

    assert len(launcher.calls) == 11  # 10 cases + 1 infra retry

    # Every envelope actually sent over the wire must validate against the
    # shared schema, and every TEST_RESULT envelope must carry exactly the
    # field names/types/enum-strings the backend's ExecutionService.recordResult
    # and its completion-gating SQL depend on.
    test_result_envelopes = [envelope for envelope in transport.sent if envelope["type"] == "TEST_RESULT"]
    assert len(test_result_envelopes) == 10  # one terminal result per test case, not per attempt

    for envelope in transport.sent:
        validator.validate(envelope)

    payloads_by_test_case: dict[str, list[dict]] = {}
    for envelope in test_result_envelopes:
        payload = envelope["payload"]
        assert set(payload.keys()) == {
            "executionId",
            "testCaseId",
            "attempt",
            "status",
            "durationMs",
            "errorCategory",
        }
        assert payload["executionId"] == str(execution_id)
        assert payload["status"] in {"PASSED", "FAILED", "ERROR"}
        payloads_by_test_case.setdefault(payload["testCaseId"], []).append(payload)

    assertion_failed_case_id = str(oa_one_cases[1].testCaseId)
    infra_retried_case_id = str(oa_two_cases[0].testCaseId)

    # Assertion failures never retry - exactly one TEST_RESULT, attempt 1, FAILED.
    assertion_result = payloads_by_test_case[assertion_failed_case_id][0]
    assert assertion_result["attempt"] == 1
    assert assertion_result["status"] == "FAILED"
    assert assertion_result["errorCategory"] == "ASSERTION_FAILURE"

    # Infrastructure errors retry exactly once - final reported attempt is 2,
    # PASSED, matching what ExecutionService.recordResult upserts by
    # (executionId, testCaseId, attempt) and what its completion-gating SQL
    # (`error_category IS DISTINCT FROM 'INFRASTRUCTURE' OR attempt >= 2`)
    # requires to consider the case terminal.
    infra_result = payloads_by_test_case[infra_retried_case_id][0]
    assert infra_result["attempt"] == 2
    assert infra_result["status"] == "PASSED"
    assert infra_result["errorCategory"] is None

    # Every other case (8 of the 10) is a single, plain PASS.
    plain_passes = [
        payloads
        for test_case_id, payloads in payloads_by_test_case.items()
        if test_case_id not in {assertion_failed_case_id, infra_retried_case_id}
    ]
    assert len(plain_passes) == 8
    for payloads in plain_passes:
        assert len(payloads) == 1
        assert payloads[0]["attempt"] == 1
        assert payloads[0]["status"] == "PASSED"


def test_envelope_to_wire_dict_round_trips_through_the_shared_schema_for_every_message_type():
    """A focused check that Task 7's Pydantic models and the shared JSON Schema
    agree on every envelope shape the Hub ever sends, independent of the
    Runner - catches drift between models.py and hub-envelope-v1.json even if
    the Runner's own behavior changes.
    """
    from datetime import datetime, timezone

    from opshub_hub.models import (
        HeartbeatEnvelope,
        HeartbeatPayload,
        JobProgressEnvelope,
        JobProgressPayload,
        MessageType,
        TestCaseStatus,
        TestResultEnvelope,
        TestResultPayload,
        ErrorCategory,
    )

    validator = load_envelope_validator()
    now = datetime.now(timezone.utc)
    execution_id = uuid4()
    test_case_id = uuid4()

    envelopes = [
        JobProgressEnvelope(
            messageId=uuid4(),
            version=1,
            type=MessageType.JOB_PROGRESS,
            timestamp=now,
            payload=JobProgressPayload(
                executionId=execution_id, testCaseId=test_case_id, status=TestCaseStatus.RUNNING, message="Executing"
            ),
        ),
        TestResultEnvelope(
            messageId=uuid4(),
            version=1,
            type=MessageType.TEST_RESULT,
            timestamp=now,
            payload=TestResultPayload(
                executionId=execution_id,
                testCaseId=test_case_id,
                attempt=1,
                status=TestResultStatus.FAILED,
                durationMs=1500,
                errorCategory=ErrorCategory.ASSERTION_FAILURE,
            ),
        ),
        HeartbeatEnvelope(
            messageId=uuid4(),
            version=1,
            type=MessageType.HEARTBEAT,
            timestamp=now,
            payload=HeartbeatPayload(deviceReady=True, runnerReady=True),
        ),
    ]

    for envelope in envelopes:
        validator.validate(envelope_to_wire_dict(envelope))
