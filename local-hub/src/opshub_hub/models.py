"""Typed protocol models mirroring contracts/schemas/hub-envelope-v1.json exactly.

Extra fields are forbidden and enum values must match the contract verbatim.
"""

from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Annotated, Literal, Union
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class MessageType(str, Enum):
    JOB_OFFERED = "JOB_OFFERED"
    JOB_PROGRESS = "JOB_PROGRESS"
    TEST_RESULT = "TEST_RESULT"
    HEARTBEAT = "HEARTBEAT"


class TestCaseStatus(str, Enum):
    PENDING = "PENDING"
    READY = "READY"
    RUNNING = "RUNNING"
    PASSED = "PASSED"
    FAILED = "FAILED"
    ERROR = "ERROR"


class TestResultStatus(str, Enum):
    PASSED = "PASSED"
    FAILED = "FAILED"
    ERROR = "ERROR"


class ErrorCategory(str, Enum):
    ASSERTION_FAILURE = "ASSERTION_FAILURE"
    INFRASTRUCTURE = "INFRASTRUCTURE"
    TIMEOUT = "TIMEOUT"
    CONFIGURATION = "CONFIGURATION"
    UNKNOWN = "UNKNOWN"


class TemplateParametersV1(StrictModel):
    oaName: str = Field(min_length=1)
    thumbnailUrl: str
    expectedHeader: str = Field(min_length=1)
    expectedBody: str = Field(min_length=1)
    expectedButtonText: str = Field(min_length=1)
    expectedRedirectUrl: str
    expectedRedirectDomain: str = Field(min_length=1)


class TestCase(StrictModel):
    testCaseId: UUID
    oaOrder: int = Field(ge=1)
    oaName: str = Field(min_length=1)
    order: int = Field(ge=1)
    templateId: str = Field(min_length=1)
    templateVersion: int = Field(ge=1)
    parameters: TemplateParametersV1


# Fixed order/templateId per position per platform, mirroring $defs.OrderedTestCases'
# prefixItems in contracts/schemas/hub-envelope-v1.json.
ORDERED_TEST_CASE_TEMPLATE_IDS_BY_PLATFORM: dict[str, tuple[str, ...]] = {
    "ANDROID": (
        "android-oa-delivery-v1",
        "android-thumbnail-v1",
        "android-content-v1",
        "android-button-text-v1",
        "android-redirect-v1",
    ),
    "WEB": (
        "web-oa-delivery-v1",
        "web-thumbnail-v1",
        "web-content-v1",
        "web-button-text-v1",
        "web-redirect-v1",
    ),
}

# Retained for any existing import of the old single-platform name; equivalent to
# ORDERED_TEST_CASE_TEMPLATE_IDS_BY_PLATFORM["ANDROID"].
ORDERED_TEST_CASE_TEMPLATE_IDS: tuple[str, ...] = ORDERED_TEST_CASE_TEMPLATE_IDS_BY_PLATFORM["ANDROID"]


class JobOfferedPayload(StrictModel):
    executionId: UUID
    idempotencyKey: str = Field(min_length=1)
    revision: int = Field(ge=1)
    platform: Literal["ANDROID", "WEB"]
    testCases: list[TestCase]
    leaseToken: UUID

    @model_validator(mode="after")
    def validate_ordered_test_cases(self) -> "JobOfferedPayload":
        ordered_template_ids = ORDERED_TEST_CASE_TEMPLATE_IDS_BY_PLATFORM[self.platform]
        group_size = len(ordered_template_ids)
        value = self.testCases
        if len(value) == 0 or len(value) % group_size != 0:
            raise ValueError(
                f"testCases must contain a positive multiple of {group_size} items "
                f"(one group of {group_size} per OA), got {len(value)}"
            )

        num_groups = len(value) // group_size
        for group_index in range(num_groups):
            group = value[group_index * group_size:(group_index + 1) * group_size]
            expected_oa_order = group_index + 1
            group_oa_order = group[0].oaOrder
            if group_oa_order != expected_oa_order:
                raise ValueError(
                    f"testCases group {group_index} must have oaOrder {expected_oa_order}, "
                    f"got {group_oa_order}"
                )
            for offset, (test_case, expected_template_id) in enumerate(
                zip(group, ordered_template_ids)
            ):
                expected_order = offset + 1
                if test_case.oaOrder != group_oa_order:
                    raise ValueError(
                        f"testCases group {group_index} has inconsistent oaOrder: "
                        f"expected {group_oa_order}, got {test_case.oaOrder} at position {offset}"
                    )
                if test_case.order != expected_order:
                    raise ValueError(
                        f"testCases group {group_index} position {offset}: order must be "
                        f"{expected_order}, got {test_case.order}"
                    )
                if test_case.templateId != expected_template_id:
                    raise ValueError(
                        f"testCases group {group_index} position {offset}: templateId must be "
                        f"'{expected_template_id}', got '{test_case.templateId}'"
                    )
        return self


class JobProgressPayload(StrictModel):
    executionId: UUID
    testCaseId: UUID
    status: TestCaseStatus
    message: str


class TestResultPayload(StrictModel):
    executionId: UUID
    testCaseId: UUID
    attempt: int = Field(ge=1)
    status: TestResultStatus
    durationMs: int = Field(ge=0)
    errorCategory: ErrorCategory | None
    errorMessage: str | None = None
    """Human-readable reason the attempt failed (see classification.extract_failure_summary) -
    None for PASSED attempts. Optional/defaulted for backward compatibility with older Hub
    versions that don't send it."""


class HeartbeatPayload(StrictModel):
    deviceReady: bool
    runnerReady: bool


class JobOfferedEnvelope(StrictModel):
    messageId: UUID
    version: Literal[1]
    type: Literal[MessageType.JOB_OFFERED]
    timestamp: datetime
    payload: JobOfferedPayload


class JobProgressEnvelope(StrictModel):
    messageId: UUID
    version: Literal[1]
    type: Literal[MessageType.JOB_PROGRESS]
    timestamp: datetime
    payload: JobProgressPayload


class TestResultEnvelope(StrictModel):
    messageId: UUID
    version: Literal[1]
    type: Literal[MessageType.TEST_RESULT]
    timestamp: datetime
    payload: TestResultPayload


class HeartbeatEnvelope(StrictModel):
    messageId: UUID
    version: Literal[1]
    type: Literal[MessageType.HEARTBEAT]
    timestamp: datetime
    payload: HeartbeatPayload


HubEnvelopeV1 = Annotated[
    Union[JobOfferedEnvelope, JobProgressEnvelope, TestResultEnvelope, HeartbeatEnvelope],
    Field(discriminator="type"),
]

HubEnvelopeAdapter: TypeAdapter[HubEnvelopeV1] = TypeAdapter(HubEnvelopeV1)


def parse_envelope(data: dict | str | bytes) -> HubEnvelopeV1:
    """Parse and strictly validate a wire message against the shared HubEnvelopeV1 contract."""
    if isinstance(data, (str, bytes)):
        return HubEnvelopeAdapter.validate_json(data)
    return HubEnvelopeAdapter.validate_python(data)


def envelope_to_wire_dict(envelope: HubEnvelopeV1) -> dict:
    """Serialize an envelope back to a JSON-compatible dict using wire enum values."""
    return HubEnvelopeAdapter.dump_python(envelope, mode="json")
