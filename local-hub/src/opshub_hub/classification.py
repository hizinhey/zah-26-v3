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


_INSTANCE_TAG_PATTERN = re.compile(r"^\[[^\]]*\]\s*")
"""Strips a wdio multi-remote instance tag (e.g. "[0-0] ") that prefixes every line of output."""

_STACK_FRAME_PATTERN = re.compile(r"^at\s")
"""Matches a printed JS stack frame line (e.g. "at async Runner.run (...)"), after the instance
tag has been stripped. Mocha/WebdriverIO print these *after* the actual assertion/error message,
so they - not the assertion - end up as "the last line" of output and must be skipped when
looking for the failure reason."""

_FRAMEWORK_LOG_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d+Z\s+(?:INFO|WARN|ERROR|DEBUG)\b")
"""Matches wdio/webdriver/appium-service's own timestamped bookkeeping logs (e.g. "INFO
@wdio/appium-service: Killing entire Appium tree"), which keep printing to stdout *after* the
Mocha reporter's actual failure block as the runner tears the session down - so without this
filter they, not the assertion, would win as "the last line"."""

_SUMMARY_FOOTER_PATTERN = re.compile(r"^Spec Files:\s")
"""Matches the reporter's trailing run-summary line (e.g. "Spec Files:  0 passed, 1 failed, 1
total ..."), which prints *after* the actual failure block and would otherwise win as "the last
line" instead of the assertion."""


def extract_failure_summary(*, stdout: str, stderr: str, timed_out: bool = False) -> str:
    """Best-effort, human-readable reason a spec failed, for TestResultPayload.errorMessage.

    Mocha/WebdriverIO print the actual assertion/error message (e.g. "AssertionError: expected
    'Open' to equal ''" for a genuinely empty/missing OA field, or a WebDriver/Appium error for
    infrastructure trouble) followed by a stack trace - so the last meaningful line is usually an
    internal stack frame, not the assertion itself. Blank lines and pure whitespace are common
    right before process exit and would otherwise win as "the last line" too. Truncated to
    MAX_FAILURE_SUMMARY_LENGTH so a runaway stack trace can't bloat the wire payload; the
    untruncated output is always in the attempt's own log file.
    """
    if timed_out:
        return "Timed out waiting for the spec to finish."

    def _clean(text: str) -> list[str]:
        lines = [_INSTANCE_TAG_PATTERN.sub("", line.strip()) for line in text.splitlines() if line.strip()]
        return [line for line in lines if line and not _STACK_FRAME_PATTERN.match(line)]

    # Mocha/WebdriverIO print the actual assertion/error message to stdout (the "spec" reporter
    # output); stderr is mostly benign process noise (e.g. the ConfigParser "pattern ... did not
    # match any file" warning, which fires on every run regardless of pass/fail). Concatenating
    # stdout+stderr and taking the last line let that stderr noise silently win over the real
    # stdout failure reason - stderr is only a fallback for cases where the process died before
    # the reporter ever printed anything (genuine infrastructure trouble with no stdout at all).
    stdout_lines, stderr_lines = _clean(stdout), _clean(stderr)

    def _drop_noise(candidate: list[str]) -> list[str]:
        return [
            line
            for line in candidate
            if not _FRAMEWORK_LOG_PATTERN.match(line) and not _SUMMARY_FOOTER_PATTERN.match(line)
        ]

    # Prefer the reporter's own output over the runner's timestamped teardown/bookkeeping logs
    # (e.g. "INFO @wdio/appium-service: ...") and the trailing "Spec Files: ..." summary, both of
    # which print to stdout *after* the failure block as the session shuts down. Fall back to the
    # noisy lines only if nothing else is available.
    lines = _drop_noise(stdout_lines) or _drop_noise(stderr_lines) or stdout_lines or stderr_lines
    if not lines:
        return ""
    # A bare "Received: ..." last line (Jest-style expect().toBe() diff output) is meaningless
    # on its own - the server needs to see what was actually expected too. When the preceding
    # line is the matching "Expected: ...", report both together as one clear sentence instead
    # of just the last line.
    if lines[-1].startswith("Received:") and len(lines) >= 2 and lines[-2].startswith("Expected:"):
        summary = f"{lines[-2]}; {lines[-1]}"
    else:
        summary = lines[-1]
    return summary[:MAX_FAILURE_SUMMARY_LENGTH]


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
