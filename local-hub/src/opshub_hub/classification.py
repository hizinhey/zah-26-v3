"""Classifies a failed test spec run as an ASSERTION failure (the OA under test is
genuinely broken/misconfigured) or an INFRASTRUCTURE failure (Appium/device/session/
network trouble unrelated to the OA). Only INFRASTRUCTURE failures are retried.
"""

from __future__ import annotations

import re
from enum import Enum

# Signals that the WebdriverIO/Mocha runner itself could not exercise the OA,
# rather than the OA failing an assertion. Matched case-insensitively against
# combined stdout+stderr.
_INFRASTRUCTURE_PATTERNS: tuple[re.Pattern[str], ...] = tuple(
    re.compile(pattern, re.IGNORECASE)
    for pattern in (
        r"could not (?:start|create|connect to) a?n? ?(?:new )?session",
        r"session not created",
        r"\bappium\b.*(?:not reachable|connection refused|crashed|unreachable)",
        r"econnrefused",
        r"econnreset",
        r"etimedout",
        r"socket hang up",
        r"no (?:such )?device",
        r"device (?:not found|offline|unauthorized)",
        r"\badb\b.*(?:error|not found|server)",
        r"unable to connect to (?:the )?(?:device|appium)",
        r"network (?:error|unreachable|timeout)",
        r"webdriver(?:io)? session (?:closed|terminated|not found)",
        r"invalid session id",
        r"waitfordriver timed out",
        r"failed to spawn",
    )
)


class FailureCategory(str, Enum):
    ASSERTION = "ASSERTION"
    INFRASTRUCTURE = "INFRASTRUCTURE"
    TIMEOUT = "TIMEOUT"


MAX_FAILURE_SUMMARY_LENGTH = 500
"""Caps how much of a spec's stdout/stderr rides in TestResultPayload.errorMessage - the full
output already lives in the per-attempt log file (see Runner._execute_attempt's log_path); this
is only meant to answer "why did it fail" at a glance, not replace the log."""


def extract_failure_summary(*, stdout: str, stderr: str, timed_out: bool = False) -> str:
    """Best-effort, human-readable reason a spec failed, for TestResultPayload.errorMessage.

    Mocha/WebdriverIO print the actual assertion/error message as the last meaningful line of
    combined stdout+stderr (e.g. "AssertionError: expected 'Open' to equal ''" for a genuinely
    empty/missing OA field, or a WebDriver/Appium error for infrastructure trouble) - blank lines
    and pure whitespace are common right before process exit and would otherwise win as "the last
    line". Truncated to MAX_FAILURE_SUMMARY_LENGTH so a runaway stack trace can't bloat the wire
    payload; the untruncated output is always in the attempt's own log file.
    """
    if timed_out:
        return "Timed out waiting for the spec to finish."
    combined = f"{stdout}\n{stderr}"
    lines = [line.strip() for line in combined.splitlines() if line.strip()]
    if not lines:
        return ""
    return lines[-1][:MAX_FAILURE_SUMMARY_LENGTH]


def classify_failure(*, returncode: int, stdout: str, stderr: str, timed_out: bool = False) -> FailureCategory:
    """Classify a non-zero-exit spec run.

    Only called when the process did not exit cleanly. Anything that looks like
    Appium/session/device/network trouble is INFRASTRUCTURE (eligible for one
    retry); everything else — including plain Mocha assertion failures — is
    ASSERTION (never retried).

    `timed_out=True` (I3 fix) is a structural signal from the launcher - not a string
    pattern match - that the spec subprocess was killed for exceeding its timeout. This is
    checked first and unconditionally maps to TIMEOUT (retryable, like INFRASTRUCTURE),
    regardless of whatever text ended up in stdout/stderr, since relying on the launcher's
    synthetic "Timed out after {timeout}s..." stderr message to also match one of the
    INFRASTRUCTURE patterns above was fragile and previously fell through to ASSERTION
    (never retried) because none of those patterns matched it.
    """
    if timed_out:
        return FailureCategory.TIMEOUT
    combined = f"{stdout}\n{stderr}"
    for pattern in _INFRASTRUCTURE_PATTERNS:
        if pattern.search(combined):
            return FailureCategory.INFRASTRUCTURE
    return FailureCategory.ASSERTION
