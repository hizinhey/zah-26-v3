"""Real, device/Appium-server-level implementations of the Runner's
`screenshot_capturer` and `reset_appium_session` hooks (see runner.py's
`Protocol` definitions).

Neither hook requires the Hub process to hold a live WebDriver/Appium *session*
object - each generated WebdriverIO spec creates and tears down its own Appium
session inside its own Node subprocess (see runner.py's module docstring and
opshub_hub.templates); the Hub itself never drives the app directly. Both
implementations below instead talk to infrastructure the Hub already assumes is
present and reachable (the same `adb` and Appium-server HTTP endpoint preflight.py
already checks in `run_preflight`):

- `AdbScreenshotCapturer` shells out to `adb exec-out screencap -p`, which captures
  whatever is currently on the connected device's screen regardless of which
  process (or subprocess) is driving it. This is a real screenshot mechanism, not
  a mock - it just doesn't depend on a Python-held Appium session.
- `reset_appium_sessions` calls the Appium server's own session-management HTTP
  API (`GET /sessions`, `DELETE /session/{id}`) to force-close any sessions left
  open after a subprocess crashes mid-spec, so the next attempt starts clean. This
  is the real "Appium session reset" the Task 8 plan asks for; it does not require
  the Hub to have created the session itself.
"""

from __future__ import annotations

import logging
import subprocess
from pathlib import Path

logger = logging.getLogger("opshub_hub.appium_control")

DEFAULT_APPIUM_BASE_URL = "http://127.0.0.1:4723"


class AdbScreenshotCapturer:
    """Captures the connected Android device's current screen via `adb exec-out
    screencap -p`, writing the PNG bytes to the requested destination path.

    Matches the `Callable[[Path], Path]` signature `runner.Runner` expects for
    `screenshot_capturer`.
    """

    def __init__(self, adb_path: str = "adb", timeout: float = 10.0):
        self._adb_path = adb_path
        self._timeout = timeout

    def __call__(self, destination: Path) -> Path:
        destination.parent.mkdir(parents=True, exist_ok=True)
        completed = subprocess.run(
            [self._adb_path, "exec-out", "screencap", "-p"],
            capture_output=True,
            timeout=self._timeout,
            check=True,
        )
        destination.write_bytes(completed.stdout)
        return destination


class AppiumSessionResetter:
    """Force-closes any Appium sessions left open on the Appium server between
    the one infrastructure retry Runner performs, so the retried attempt's spec
    starts a fresh WebdriverIO session rather than colliding with a half-dead one
    left behind by a crashed subprocess.

    Matches the `AppiumSessionResetter` Protocol (`__call__() -> None`) runner.py
    declares for `reset_appium_session`.
    """

    def __init__(self, base_url: str = DEFAULT_APPIUM_BASE_URL, timeout: float = 10.0):
        self._base_url = base_url.rstrip("/")
        self._timeout = timeout

    def __call__(self) -> None:
        import httpx

        try:
            response = httpx.get(f"{self._base_url}/sessions", timeout=self._timeout)
            response.raise_for_status()
            sessions = response.json().get("value", [])
        except Exception:
            logger.warning("Could not list Appium sessions at %s to reset them.", self._base_url, exc_info=True)
            return

        for session in sessions:
            session_id = session.get("id")
            if not session_id:
                continue
            try:
                httpx.delete(f"{self._base_url}/session/{session_id}", timeout=self._timeout)
            except Exception:
                logger.warning("Could not close leftover Appium session %s.", session_id, exc_info=True)
