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


def classify_failure(*, returncode: int, stdout: str, stderr: str) -> FailureCategory:
    """Classify a non-zero-exit spec run.

    Only called when the process did not exit cleanly. Anything that looks like
    Appium/session/device/network trouble is INFRASTRUCTURE (eligible for one
    retry); everything else — including plain Mocha assertion failures — is
    ASSERTION (never retried).
    """
    combined = f"{stdout}\n{stderr}"
    for pattern in _INFRASTRUCTURE_PATTERNS:
        if pattern.search(combined):
            return FailureCategory.INFRASTRUCTURE
    return FailureCategory.ASSERTION
