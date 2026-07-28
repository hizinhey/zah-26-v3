from pathlib import Path

import pytest

from opshub_hub.browser_control import WebScreenshotCapturer, web_command_builder


def test_web_command_builder_runs_wdio_with_the_web_config():
    command = web_command_builder(Path("/exec/tests/web-oa-delivery-v1.spec.ts"))
    assert command == [
        "npx", "wdio", "run", "wdio.web.conf.ts", "--spec", "/exec/tests/web-oa-delivery-v1.spec.ts",
    ]


def test_screenshot_capturer_moves_the_fixed_screenshot_to_the_requested_destination(tmp_path):
    evidence_dir = tmp_path / "evidence"
    evidence_dir.mkdir()
    source = evidence_dir / "last-screenshot.png"
    source.write_bytes(b"fake-png-bytes")
    destination = evidence_dir / "test-case-1-attempt1.png"

    capturer = WebScreenshotCapturer()
    result = capturer(destination)

    assert result == destination
    assert destination.read_bytes() == b"fake-png-bytes"
    assert not source.exists()


def test_screenshot_capturer_raises_when_wdio_never_wrote_a_screenshot(tmp_path):
    evidence_dir = tmp_path / "evidence"
    evidence_dir.mkdir()
    destination = evidence_dir / "test-case-1-attempt1.png"

    capturer = WebScreenshotCapturer()

    with pytest.raises(FileNotFoundError):
        capturer(destination)
