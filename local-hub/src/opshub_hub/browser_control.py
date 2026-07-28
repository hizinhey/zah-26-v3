"""Web (WebdriverIO + Chrome) counterparts to appium_control.py's Android hooks -
command building and evidence capture for the Web execution path, which has no adb
and no Appium server to talk to."""

from __future__ import annotations

from pathlib import Path


def web_command_builder(spec_path: Path) -> list[str]:
    """Matches Runner's `command_builder: Callable[[Path], list[str]]`. Assumes
    wdio.web.conf.ts is present in the job's working directory (Runner sets `cwd` to
    `spec_path.parent.parent`), the same way the Android path assumes wdio.conf.ts is."""
    return ["npx", "wdio", "run", "wdio.web.conf.ts", "--spec", str(spec_path)]


class WebScreenshotCapturer:
    """Locates the screenshot the WebdriverIO subprocess already wrote (via its
    `afterTest` hook, to a fixed `evidence/last-screenshot.png` relative to its own
    working directory) and moves it to the attempt-specific path Runner requests.

    Matches the `Callable[[Path], Path]` signature `runner.Runner` expects for
    `screenshot_capturer`.
    """

    def __call__(self, destination: Path) -> Path:
        source = destination.parent / "last-screenshot.png"
        if not source.exists():
            raise FileNotFoundError(
                f"wdio.web.conf.ts did not write a screenshot to {source}; check its afterTest hook."
            )
        destination.parent.mkdir(parents=True, exist_ok=True)
        source.replace(destination)
        return destination
