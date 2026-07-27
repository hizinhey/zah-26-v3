"""Local Hub entrypoint: wires config, transports, journal, outbox, template
catalog, and the Runner together, then loops receiving and executing jobs.
"""

from __future__ import annotations

import contextlib
import logging
import threading
import time

from opshub_hub.appium_control import AdbScreenshotCapturer, AppiumSessionResetter
from opshub_hub.config import HubConfig, load_config
from opshub_hub.evidence import HttpEvidenceUploader
from opshub_hub.journal import ExecutionJournal
from opshub_hub.models import JobOfferedPayload
from opshub_hub.outbox import Outbox
from opshub_hub.preflight import run_preflight
from opshub_hub.runner import Runner
from opshub_hub.templates import TemplateCatalog
from opshub_hub.transport.failover import FailoverTransport
from opshub_hub.transport.polling_client import PollingTransport
from opshub_hub.transport.websocket_client import WebSocketTransport

logger = logging.getLogger("opshub_hub")


def build_runner(config: HubConfig, transport: FailoverTransport, outbox: Outbox) -> Runner:
    catalog = TemplateCatalog(config.template_root)
    execution_root = config.data_root / "executions"
    evidence_uploader = HttpEvidenceUploader(base_url=config.backend_url, hub_token=config.hub_token)
    # C3 fix: wire real screenshot capture (device-level, via `adb exec-out screencap`) and a
    # real Appium-session reset (via the Appium server's own /sessions HTTP API) into the
    # Runner. Previously neither was passed here, so Runner._capture_and_upload_evidence's
    # `if self._screenshot_capturer is None: return` short-circuited immediately - the entire
    # evidence pipeline (evidence_uploader included) was dead code on the real path, and the
    # single infrastructure retry never reset the Appium session. See appium_control.py's
    # module docstring for why neither of these needs the Hub to hold a live WebDriver session
    # itself (each generated WebdriverIO spec manages its own session in its own subprocess).
    return Runner(
        catalog=catalog,
        execution_root=execution_root,
        launcher=_SubprocessLauncherImpl(),
        outbox=outbox,
        transport=transport,
        evidence_uploader=evidence_uploader,
        screenshot_capturer=AdbScreenshotCapturer(),
        reset_appium_session=AppiumSessionResetter(),
    )


class _SubprocessLauncherImpl:
    """Real subprocess launcher used outside of tests."""

    def run(self, command, cwd, timeout):
        import subprocess

        from opshub_hub.runner import ProcessResult

        try:
            completed = subprocess.run(
                command, cwd=cwd, capture_output=True, text=True, timeout=timeout
            )
            return ProcessResult(returncode=completed.returncode, stdout=completed.stdout, stderr=completed.stderr)
        except subprocess.TimeoutExpired as exc:
            # I3 fix: signal the timeout structurally via ProcessResult.timed_out rather than
            # relying on classification.py's regex patterns matching this synthetic message -
            # none of them did, so this fell through to ASSERTION (never retried) instead of
            # TIMEOUT (retryable).
            return ProcessResult(
                returncode=-1,
                stdout=exc.stdout or "",
                stderr=f"{exc.stderr or ''}\nTimed out after {timeout}s waiting for the spec to finish.",
                timed_out=True,
            )


HEARTBEAT_INTERVAL_SECONDS = 20.0
"""Well under LeaseService.LEASE_DURATION (60s, backend-side) so a heartbeat always lands
comfortably before the lease could expire, even accounting for scheduling jitter."""


@contextlib.contextmanager
def _heartbeat_while_running(transport: FailoverTransport, interval: float = HEARTBEAT_INTERVAL_SECONDS):
    """Keeps sending heartbeats on a background thread for the duration of the `with` block.

    I2 fix: `runner.run(payload)` blocks synchronously for the full duration of a job (multiple
    Appium specs), during which the main loop's `transport.heartbeat()` call (only reached in the
    `job is None` branch) never runs. Since `LeaseService.LEASE_DURATION` is 60 seconds, any real
    execution longer than that lost its lease mid-run. This wraps `runner.run(...)` so heartbeats
    keep firing every `interval` seconds regardless of how long the job takes.
    """
    stop = threading.Event()

    def _beat() -> None:
        while not stop.wait(interval):
            try:
                transport.heartbeat()
            except Exception:
                logger.warning("Heartbeat failed while a job was running; will retry.", exc_info=True)

    thread = threading.Thread(target=_beat, name="opshub-hub-job-heartbeat", daemon=True)
    thread.start()
    try:
        yield
    finally:
        stop.set()
        thread.join(timeout=interval)


def run_forever(config: HubConfig | None = None) -> None:
    config = config or load_config()

    preflight = run_preflight(template_root=config.template_root, data_root=config.data_root)
    if not preflight.ok:
        for failure in preflight.failures():
            logger.error("Preflight check failed: %s (%s)", failure.name, failure.detail)
        raise SystemExit("Preflight checks failed; refusing to start the Local Hub.")

    journal = ExecutionJournal(config.data_root / "journal.sqlite3")
    outbox = Outbox(config.data_root / "outbox.sqlite3")
    transport = FailoverTransport(
        ws_transport=WebSocketTransport(config),
        polling_transport=PollingTransport(config),
    )
    transport.ws_transport.connect()

    runner = build_runner(config, transport, outbox)

    while True:
        outbox.flush(transport)
        job = transport.receive_job()
        if job is None:
            transport.heartbeat()
            time.sleep(1.0)
            continue
        try:
            payload = JobOfferedPayload.model_validate(job.get("payload", job))
        except Exception:
            logger.exception("Rejected an invalid JOB_OFFERED payload from the backend; skipping it.")
            continue
        if not journal.claim(str(payload.executionId), payload.idempotencyKey):
            continue
        with _heartbeat_while_running(transport):
            runner.run(payload)
        journal.complete(str(payload.executionId))


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    run_forever()
