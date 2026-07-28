"""Node-version fix: `materialize_execution_dir` must turn every execution directory into a
fully self-contained, runnable WebdriverIO project (wdio.conf.ts, tsconfig.json, node_modules)
when a `wdio_project_root` is supplied - not just a bare directory of rendered specs with
nothing to actually run them with. Previously the runner always fell back to `npx wdio run`,
which had no config file to find and cold-bootstrapped a fresh project using whatever `node`
happened to be first on PATH.
"""

from __future__ import annotations

from pathlib import Path

from opshub_hub.models import TemplateParametersV1, TestCase
from opshub_hub.templates import TemplateCatalog, materialize_execution_dir

TEMPLATE_ROOT = Path(__file__).resolve().parents[1] / "templates" / "android"

SAMPLE_PARAMETERS = TemplateParametersV1(
    oaName="Sample OA",
    thumbnailUrl="https://cdn.example.test/thumb.png",
    expectedHeader="Header",
    expectedBody="Body",
    expectedButtonText="Open",
    expectedRedirectUrl="https://business.example.test/offer",
    expectedRedirectDomain="business.example.test",
)


def _make_test_case(order: int) -> TestCase:
    from uuid import uuid4

    return TestCase(
        testCaseId=uuid4(),
        oaOrder=1,
        oaName="Sample OA",
        order=order,
        templateId="android-oa-delivery-v1",
        templateVersion=1,
        parameters=SAMPLE_PARAMETERS,
    )


def _make_wdio_project(root: Path, config_filename: str = "wdio.conf.ts") -> Path:
    root.mkdir()
    (root / config_filename).write_text("export const config = { marker: 'pinned-project' };")
    (root / "tsconfig.json").write_text('{"marker": "pinned-tsconfig"}')
    (root / "node_modules").mkdir()
    (root / "node_modules" / "marker.txt").write_text("pinned-node-modules")
    return root


def test_without_wdio_project_root_only_renders_specs_and_pages(tmp_path):
    catalog = TemplateCatalog(TEMPLATE_ROOT)
    execution_dir = tmp_path / "execution"

    materialize_execution_dir(catalog, execution_dir, [_make_test_case(1)])

    assert (execution_dir / "tests").is_dir()
    assert (execution_dir / "pages").is_dir()
    assert not (execution_dir / "wdio.conf.ts").exists()
    assert not (execution_dir / "node_modules").exists()


def test_with_wdio_project_root_copies_config_and_symlinks_node_modules(tmp_path):
    catalog = TemplateCatalog(TEMPLATE_ROOT)
    execution_dir = tmp_path / "execution"
    wdio_project_root = _make_wdio_project(tmp_path / "wdio-project")

    materialize_execution_dir(
        catalog, execution_dir, [_make_test_case(1)], wdio_project_root=wdio_project_root
    )

    assert (execution_dir / "wdio.conf.ts").read_text() == "export const config = { marker: 'pinned-project' };"
    assert (execution_dir / "tsconfig.json").read_text() == '{"marker": "pinned-tsconfig"}'
    node_modules_link = execution_dir / "node_modules"
    assert node_modules_link.is_symlink()
    assert (node_modules_link / "marker.txt").read_text() == "pinned-node-modules"


def test_web_platform_copies_wdio_web_conf_instead_of_wdio_conf(tmp_path):
    """Critical-1 regression: the Web execution path previously never got a config file
    materialized at all (only ANDROID's "wdio.conf.ts" was ever copied, and build_web_runner
    never passed wdio_project_root in the first place). config_filename must select which real
    project file lands in the execution directory."""
    catalog = TemplateCatalog(TEMPLATE_ROOT)
    execution_dir = tmp_path / "execution"
    wdio_project_root = _make_wdio_project(tmp_path / "wdio-project", config_filename="wdio.web.conf.ts")

    materialize_execution_dir(
        catalog,
        execution_dir,
        [_make_test_case(1)],
        wdio_project_root=wdio_project_root,
        config_filename="wdio.web.conf.ts",
    )

    assert (execution_dir / "wdio.web.conf.ts").read_text() == "export const config = { marker: 'pinned-project' };"
    assert not (execution_dir / "wdio.conf.ts").exists()
    assert (execution_dir / "tsconfig.json").read_text() == '{"marker": "pinned-tsconfig"}'


def test_wdio_project_files_are_not_recopied_on_a_second_materialize_call(tmp_path):
    """A test case's spec is rendered fresh per attempt, but wdio.conf.ts/tsconfig.json/
    node_modules only need to be linked in once per execution directory."""
    catalog = TemplateCatalog(TEMPLATE_ROOT)
    execution_dir = tmp_path / "execution"
    wdio_project_root = _make_wdio_project(tmp_path / "wdio-project")

    materialize_execution_dir(
        catalog, execution_dir, [_make_test_case(1)], wdio_project_root=wdio_project_root
    )
    # Overwrite the pinned project's own file after the first call to prove the execution
    # directory's copy is not refreshed (and, more importantly, that re-symlinking an existing
    # node_modules symlink doesn't raise FileExistsError).
    (wdio_project_root / "wdio.conf.ts").write_text("changed after first materialize")

    materialize_execution_dir(
        catalog, execution_dir, [_make_test_case(2)], wdio_project_root=wdio_project_root
    )

    assert (execution_dir / "wdio.conf.ts").read_text() == "export const config = { marker: 'pinned-project' };"
