"""Local Hub entrypoint: wires config, transports, journal, outbox, template
catalog, and the Runner together, then loops receiving and executing jobs.
"""

from __future__ import annotations

import logging
import time

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
    evidence_uploader = HttpEvidenceUploader(base_url=config.backend_url)
    return Runner(
        catalog=catalog,
        execution_root=execution_root,
        launcher=_SubprocessLauncherImpl(),
        outbox=outbox,
        transport=transport,
        evidence_uploader=evidence_uploader,
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
            return ProcessResult(
                returncode=-1,
                stdout=exc.stdout or "",
                stderr=f"{exc.stderr or ''}\nTimed out after {timeout}s waiting for the spec to finish.",
            )


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
        payload = JobOfferedPayload.model_validate(job.get("payload", job))
        if not journal.claim(str(payload.executionId), payload.idempotencyKey):
            continue
        runner.run(payload)
        journal.complete(str(payload.executionId))


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    run_forever()
