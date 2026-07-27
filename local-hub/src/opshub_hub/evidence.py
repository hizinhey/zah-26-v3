"""Evidence capture and upload.

Screenshots and logs are written to the filesystem under the execution directory
and only ever referenced from PostgreSQL by relative path/metadata — the Hub
uploads the file bytes to the Task 6 evidence endpoint
(`POST /api/v1/test-results/{testResultId}/evidence`) as multipart form data with
a declared size and SHA-256 that the backend verifies against what it actually
receives. Local files are preserved until the upload is acknowledged (HTTP 2xx);
a failed upload leaves the file in place for a later retry.
"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Protocol
from uuid import UUID


class EvidenceType(str, Enum):
    SCREENSHOT = "SCREENSHOT"
    LOG = "LOG"


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(65536), b""):
            digest.update(chunk)
    return digest.hexdigest()


@dataclass(frozen=True)
class EvidenceFile:
    path: Path
    evidence_type: EvidenceType

    @property
    def size(self) -> int:
        return self.path.stat().st_size

    @property
    def sha256(self) -> str:
        return sha256_of(self.path)


class EvidenceUploadError(Exception):
    """Raised when the evidence endpoint rejects or cannot be reached for an upload."""


class EvidenceUploader(Protocol):
    def upload(self, test_result_id: UUID, evidence: EvidenceFile) -> UUID: ...


class HttpEvidenceUploader:
    """Uploads evidence via the Task 6 evidence endpoint using httpx."""

    def __init__(self, base_url: str, client=None):
        self._base_url = base_url.rstrip("/")
        if client is None:
            import httpx

            client = httpx.Client(timeout=30.0)
        self._client = client

    def upload(self, test_result_id: UUID, evidence: EvidenceFile) -> UUID:
        import httpx

        url = f"{self._base_url}/api/v1/test-results/{test_result_id}/evidence"
        params = {
            "evidenceType": evidence.evidence_type.value,
            "declaredSize": evidence.size,
            "declaredSha256": evidence.sha256,
        }
        try:
            with evidence.path.open("rb") as handle:
                files = {"file": (evidence.path.name, handle)}
                response = self._client.post(url, params=params, files=files)
        except httpx.HTTPError as exc:
            raise EvidenceUploadError(str(exc)) from exc
        if response.status_code >= 300:
            raise EvidenceUploadError(f"Evidence upload failed ({response.status_code}): {response.text}")
        return UUID(response.json()["id"])


class ScreenshotCapturer(Protocol):
    """Captures the current device screen to a PNG file inside the execution
    directory. Concrete implementations talk to the active Appium session."""

    def __call__(self, destination: Path) -> Path: ...
