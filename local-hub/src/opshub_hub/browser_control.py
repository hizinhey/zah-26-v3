"""Web (WebdriverIO + Chrome) counterpart to appium_control.py's Android hooks -
evidence capture for the Web execution path, which has no adb and no Appium server to
talk to. Command building for Web uses the same pinned-Node/pinned-project mechanism as
Android (see `runner.build_wdio_command_builder`, `main.build_web_runner`) rather than a
Web-specific builder - `npx` with no pinned project/Node cannot find a config file to
run and has no guarantee of a new-enough `node` on `PATH`."""

from __future__ import annotations

from pathlib import Path


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
