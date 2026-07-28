"""Tests for opshub_hub.models against contracts/schemas/hub-envelope-v1.json.

Focused on the OrderedTestCases contract: JOB_OFFERED.payload.testCases must be
exactly 5 items with a fixed order/templateId per position, matching the schema's
prefixItems (contracts/schemas/hub-envelope-v1.json:86-123). These tests verify a
malformed offer is rejected at parse time, not merely mishandled downstream.
"""

from __future__ import annotations

import uuid
from copy import deepcopy

import pytest
from pydantic import ValidationError

from opshub_hub.models import (
    ORDERED_TEST_CASE_TEMPLATE_IDS,
    ORDERED_TEST_CASE_TEMPLATE_IDS_BY_PLATFORM,
    parse_envelope,
)


def _template_parameters() -> dict:
    return {
        "oaName": "Sample OA",
        "thumbnailUrl": "https://example.com/thumb.png",
        "expectedHeader": "Header",
        "expectedBody": "Body",
        "expectedButtonText": "Tap here",
        "expectedRedirectUrl": "https://example.com/redirect",
        "expectedRedirectDomain": "example.com",
    }


def _test_case(oa_order: int, order: int, template_id: str) -> dict:
    return {
        "testCaseId": str(uuid.uuid4()),
        "oaOrder": oa_order,
        "oaName": f"OA {oa_order}",
        "order": order,
        "templateId": template_id,
        "templateVersion": 1,
        "parameters": _template_parameters(),
    }


def _oa_group(oa_order: int) -> list[dict]:
    return [
        _test_case(oa_order, index + 1, template_id)
        for index, template_id in enumerate(ORDERED_TEST_CASE_TEMPLATE_IDS)
    ]


def _valid_job_offered_envelope(num_oas: int = 1) -> dict:
    test_cases: list[dict] = []
    for oa_order in range(1, num_oas + 1):
        test_cases.extend(_oa_group(oa_order))
    return {
        "messageId": str(uuid.uuid4()),
        "version": 1,
        "type": "JOB_OFFERED",
        "timestamp": "2026-07-27T00:00:00Z",
        "payload": {
            "executionId": str(uuid.uuid4()),
            "idempotencyKey": "idem-1",
            "revision": 1,
            "platform": "ANDROID",
            "testCases": test_cases,
            "leaseToken": str(uuid.uuid4()),
        },
    }


def test_valid_job_offered_envelope_parses():
    envelope = parse_envelope(_valid_job_offered_envelope())
    assert envelope.payload.testCases[0].templateId == "android-oa-delivery-v1"
    assert len(envelope.payload.testCases) == 5


def test_valid_multi_oa_job_offered_envelope_parses():
    envelope = parse_envelope(_valid_job_offered_envelope(num_oas=2))
    assert len(envelope.payload.testCases) == 10
    assert [case.oaOrder for case in envelope.payload.testCases] == [1, 1, 1, 1, 1, 2, 2, 2, 2, 2]


def test_job_offered_with_too_few_test_cases_is_rejected():
    data = deepcopy(_valid_job_offered_envelope())
    data["payload"]["testCases"] = data["payload"]["testCases"][:4]
    with pytest.raises(ValidationError):
        parse_envelope(data)


def test_job_offered_with_a_count_not_a_multiple_of_five_is_rejected():
    data = deepcopy(_valid_job_offered_envelope(num_oas=2))
    data["payload"]["testCases"].append(_test_case(3, 1, "android-oa-delivery-v1"))
    with pytest.raises(ValidationError):
        parse_envelope(data)


def test_job_offered_with_wrong_template_id_at_position_is_rejected():
    data = deepcopy(_valid_job_offered_envelope())
    # Swap position 0's templateId for position 1's, breaking the fixed mapping.
    data["payload"]["testCases"][0]["templateId"] = "android-thumbnail-v1"
    with pytest.raises(ValidationError):
        parse_envelope(data)


def test_job_offered_with_wrong_order_at_position_is_rejected():
    data = deepcopy(_valid_job_offered_envelope())
    data["payload"]["testCases"][0]["order"] = 2
    with pytest.raises(ValidationError):
        parse_envelope(data)


def test_job_offered_with_shuffled_test_cases_is_rejected():
    data = deepcopy(_valid_job_offered_envelope())
    test_cases = data["payload"]["testCases"]
    test_cases[0], test_cases[1] = test_cases[1], test_cases[0]
    with pytest.raises(ValidationError):
        parse_envelope(data)


def test_job_offered_with_wrong_oa_order_grouping_is_rejected():
    data = deepcopy(_valid_job_offered_envelope(num_oas=2))
    # Second group should be oaOrder=2; make it 3 to break contiguous grouping.
    for case in data["payload"]["testCases"][5:]:
        case["oaOrder"] = 3
    with pytest.raises(ValidationError):
        parse_envelope(data)


def test_job_offered_payload_accepts_web_platform_with_web_template_ids():
    data = deepcopy(_valid_job_offered_envelope())
    data["payload"]["platform"] = "WEB"
    data["payload"]["testCases"] = [
        _test_case(1, index + 1, template_id)
        for index, template_id in enumerate(ORDERED_TEST_CASE_TEMPLATE_IDS_BY_PLATFORM["WEB"])
    ]
    envelope = parse_envelope(data)
    assert envelope.payload.platform == "WEB"
    assert envelope.payload.testCases[0].templateId == "web-oa-delivery-v1"
    assert len(envelope.payload.testCases) == 5


def test_job_offered_payload_rejects_android_template_ids_for_a_web_platform_job():
    data = deepcopy(_valid_job_offered_envelope())
    data["payload"]["platform"] = "WEB"
    # testCases still use android-*-v1 ids, which must not validate against a WEB job.
    with pytest.raises(ValidationError):
        parse_envelope(data)
