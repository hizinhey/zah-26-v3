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


def deterministic_test_result_id(execution_id: UUID, test_case_id: UUID, attempt: int) -> UUID:
    """Computes the same `test_results.id` the backend generates for this
    (executionId, testCaseId, attempt) triple, so evidence can be uploaded against
    it without a round trip to learn a server-generated id (see
    `ExecutionService.testResultId` in the backend, which the two MUST match
    byte-for-byte).

    This reimplements `java.util.UUID.nameUUIDFromBytes` from scratch: MD5 the
    UTF-8 bytes of the canonical string, then set the version (3) and variant
    (RFC 4122) bits on the digest. Note `uuid.uuid3` from the stdlib does NOT
    match, because it prepends namespace bytes before hashing.
    """
    canonical = f"{execution_id}:{test_case_id}:{attempt}"
    digest = bytearray(hashlib.md5(canonical.encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30
    digest[8] = (digest[8] & 0x3F) | 0x80
    return UUID(bytes=bytes(digest))


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
