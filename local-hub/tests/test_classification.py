from opshub_hub.classification import FailureCategory, classify_failure


def test_mocha_assertion_failure_is_classified_as_assertion():
    stdout = (
        "1 passing\n1 failing\n\n"
        "1) OA delivery test\n   expected 'Hello' to equal 'Goodbye'\n"
        "   AssertionError: expected 'Hello' to equal 'Goodbye'\n"
    )
    category = classify_failure(returncode=1, stdout=stdout, stderr="")
    assert category is FailureCategory.ASSERTION


def test_appium_session_error_is_classified_as_infrastructure():
    stderr = "Error: Could not create a new session. Appium unreachable at http://127.0.0.1:4723"
    category = classify_failure(returncode=1, stdout="", stderr=stderr)
    assert category is FailureCategory.INFRASTRUCTURE


def test_device_not_found_is_classified_as_infrastructure():
    stderr = "Error: No device found with id emulator-5554; adb server error"
    category = classify_failure(returncode=1, stdout="", stderr=stderr)
    assert category is FailureCategory.INFRASTRUCTURE


def test_network_econnrefused_is_classified_as_infrastructure():
    stderr = "connect ECONNREFUSED 127.0.0.1:4723"
    category = classify_failure(returncode=1, stdout="", stderr=stderr)
    assert category is FailureCategory.INFRASTRUCTURE


def test_generic_nonzero_exit_without_infra_signals_is_assertion():
    category = classify_failure(returncode=1, stdout="Error: element not found in time", stderr="")
    assert category is FailureCategory.ASSERTION
