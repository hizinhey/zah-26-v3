from pathlib import Path

from opshub_hub.preflight import ProcessResult, run_preflight, run_web_preflight
from opshub_hub.templates import TemplateIntegrityError

TEMPLATE_ROOT = Path(__file__).resolve().parents[1] / "templates" / "android"
WEB_TEMPLATE_ROOT = Path(__file__).resolve().parents[1] / "templates" / "web"


def _happy_run_command(args, timeout=10.0):
    if args[-1] == "--version":
        return ProcessResult(returncode=0, stdout="v24.0.0\n")
    if args == ["adb", "devices"]:
        return ProcessResult(returncode=0, stdout="List of devices attached\nemulator-5554\tdevice\n")
    if args[:3] == ["adb", "shell", "pm"]:
        return ProcessResult(returncode=0, stdout="package:com.zing.zalo\n")
    raise AssertionError(f"unexpected command {args}")


class _OkCatalog:
    def verify(self) -> None:
        return None


def test_all_checks_pass_when_everything_is_healthy(tmp_path):
    report = run_preflight(
        template_root=TEMPLATE_ROOT,
        data_root=tmp_path,
        run_command=_happy_run_command,
        probe_url=lambda url, timeout=5.0: True,
        catalog_factory=lambda root: _OkCatalog(),
    )
    assert report.ok is True
    assert report.failures() == []
    names = {check.name for check in report.checks}
    assert "executable:node" in names
    assert "adb-device-state" in names
    assert "appium-reachable" in names
    assert "zalo-package-installed" in names
    assert "template-manifest-checksum" in names
    assert "writable:data-root" in names
    assert "wdio-project-installed" not in names, (
        "the check should only run when wdio_project_root is actually passed"
    )


def _make_installed_wdio_project(root: Path, config_filename: str = "wdio.conf.ts") -> Path:
    root.mkdir()
    (root / config_filename).write_text("export const config = {};")
    (root / "tsconfig.json").write_text("{}")
    bin_dir = root / "node_modules" / ".bin"
    bin_dir.mkdir(parents=True)
    (bin_dir / "wdio").write_text("#!/usr/bin/env node\n")
    return root


def test_wdio_project_installed_check_passes_when_fully_installed(tmp_path):
    wdio_project_root = _make_installed_wdio_project(tmp_path / "wdio-project")

    report = run_preflight(
        template_root=TEMPLATE_ROOT,
        data_root=tmp_path / "data",
        run_command=_happy_run_command,
        probe_url=lambda url, timeout=5.0: True,
        catalog_factory=lambda root: _OkCatalog(),
        wdio_project_root=wdio_project_root,
    )

    check = next(c for c in report.checks if c.name == "wdio-project-installed")
    assert check.ok is True


def test_wdio_project_installed_check_fails_when_node_modules_missing(tmp_path):
    wdio_project_root = tmp_path / "wdio-project"
    wdio_project_root.mkdir()
    (wdio_project_root / "wdio.conf.ts").write_text("export const config = {};")
    (wdio_project_root / "tsconfig.json").write_text("{}")
    # node_modules deliberately absent.

    report = run_preflight(
        template_root=TEMPLATE_ROOT,
        data_root=tmp_path / "data",
        run_command=_happy_run_command,
        probe_url=lambda url, timeout=5.0: True,
        catalog_factory=lambda root: _OkCatalog(),
        wdio_project_root=wdio_project_root,
    )

    check = next(c for c in report.checks if c.name == "wdio-project-installed")
    assert check.ok is False
    assert "node_modules" in check.detail


def test_no_authorized_device_fails_adb_device_state_check(tmp_path):
    def run_command(args, timeout=10.0):
        if args[-1] == "--version":
            return ProcessResult(returncode=0, stdout="v24.0.0\n")
        if args == ["adb", "devices"]:
            return ProcessResult(returncode=0, stdout="List of devices attached\n")
        return ProcessResult(returncode=0, stdout="package:com.zing.zalo\n")

    report = run_preflight(
        template_root=TEMPLATE_ROOT,
        data_root=tmp_path,
        run_command=run_command,
        probe_url=lambda url, timeout=5.0: True,
        catalog_factory=lambda root: _OkCatalog(),
    )
    assert report.ok is False
    device_check = next(c for c in report.checks if c.name == "adb-device-state")
    assert device_check.ok is False


def test_unreachable_appium_fails_appium_check(tmp_path):
    report = run_preflight(
        template_root=TEMPLATE_ROOT,
        data_root=tmp_path,
        run_command=_happy_run_command,
        probe_url=lambda url, timeout=5.0: False,
        catalog_factory=lambda root: _OkCatalog(),
    )
    appium_check = next(c for c in report.checks if c.name == "appium-reachable")
    assert appium_check.ok is False
    assert report.ok is False


def test_zalo_package_not_installed_fails_check(tmp_path):
    def run_command(args, timeout=10.0):
        if args[-1] == "--version":
            return ProcessResult(returncode=0, stdout="v24.0.0\n")
        if args == ["adb", "devices"]:
            return ProcessResult(returncode=0, stdout="List of devices attached\nemulator-5554\tdevice\n")
        return ProcessResult(returncode=0, stdout="")

    report = run_preflight(
        template_root=TEMPLATE_ROOT,
        data_root=tmp_path,
        run_command=run_command,
        probe_url=lambda url, timeout=5.0: True,
        catalog_factory=lambda root: _OkCatalog(),
    )
    zalo_check = next(c for c in report.checks if c.name == "zalo-package-installed")
    assert zalo_check.ok is False
    assert report.ok is False


def test_manifest_checksum_mismatch_fails_check(tmp_path):
    class _BadCatalog:
        def verify(self) -> None:
            raise TemplateIntegrityError("checksum mismatch")

    report = run_preflight(
        template_root=TEMPLATE_ROOT,
        data_root=tmp_path,
        run_command=_happy_run_command,
        probe_url=lambda url, timeout=5.0: True,
        catalog_factory=lambda root: _BadCatalog(),
    )
    checksum_check = next(c for c in report.checks if c.name == "template-manifest-checksum")
    assert checksum_check.ok is False
    assert report.ok is False


def test_unwritable_data_root_fails_check(tmp_path, monkeypatch):
    unwritable = tmp_path / "readonly"
    unwritable.mkdir()
    unwritable.chmod(0o500)
    try:
        report = run_preflight(
            template_root=TEMPLATE_ROOT,
            data_root=unwritable / "nested",
            run_command=_happy_run_command,
            probe_url=lambda url, timeout=5.0: True,
            catalog_factory=lambda root: _OkCatalog(),
        )
        writable_check = next(c for c in report.checks if c.name == "writable:data-root")
        assert writable_check.ok is False
        assert report.ok is False
    finally:
        unwritable.chmod(0o700)


def test_web_preflight_passes_when_node_present_and_profile_exists(tmp_path):
    profile_dir = tmp_path / "chrome-profile"
    profile_dir.mkdir()

    def run_command(args, timeout=10.0):
        if args[-1] == "--version":
            return ProcessResult(returncode=0, stdout="v24.0.0\n")
        raise AssertionError(f"unexpected command {args}")

    report = run_web_preflight(
        template_root=WEB_TEMPLATE_ROOT,
        data_root=tmp_path / "data",
        chrome_profile_dir=profile_dir,
        run_command=run_command,
        catalog_factory=lambda root: _OkCatalog(),
    )

    assert report.ok is True
    names = {check.name for check in report.checks}
    assert "executable:node" in names
    assert "chrome-profile-exists" in names
    assert "template-manifest-checksum" in names
    assert "writable:data-root" in names
    assert "adb-device-state" not in names
    assert "appium-reachable" not in names


def test_web_preflight_fails_when_chrome_profile_directory_is_missing(tmp_path):
    def run_command(args, timeout=10.0):
        return ProcessResult(returncode=0, stdout="v24.0.0\n")

    report = run_web_preflight(
        template_root=WEB_TEMPLATE_ROOT,
        data_root=tmp_path / "data",
        chrome_profile_dir=tmp_path / "does-not-exist",
        run_command=run_command,
        catalog_factory=lambda root: _OkCatalog(),
    )

    assert report.ok is False
    failure = next(check for check in report.failures() if check.name == "chrome-profile-exists")
    assert "does-not-exist" in failure.detail


def test_web_preflight_wdio_project_check_passes_when_wdio_web_conf_installed(tmp_path):
    profile_dir = tmp_path / "chrome-profile"
    profile_dir.mkdir()
    wdio_project_root = _make_installed_wdio_project(tmp_path / "wdio-project", config_filename="wdio.web.conf.ts")

    def run_command(args, timeout=10.0):
        return ProcessResult(returncode=0, stdout="v24.0.0\n")

    report = run_web_preflight(
        template_root=WEB_TEMPLATE_ROOT,
        data_root=tmp_path / "data",
        chrome_profile_dir=profile_dir,
        run_command=run_command,
        catalog_factory=lambda root: _OkCatalog(),
        wdio_project_root=wdio_project_root,
    )

    check = next(c for c in report.checks if c.name == "wdio-project-installed")
    assert check.ok is True
    assert report.ok is True


def test_web_preflight_wdio_project_check_fails_when_wdio_web_conf_missing(tmp_path):
    profile_dir = tmp_path / "chrome-profile"
    profile_dir.mkdir()
    wdio_project_root = tmp_path / "wdio-project"
    wdio_project_root.mkdir()
    # wdio.web.conf.ts deliberately absent (e.g. only the ANDROID wdio.conf.ts was provisioned).
    (wdio_project_root / "tsconfig.json").write_text("{}")
    (wdio_project_root / "node_modules").mkdir()

    def run_command(args, timeout=10.0):
        return ProcessResult(returncode=0, stdout="v24.0.0\n")

    report = run_web_preflight(
        template_root=WEB_TEMPLATE_ROOT,
        data_root=tmp_path / "data",
        chrome_profile_dir=profile_dir,
        run_command=run_command,
        catalog_factory=lambda root: _OkCatalog(),
        wdio_project_root=wdio_project_root,
    )

    check = next(c for c in report.checks if c.name == "wdio-project-installed")
    assert check.ok is False
    assert "wdio.web.conf.ts" in check.detail
    assert report.ok is False
