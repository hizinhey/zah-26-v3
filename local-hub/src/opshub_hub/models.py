"""Typed protocol models mirroring contracts/schemas/hub-envelope-v1.json exactly.

Extra fields are forbidden and enum values must match the contract verbatim.
"""

from __future__ import annotations

from datetime import datetime
from enum import Enum
from typing import Annotated, Literal, Union
from uuid import UUID

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter


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
    order: int = Field(ge=1)
    templateId: str = Field(min_length=1)
    templateVersion: int = Field(ge=1)
    parameters: TemplateParametersV1


class JobOfferedPayload(StrictModel):
    executionId: UUID
    idempotencyKey: str = Field(min_length=1)
    revision: int = Field(ge=1)
    platform: Literal["ANDROID"]
    testCases: list[TestCase]
    leaseToken: UUID


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
