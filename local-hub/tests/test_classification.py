from opshub_hub.classification import (
    MAX_FAILURE_SUMMARY_LENGTH,
    FailureCategory,
    classify_failure,
    extract_failure_summary,
)


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


def test_timed_out_flag_is_classified_as_timeout_regardless_of_message_text(): # I3 regression
    # The synthetic message main.py's launcher produces on subprocess.TimeoutExpired doesn't
    # match any INFRASTRUCTURE pattern - the structural `timed_out=True` flag is what must
    # drive the classification, not the message text.
    stderr = "\nTimed out after 30s waiting for the spec to finish."
    category = classify_failure(returncode=-1, stdout="", stderr=stderr, timed_out=True)
    assert category is FailureCategory.TIMEOUT


def test_timed_out_flag_wins_even_if_message_also_looks_like_infrastructure():
    stderr = "session not created\nTimed out after 30s waiting for the spec to finish."
    category = classify_failure(returncode=-1, stdout="", stderr=stderr, timed_out=True)
    assert category is FailureCategory.TIMEOUT


def test_extract_failure_summary_returns_the_last_meaningful_line():
    stdout = (
        "1 passing\n1 failing\n\n"
        "1) OA delivery test\n   expected 'Hello' to equal 'Goodbye'\n"
        "   AssertionError: expected 'Hello' to equal 'Goodbye'\n\n\n"
    )
    summary = extract_failure_summary(stdout=stdout, stderr="")
    assert summary == "AssertionError: expected 'Hello' to equal 'Goodbye'"


def test_extract_failure_summary_ignores_trailing_blank_lines_in_stderr():
    stdout = ""
    stderr = "Error: expected non-empty content, got ''\n\n   \n"
    summary = extract_failure_summary(stdout=stdout, stderr=stderr)
    assert summary == "Error: expected non-empty content, got ''"


def test_extract_failure_summary_is_empty_when_no_output_at_all():
    assert extract_failure_summary(stdout="", stderr="") == ""


def test_extract_failure_summary_truncates_a_very_long_line():
    long_line = "x" * (MAX_FAILURE_SUMMARY_LENGTH + 200)
    summary = extract_failure_summary(stdout=long_line, stderr="")
    assert len(summary) == MAX_FAILURE_SUMMARY_LENGTH


def test_extract_failure_summary_reports_timeout_message_regardless_of_output():
    summary = extract_failure_summary(stdout="", stderr="session not created", timed_out=True)
    assert summary == "Timed out waiting for the spec to finish."
