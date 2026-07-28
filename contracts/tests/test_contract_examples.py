import json
import tomllib
from copy import deepcopy
from pathlib import Path

import pytest
import yaml
from jsonschema.validators import validator_for


CONTRACTS_ROOT = Path(__file__).parents[1]
TEMPLATE_IDS = [
    "android-oa-delivery-v1",
    "android-thumbnail-v1",
    "android-content-v1",
    "android-button-text-v1",
    "android-redirect-v1",
]


def job_offered_payload():
    return {
        "executionId": "186a56af-d743-4ffd-8e68-4d034d2927a6",
        "idempotencyKey": "run-186a56af-d743-4ffd-8e68-4d034d2927a6",
        "revision": 7,
        "platform": "ANDROID",
        "leaseToken": "9e2f7c3a-1b4d-4a5e-8f6a-2c7d5e9b1a3f",
        "testCases": [
            {
                "testCaseId": f"00000000-0000-4000-8000-00000000000{order}",
                "oaOrder": 1,
                "oaName": "zBusiness",
                "order": order,
                "templateId": template_id,
                "templateVersion": 1,
                "parameters": {
                    "oaName": "zBusiness",
                    "thumbnailUrl": "https://example.test/thumb.png",
                    "expectedHeader": "Header",
                    "expectedBody": "Body",
                    "expectedButtonText": "Open now",
                    "expectedRedirectUrl": "https://example.test/path",
                    "expectedRedirectDomain": "example.test",
                },
            }
            for order, template_id in enumerate(TEMPLATE_IDS, start=1)
        ],
    }


def job_offered_envelope():
    return {
        "messageId": "ac4e9772-5f04-40d0-a32c-323c0b93ceff",
        "version": 1,
        "type": "JOB_OFFERED",
        "timestamp": "2026-07-26T00:00:00Z",
        "payload": job_offered_payload(),
    }


@pytest.fixture
def schema():
    with (CONTRACTS_ROOT / "schemas" / "hub-envelope-v1.json").open() as schema_file:
        return json.load(schema_file)


@pytest.fixture
def validator():
    def build(schema):
        validator_class = validator_for(schema)
        validator_class.check_schema(schema)
        return validator_class(schema)

    return build


@pytest.fixture
def openapi():
    with (CONTRACTS_ROOT / "openapi" / "opshub-v1.yaml").open() as contract_file:
        return yaml.safe_load(contract_file)


@pytest.fixture(
    params=[
        job_offered_envelope(),
        {
            "messageId": "bbf717c1-1ff3-4b85-8d01-b67a7ce545f5",
            "version": 1,
            "type": "JOB_PROGRESS",
            "timestamp": "2026-07-26T00:00:01Z",
            "payload": {
                "executionId": "186a56af-d743-4ffd-8e68-4d034d2927a6",
                "testCaseId": "d1008cf0-12e3-42a1-9c14-a0f5af990908",
                "status": "RUNNING",
                "message": "Launching Zalo",
            },
        },
        {
            "messageId": "6e5608c9-a155-4f3d-9d26-1a6a6b2e470a",
            "version": 1,
            "type": "TEST_RESULT",
            "timestamp": "2026-07-26T00:00:02Z",
            "payload": {
                "executionId": "186a56af-d743-4ffd-8e68-4d034d2927a6",
                "testCaseId": "d1008cf0-12e3-42a1-9c14-a0f5af990908",
                "attempt": 1,
                "status": "PASSED",
                "durationMs": 1200,
                "errorCategory": None,
            },
        },
        {
            "messageId": "5065f2e9-f4af-4a7f-9c4d-2e667e1096be",
            "version": 1,
            "type": "HEARTBEAT",
            "timestamp": "2026-07-26T00:00:03Z",
            "payload": {"deviceReady": True, "runnerReady": True},
        },
    ],
    ids=["job_offered", "job_progress", "test_result", "heartbeat"],
)
def valid_hub_envelope(request):
    return request.param


def test_hub_envelope_examples_validate(schema, validator, valid_hub_envelope):
    assert not list(validator(schema).iter_errors(valid_hub_envelope))


def test_hub_envelope_requires_message_id(schema, validator):
    payload = {
        "version": 1,
        "type": "HEARTBEAT",
        "timestamp": "2026-07-26T00:00:00Z",
        "payload": {},
    }
    assert list(validator(schema).iter_errors(payload))


def test_job_offered_requires_exactly_five_fixed_cases(schema, validator):
    payload = job_offered_envelope()
    payload["payload"]["testCases"].pop()

    assert list(validator(schema).iter_errors(payload))


def test_job_offered_requires_order_within_one_to_five(schema, validator):
    # The JSON Schema layer bounds `order` to 1-5 per item; the fixed order-per-position
    # mapping within an OA's group of 5 is enforced at the code layer (see
    # local-hub/src/opshub_hub/models.py JobOfferedPayload.validate_ordered_test_cases and
    # local-hub/tests/test_models.py::test_job_offered_with_wrong_order_at_position_is_rejected),
    # since JSON Schema alone cannot express "grouped by oaOrder, order 1-5 within each group".
    payload = deepcopy(job_offered_envelope())
    payload["payload"]["testCases"][0]["order"] = 6

    assert list(validator(schema).iter_errors(payload))


def test_job_offered_allows_multiple_oa_groups_of_five(schema, validator):
    payload = deepcopy(job_offered_envelope())
    second_group = deepcopy(payload["payload"]["testCases"])
    for index, case in enumerate(second_group):
        case["testCaseId"] = f"00000000-0000-4000-8000-00000000010{index}"
        case["oaOrder"] = 2
        case["oaName"] = "Other Business"
    payload["payload"]["testCases"].extend(second_group)

    assert not list(validator(schema).iter_errors(payload))


def test_job_offered_requires_the_fixed_template_set(schema, validator):
    payload = deepcopy(job_offered_envelope())
    payload["payload"]["testCases"][4]["templateId"] = "android-other-v1"

    assert list(validator(schema).iter_errors(payload))


def test_operation_oa_endpoints_match_the_persisted_android_contract(openapi):
    operation_path = openapi["paths"]["/api/v1/operations/{operationId}"]
    replace_oas = openapi["paths"]["/api/v1/operations/{operationId}/oas"]["put"]
    schemas = openapi["components"]["schemas"]

    assert "put" not in operation_path
    assert replace_oas["requestBody"]["content"]["application/json"]["schema"] == {
        "$ref": "#/components/schemas/ReplaceOasRequest"
    }
    assert replace_oas["responses"]["409"]["content"]["application/json"]["schema"] == {
        "$ref": "#/components/schemas/RevisionConflictError"
    }
    assert schemas["Operation"]["required"] == [
        "id", "jiraId", "revision", "status", "createdAt", "updatedAt", "oas"
    ]
    assert schemas["ReplaceOasRequest"]["required"] == ["expectedRevision", "oas"]
    assert schemas["ReplaceOasRequest"]["properties"]["expectedRevision"] == {
        "$ref": "#/components/schemas/Revision"
    }
    assert schemas["OfficialAccountInput"]["properties"]["platform"] == {"enum": ["ANDROID", "WEB"]}
    assert schemas["RevisionConflictError"]["required"] == ["code", "message", "currentRevision"]
    assert schemas["RevisionConflictError"]["properties"]["code"] == {"const": "REVISION_CONFLICT"}


def test_contract_test_dependencies_include_pyyaml():
    with (CONTRACTS_ROOT.parent / "local-hub" / "pyproject.toml").open("rb") as project_file:
        project = tomllib.load(project_file)

    assert "PyYAML>=6.0,<7" in project["project"]["optional-dependencies"]["dev"]
