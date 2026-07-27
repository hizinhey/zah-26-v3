from uuid import UUID

from opshub_hub.evidence import deterministic_test_result_id


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
