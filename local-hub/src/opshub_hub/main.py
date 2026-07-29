"""Local Hub entrypoint: wires config, transports, journal, outbox, template
catalog, and the Runner together for each configured platform, running them
concurrently - one thread per platform, each fully isolated from the others.
"""

from __future__ import annotations

import contextlib
import logging
import threading
import time

from opshub_hub.appium_control import AdbScreenshotCapturer, AppiumSessionResetter
from opshub_hub.browser_control import WebScreenshotCapturer
from opshub_hub.config import HubConfig, load_config
from opshub_hub.evidence import HttpEvidenceUploader
from opshub_hub.journal import ExecutionJournal
from opshub_hub.models import JobOfferedPayload
from opshub_hub.outbox import Outbox
from opshub_hub.preflight import run_preflight, run_web_preflight
from opshub_hub.runner import Runner, build_wdio_command_builder
from opshub_hub.templates import TemplateCatalog
from opshub_hub.transport.failover import FailoverTransport
from opshub_hub.transport.polling_client import PollingTransport
from opshub_hub.transport.websocket_client import WebSocketTransport

logger = logging.getLogger("opshub_hub")


def build_runner(config: HubConfig, transport: FailoverTransport, outbox: Outbox) -> Runner:
    catalog = TemplateCatalog(config.platform_template_root("ANDROID"))
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
    # Node-version fix: run the pinned WebdriverIO CLI (config.wdio_project_root's own
    # node_modules/.bin/wdio) via the pinned config.node_executable, instead of the previous
    # `npx wdio run` default - which cold-bootstrapped a fresh, config-less WebdriverIO project
    # in the execution directory on every single attempt (visible in its own logs as "Creating
    # WebdriverIO project..."), using whatever `node` happened to be first on PATH. On a host
    # where that's an older Node (this one's system `node` is 18.15.0), WebdriverIO's own
    # dependencies fail to even load (`node:events` doesn't export `addAbortListener` before
    # Node 20), so every attempt errored out immediately, before any real assertion ever ran -
    # and that error was misclassified as a genuine app ASSERTION_FAILURE rather than the
    # infrastructure/environment problem it actually was.
    command_builder = build_wdio_command_builder(config.node_executable, config.wdio_project_root)
    return Runner(
        catalog=catalog,
        execution_root=execution_root,
        launcher=_SubprocessLauncherImpl(),
        outbox=outbox,
        transport=transport,
        evidence_uploader=evidence_uploader,
        screenshot_capturer=AdbScreenshotCapturer(),
        reset_appium_session=AppiumSessionResetter(),
        command_builder=command_builder,
        wdio_project_root=config.wdio_project_root,
    )


def build_web_runner(config: HubConfig, transport: FailoverTransport, outbox: Outbox) -> Runner:
    catalog = TemplateCatalog(config.platform_template_root("WEB"))
    execution_root = config.data_root / "executions"
    evidence_uploader = HttpEvidenceUploader(base_url=config.backend_url, hub_token=config.hub_token)
    # Mirrors build_runner's Node-version fix exactly, just against wdio.web.conf.ts instead of
    # wdio.conf.ts: without a pinned node_executable/wdio_project_root, there is no config file or
    # dependencies in the execution directory for `wdio` to run against at all (see
    # templates.materialize_execution_dir and runner.build_wdio_command_builder).
    command_builder = build_wdio_command_builder(
        config.node_executable, config.wdio_project_root, config_filename="wdio.web.conf.ts"
    )
    return Runner(
        catalog=catalog,
        execution_root=execution_root,
        launcher=_SubprocessLauncherImpl(),
        outbox=outbox,
        transport=transport,
        evidence_uploader=evidence_uploader,
        screenshot_capturer=WebScreenshotCapturer(),
        reset_appium_session=None,
        command_builder=command_builder,
        wdio_project_root=config.wdio_project_root,
        wdio_config_filename="wdio.web.conf.ts",
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


def _run_platform(config: HubConfig, platform: str, entered_loop: dict[str, bool] | None = None) -> None:
    """Runs one platform's full preflight-connect-loop pipeline to completion (or until the
    process is killed). Isolated per platform: a failure here does not affect any other
    platform thread running in the same process.

    `entered_loop`, if given, is a shared dict (keyed by platform) that this function sets to
    True right before it starts its receive/execute loop - i.e. once preflight has actually
    succeeded and this platform is really about to start serving jobs. `run_forever` uses this
    after joining all threads to tell "every platform's preflight failed" (or `config.platforms`
    was empty) apart from a normal, healthy shutdown - see run_forever's docstring.
    """
    try:
        if platform == "WEB":
            chrome_profile_dir = config.data_root / "chrome-profile"
            preflight = run_web_preflight(
                template_root=config.platform_template_root("WEB"),
                data_root=config.data_root,
                chrome_profile_dir=chrome_profile_dir,
                # Same reasoning as the ANDROID branch below: check the pinned Node executable
                # itself, not whatever "node" resolves to on PATH.
                required_executables=(str(config.node_executable),),
                wdio_project_root=config.wdio_project_root,
            )
        else:
            preflight = run_preflight(
                template_root=config.platform_template_root("ANDROID"),
                data_root=config.data_root,
                # Check the pinned Node executable itself (not whatever "node" resolves to on PATH -
                # see build_runner's command_builder comment for why that distinction matters) and that
                # the pinned WebdriverIO project actually has its CLI installed.
                required_executables=(str(config.node_executable), "adb"),
                wdio_project_root=config.wdio_project_root,
            )
    except Exception:
        # run_preflight/run_web_preflight only turn a *missing template checksum* into a graceful
        # CheckResult (TemplateIntegrityError) - a template_root that doesn't exist at all raises
        # FileNotFoundError straight out of TemplateCatalog's constructor instead. Either way, one
        # platform's broken environment must degrade to "this platform doesn't start" rather than
        # taking down the whole multi-platform process (and the other platforms' threads with it).
        logger.exception(
            "[%s] Preflight raised an unexpected error; this platform will not run in this Hub process.",
            platform,
        )
        return

    if not preflight.ok:
        for failure in preflight.failures():
            logger.error("[%s] Preflight check failed: %s (%s)", platform, failure.name, failure.detail)
        logger.error("[%s] Preflight checks failed; this platform will not run in this Hub process.", platform)
        return

    journal = ExecutionJournal(config.data_root / f"journal-{platform.lower()}.sqlite3")
    outbox = Outbox(config.data_root / f"outbox-{platform.lower()}.sqlite3")
    transport = FailoverTransport(
        ws_transport=WebSocketTransport(config, platform),
        polling_transport=PollingTransport(config, platform),
    )
    transport.ws_transport.connect()

    runner = build_web_runner(config, transport, outbox) if platform == "WEB" else build_runner(config, transport, outbox)

    if entered_loop is not None:
        entered_loop[platform] = True

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
            logger.exception("[%s] Rejected an invalid JOB_OFFERED payload from the backend; skipping it.", platform)
            continue
        if not journal.claim(str(payload.executionId), payload.idempotencyKey):
            continue
        with _heartbeat_while_running(transport):
            runner.run(payload)
        journal.complete(str(payload.executionId))


def run_forever(config: HubConfig | None = None) -> None:
    """Starts one thread per configured platform and blocks until all of them exit.

    In healthy operation this call never returns: every thread's `_run_platform` loop runs
    forever. `thread.join()` only completes early for a thread whose platform failed preflight
    (see `_run_platform`'s isolation behavior - that failure is logged and the thread returns,
    it is not raised). If *every* thread returns this way - including the degenerate case of
    `config.platforms` being empty, e.g. a whitespace-only `OPSHUB_PLATFORMS` - this function
    would otherwise return normally, which is indistinguishable from a clean shutdown to
    systemd/Docker restart policies and monitoring. `entered_loop` is used to tell the two
    apart: raise `SystemExit` unless at least one platform actually made it into its
    receive/execute loop.
    """
    config = config or load_config()

    # De-duplicate: config.platforms doesn't guarantee uniqueness (e.g. "ANDROID,ANDROID" is
    # accepted as-is by load_config), and spawning two threads for the same platform would mean
    # two runners racing over the same journal/outbox sqlite files and the same transport
    # connection for that platform. dict.fromkeys preserves first-seen order.
    platforms = tuple(dict.fromkeys(config.platforms))

    entered_loop: dict[str, bool] = {platform: False for platform in platforms}

    threads = [
        threading.Thread(
            target=_run_platform,
            args=(config, platform, entered_loop),
            name=f"opshub-hub-{platform.lower()}",
            daemon=False,
        )
        for platform in platforms
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    if not any(entered_loop.values()):
        raise SystemExit(
            f"No platform session is running (configured: {config.platforms}); refusing to stay up."
        )


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    run_forever()
