import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "templates" / "android"
NODE_MODULES_ENV = "MOBILE_SCRIPT_NODE_MODULES"
NODE_BINARY_ENV = "MOBILE_SCRIPT_NODE"
EXPECTED_IDS = [
    "android-oa-delivery-v1",
    "android-thumbnail-v1",
    "android-content-v1",
    "android-button-text-v1",
    "android-redirect-v1",
]
SAMPLE_PARAMETERS = {
    "oaName": 'OA "Việt Nam" `promo` 😀',
    "thumbnailUrl": "https://cdn.example.test/thumb.png?campaign=summer&lang=vi",
    "expectedHeader": 'Ưu đãi "mới" `hôm nay` 😀',
    "expectedBody": "Dòng một\nDòng hai có dấu ` và emoji 🚀",
    "expectedButtonText": 'Mở "ngay"',
    "expectedRedirectUrl": "https://business.example.test/offer?q=x%60y&lang=vi",
    "expectedRedirectDomain": "business.example.test",
}


def render(template: str, parameters: dict[str, str]) -> str:
    """Render the deliberately narrow Handlebars JSON helper used by templates."""
    return re.sub(
        r"\{\{\{json ([a-zA-Z][a-zA-Z0-9]*)\}\}\}",
        lambda match: json.dumps(parameters[match.group(1)], ensure_ascii=False),
        template,
    )


def test_catalog_has_the_five_ordered_hashed_templates() -> None:
    manifest = json.loads((CATALOG / "manifest.json").read_text())

    assert [entry["id"] for entry in manifest["templates"]] == EXPECTED_IDS
    assert all(entry["version"] == 1 for entry in manifest["templates"])
    assert all(entry["parameterSchema"] == "template-parameters-v1" for entry in manifest["templates"])
    for entry in manifest["templates"]:
        template = CATALOG / entry["path"]
        assert template.is_file()
        assert entry["sha256"] == hashlib.sha256(template.read_bytes()).hexdigest()


def test_templates_render_special_characters_as_typescript_string_literals() -> None:
    manifest = json.loads((CATALOG / "manifest.json").read_text())

    rendered = {
        entry["id"]: render((CATALOG / entry["path"]).read_text(), SAMPLE_PARAMETERS)
        for entry in manifest["templates"]
    }

    assert json.dumps(SAMPLE_PARAMETERS["oaName"], ensure_ascii=False) in rendered[EXPECTED_IDS[0]]
    assert json.dumps(SAMPLE_PARAMETERS["expectedHeader"], ensure_ascii=False) in rendered[EXPECTED_IDS[2]]
    assert json.dumps(SAMPLE_PARAMETERS["expectedBody"], ensure_ascii=False) in rendered[EXPECTED_IDS[2]]
    assert json.dumps(SAMPLE_PARAMETERS["expectedRedirectUrl"], ensure_ascii=False) in rendered[EXPECTED_IDS[4]]
    assert all("{{" not in source for source in rendered.values())


def test_rendered_specs_typecheck_with_the_local_wdio_dependencies(tmp_path: Path) -> None:
    node_modules_value = os.environ.get(NODE_MODULES_ENV)
    if node_modules_value is None:
        pytest.fail(f"Set {NODE_MODULES_ENV} to the ignored WebdriverIO node_modules directory")
    node_modules = Path(node_modules_value)
    node_binary_value = os.environ.get(NODE_BINARY_ENV)
    if node_binary_value is None:
        pytest.fail(f"Set {NODE_BINARY_ENV} to a Node.js 24+ executable")
    node_binary = Path(node_binary_value)
    if not node_binary.is_file():
        pytest.fail(f"{NODE_BINARY_ENV} is not an executable file: {node_binary}")
    tsc = node_modules / "typescript" / "lib" / "tsc.js"
    if not tsc.exists():
        pytest.fail(f"{NODE_MODULES_ENV} does not contain TypeScript: {node_modules}")

    manifest = json.loads((CATALOG / "manifest.json").read_text())
    tests = tmp_path / "tests"
    pages = tmp_path / "pages"
    tests.mkdir()
    shutil.copytree(CATALOG / "pages", pages)
    for entry in manifest["templates"]:
        output = tests / Path(entry["path"]).name.removesuffix(".hbs")
        output.write_text(render((CATALOG / entry["path"]).read_text(), SAMPLE_PARAMETERS))
    (tmp_path / "node_modules").symlink_to(node_modules, target_is_directory=True)
    (tmp_path / "tsconfig.json").write_text(
        json.dumps(
            {
                "compilerOptions": {
                    "moduleResolution": "node16",
                    "module": "node16",
                    "target": "es2022",
                    "lib": ["es2022", "dom"],
                    "types": ["node", "@wdio/globals/types", "expect-webdriverio", "@wdio/mocha-framework"],
                    "skipLibCheck": True,
                    "noEmit": True,
                    "strict": True,
                },
                "include": ["tests", "pages"],
            }
        )
    )
    subprocess.run([str(node_binary), str(tsc), "--noEmit", "--project", str(tmp_path / "tsconfig.json")], check=True)
