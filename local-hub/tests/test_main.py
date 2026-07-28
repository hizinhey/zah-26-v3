"""Regression test for C3: `build_runner` must wire real `screenshot_capturer` and
`reset_appium_session` implementations into the Runner it constructs. Before this fix,
`build_runner` passed `evidence_uploader` but left both of those as their `None` defaults,
which silently made the entire evidence pipeline dead code
(`Runner._capture_and_upload_evidence` short-circuits immediately when `screenshot_capturer`
is `None`) and meant the one infrastructure retry never reset the Appium session.
"""

from __future__ import annotations

import time
from pathlib import Path

from opshub_hub.config import HubConfig
from opshub_hub.main import _heartbeat_while_running, build_runner
from opshub_hub.main import build_web_runner
from opshub_hub.outbox import Outbox
from opshub_hub.transport.failover import FailoverTransport

TEMPLATE_ROOT = Path(__file__).resolve().parents[1] / "templates"


class _FakeTransport:
    def connect(self) -> None:
        pass

    def receive_job(self) -> dict | None:
        return None

    def send(self, envelope: dict) -> None:
        pass

    def heartbeat(self) -> None:
        pass

    def close(self) -> None:
        pass


def test_build_runner_wires_a_real_screenshot_capturer_and_appium_session_resetter(tmp_path):
    wdio_project_root = tmp_path / "wdio-project"
    wdio_project_root.mkdir()
    config = HubConfig(
        backend_url="https://backend.example.test",
        hub_id="hub-1",
        hub_token="token",
        template_root=TEMPLATE_ROOT,
        data_root=tmp_path,
        wdio_project_root=wdio_project_root,
        node_executable=Path("/usr/bin/node"),
    )
    transport = FailoverTransport(ws_transport=_FakeTransport(), polling_transport=_FakeTransport())
    outbox = Outbox(tmp_path / "outbox.sqlite3")

    runner = build_runner(config, transport, outbox)

    assert runner._screenshot_capturer is not None, (
        "build_runner must pass a real screenshot_capturer, or evidence capture is silently dead"
    )
    assert runner._reset_appium_session is not None, (
        "build_runner must pass a real reset_appium_session, or infra retries never reset the session"
    )
    assert runner._wdio_project_root == wdio_project_root, (
        "build_runner must pass config.wdio_project_root through so specs are materialized as a "
        "real, runnable WebdriverIO project rather than a bare directory with no wdio.conf.ts"
    )
    command = runner._command_builder(Path("/tmp/exec/tests/example.spec.ts"))
    assert command[0] == "/usr/bin/node", (
        "build_runner must use the pinned node_executable, not npx/PATH's node - "
        f"got command: {command}"
    )
    assert str(wdio_project_root / "node_modules" / ".bin" / "wdio") in command, (
        f"build_runner must invoke the pinned project's own wdio CLI directly - got command: {command}"
    )


class _CountingTransport:
    def __init__(self) -> None:
        self.heartbeat_count = 0

    def heartbeat(self) -> None:
        self.heartbeat_count += 1


def test_heartbeat_keeps_firing_while_a_simulated_long_running_job_blocks():
    """I2 regression test: previously `transport.heartbeat()` was only called in the
    `job is None` branch of the main loop, so a long-running `runner.run(...)` call (which
    blocks synchronously) never triggered a heartbeat, silently expiring the lease on any real
    execution longer than LeaseService.LEASE_DURATION (60s, backend-side)."""
    transport = _CountingTransport()

    with _heartbeat_while_running(transport, interval=0.02):
        # Simulate a long-running job (runner.run(...) blocking synchronously).
        time.sleep(0.1)

    assert transport.heartbeat_count >= 3, (
        f"expected multiple heartbeats while the job was 'running', got {transport.heartbeat_count}"
    )


def test_build_web_runner_uses_the_pinned_wdio_web_conf_and_screenshot_capturer(tmp_path):
    wdio_project_root = tmp_path / "wdio-project"
    wdio_project_root.mkdir()
    config = HubConfig(
        backend_url="https://backend.example.test",
        hub_id="hub-1",
        hub_token="token",
        template_root=Path(__file__).resolve().parents[1] / "templates",
        data_root=tmp_path,
        platforms=("WEB",),
        wdio_project_root=wdio_project_root,
        node_executable=Path("/usr/bin/node"),
    )
    transport = FailoverTransport(ws_transport=_FakeTransport(), polling_transport=_FakeTransport())
    outbox = Outbox(tmp_path / "outbox.sqlite3")

    runner = build_web_runner(config, transport, outbox)

    assert runner._screenshot_capturer is not None
    assert runner._reset_appium_session is None
    assert runner._wdio_project_root == wdio_project_root, (
        "build_web_runner must pass config.wdio_project_root through, or materialize_execution_dir "
        "never copies wdio.web.conf.ts/tsconfig.json/node_modules into the execution directory"
    )
    assert runner._wdio_config_filename == "wdio.web.conf.ts"
    command = runner._command_builder(Path("/exec/tests/x.spec.ts"))
    assert command[0] == "/usr/bin/node", (
        f"build_web_runner must use the pinned node_executable, not npx/PATH's node - got: {command}"
    )
    assert str(wdio_project_root / "node_modules" / ".bin" / "wdio") in command
    assert "wdio.web.conf.ts" in command
    assert "npx" not in command


def test_run_platform_logs_and_returns_without_raising_when_preflight_fails(tmp_path, caplog):
    from opshub_hub.main import _run_platform

    config = HubConfig(
        backend_url="https://backend.example.test",
        hub_id="hub-1",
        hub_token="token",
        template_root=tmp_path / "nonexistent-templates",
        data_root=tmp_path,
        platforms=("ANDROID",),
        wdio_project_root=tmp_path / "nonexistent-wdio-project",
        node_executable=Path("/usr/bin/node"),
    )

    with caplog.at_level("ERROR"):
        _run_platform(config, "ANDROID")

    assert any("Preflight" in record.message for record in caplog.records), (
        "a failed preflight must log and return, not raise - one platform's broken environment "
        "must not crash the whole multi-platform Hub process"
    )
