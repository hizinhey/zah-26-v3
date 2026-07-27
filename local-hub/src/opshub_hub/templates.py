"""Template catalog loading, checksum verification, and safe parameter substitution.

Only declared `TemplateParametersV1` fields are ever rendered into a template — the
Handlebars-style `{{{json name}}}` placeholders are substituted with
`json.dumps(value)`, so no other file content or executable code can be injected
through OA-controlled strings.
"""

from __future__ import annotations

import hashlib
import json
import re
import shutil
from dataclasses import dataclass
from pathlib import Path

from opshub_hub.models import TemplateParametersV1

_PLACEHOLDER = re.compile(r"\{\{\{json ([a-zA-Z][a-zA-Z0-9]*)\}\}\}")

# Derived from opshub_hub.models.TemplateParametersV1's declared fields rather than
# hand-copied, so a future field rename in models.py can't silently drift out of sync.
ALLOWED_PARAMETER_NAMES: frozenset[str] = frozenset(TemplateParametersV1.model_fields.keys())


class TemplateIntegrityError(Exception):
    """Raised when a template file is missing, its checksum doesn't match the
    manifest, or an attempt is made to render an undeclared parameter."""


@dataclass(frozen=True)
class TemplateEntry:
    id: str
    version: int
    path: str
    sha256: str
    parameter_schema: str


class TemplateCatalog:
    def __init__(self, root: Path | str):
        self.root = Path(root)
        manifest = json.loads((self.root / "manifest.json").read_text())
        self.catalog_version: str = manifest["catalogVersion"]
        self._entries: dict[str, TemplateEntry] = {
            entry["id"]: TemplateEntry(
                id=entry["id"],
                version=entry["version"],
                path=entry["path"],
                sha256=entry["sha256"],
                parameter_schema=entry["parameterSchema"],
            )
            for entry in manifest["templates"]
        }

    def entry(self, template_id: str) -> TemplateEntry:
        try:
            return self._entries[template_id]
        except KeyError:
            raise TemplateIntegrityError(f"Unknown template id: {template_id}") from None

    def verify(self) -> None:
        """Verify every catalogued template file's SHA-256 matches the manifest."""
        for entry in self._entries.values():
            file_path = self.root / entry.path
            if not file_path.is_file():
                raise TemplateIntegrityError(f"Missing template file: {entry.path}")
            actual = hashlib.sha256(file_path.read_bytes()).hexdigest()
            if actual != entry.sha256:
                raise TemplateIntegrityError(
                    f"Checksum mismatch for template '{entry.id}': "
                    f"manifest declares {entry.sha256}, file hashes to {actual}"
                )

    def render(self, template_id: str, parameters: dict[str, str]) -> str:
        """Render only the declared parameters into the template source."""
        entry = self.entry(template_id)
        unknown = set(parameters) - ALLOWED_PARAMETER_NAMES
        if unknown:
            raise TemplateIntegrityError(f"Refusing to render undeclared parameters: {sorted(unknown)}")
        source = (self.root / entry.path).read_text()

        def substitute(match: re.Match[str]) -> str:
            key = match.group(1)
            if key not in parameters:
                raise TemplateIntegrityError(f"Missing declared parameter for template: {key}")
            return json.dumps(parameters[key], ensure_ascii=False)

        return _PLACEHOLDER.sub(substitute, source)


def materialize_execution_dir(
    catalog: TemplateCatalog,
    execution_dir: Path,
    test_cases: list,
) -> dict[str, Path]:
    """Render every test case's spec into a fresh execution directory alongside a
    copy of the shared page objects. Returns a mapping of testCaseId (str) -> spec
    file path, in the same order as `test_cases`.
    """
    execution_dir.mkdir(parents=True, exist_ok=True)
    tests_dir = execution_dir / "tests"
    tests_dir.mkdir(parents=True, exist_ok=True)
    pages_src = catalog.root / "pages"
    pages_dst = execution_dir / "pages"
    if pages_src.is_dir() and not pages_dst.exists():
        shutil.copytree(pages_src, pages_dst)

    spec_paths: dict[str, Path] = {}
    for test_case in test_cases:
        rendered = catalog.render(test_case.templateId, test_case.parameters.model_dump())
        spec_path = tests_dir / f"{test_case.templateId}.spec.ts"
        spec_path.write_text(rendered)
        spec_paths[str(test_case.testCaseId)] = spec_path
    return spec_paths
