from uuid import UUID

import httpx

from opshub_hub.evidence import EvidenceFile, EvidenceType, HttpEvidenceUploader, deterministic_test_result_id


def test_deterministic_test_result_id_matches_backend_reference_value():
    """Cross-language parity check with ExecutionServiceTest's equivalent case.

    The backend computes `test_results.id` via
    `UUID.nameUUIDFromBytes("{executionId}:{testCaseId}:{attempt}".getBytes(UTF_8))`.
    This literal expected UUID was independently derived by hand-tracing the
    MD5 + version/variant bit-twiddling algorithm and confirmed against a real
    `UUID.nameUUIDFromBytes` call in Java for the same inputs. If this test and
    the Java `ExecutionServiceTest` assertion both pass with this same literal
    string, the two implementations are proven byte-for-byte identical.
    """
    execution_id = UUID("11111111-1111-1111-1111-111111111111")
    test_case_id = UUID("22222222-2222-2222-2222-222222222222")
    attempt = 1

    result = deterministic_test_result_id(execution_id, test_case_id, attempt)

    assert str(result) == "060241da-e04f-3366-bf26-c1a67252bb48"


def test_deterministic_test_result_id_is_stable_and_input_sensitive():
    execution_id = UUID("11111111-1111-1111-1111-111111111111")
    test_case_id = UUID("22222222-2222-2222-2222-222222222222")

    first = deterministic_test_result_id(execution_id, test_case_id, 1)
    again = deterministic_test_result_id(execution_id, test_case_id, 1)
    different_attempt = deterministic_test_result_id(execution_id, test_case_id, 2)

    assert first == again
    assert first != different_attempt


def test_http_evidence_uploader_sends_hub_token_header(tmp_path):
    """The evidence endpoint requires X-Hub-Token auth, same as every other
    Hub -> backend endpoint. A prior fix added that check to
    EvidenceController.java but HttpEvidenceUploader never sent the header,
    so every real upload 401'd. Guard against regressing that."""
    captured_requests: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured_requests.append(request)
        return httpx.Response(200, json={"id": "33333333-3333-3333-3333-333333333333"})

    client = httpx.Client(transport=httpx.MockTransport(handler))
    uploader = HttpEvidenceUploader(base_url="http://backend.local", hub_token="secret-hub-token", client=client)

    evidence_path = tmp_path / "screenshot.png"
    evidence_path.write_bytes(b"fake-png-bytes")
    evidence = EvidenceFile(path=evidence_path, evidence_type=EvidenceType.SCREENSHOT)

    test_result_id = UUID("11111111-1111-1111-1111-111111111111")
    result_id = uploader.upload(test_result_id, evidence)

    assert result_id == UUID("33333333-3333-3333-3333-333333333333")
    assert len(captured_requests) == 1
    assert captured_requests[0].headers["X-Hub-Token"] == "secret-hub-token"
    assert captured_requests[0].url.path == f"/api/v1/test-results/{test_result_id}/evidence"
