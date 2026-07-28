"""Preflight checks the Local Hub runs before executing any job: executable
versions, ADB device state, Appium reachability, Zalo package installation,
template manifest checksum, and writable data directories.

Every external effect (subprocess calls, HTTP reachability, filesystem writes) is
injected so this module is fully testable without real tooling installed.
"""

from __future__ import annotations

import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Protocol

from opshub_hub.templates import TemplateCatalog, TemplateIntegrityError


@dataclass(frozen=True)
class ProcessResult:
    returncode: int
    stdout: str = ""
    stderr: str = ""


class CommandRunner(Protocol):
    def __call__(self, args: list[str], timeout: float = 10.0) -> ProcessResult: ...


class Prober(Protocol):
    def __call__(self, url: str, timeout: float = 5.0) -> bool: ...


@dataclass
class CheckResult:
    name: str
    ok: bool
    detail: str = ""


@dataclass
class PreflightReport:
    checks: list[CheckResult] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return all(check.ok for check in self.checks)

    def failures(self) -> list[CheckResult]:
        return [check for check in self.checks if not check.ok]


def _default_run_command(args: list[str], timeout: float = 10.0) -> ProcessResult:
    completed = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    return ProcessResult(returncode=completed.returncode, stdout=completed.stdout, stderr=completed.stderr)


def _default_probe(url: str, timeout: float = 5.0) -> bool:
    import httpx

    try:
        response = httpx.get(url, timeout=timeout)
        return response.status_code < 500
    except httpx.HTTPError:
        return False


def run_preflight(
    *,
    template_root: Path,
    data_root: Path,
    appium_status_url: str = "http://127.0.0.1:4723/status",
    zalo_package: str = "com.zing.zalo",
    required_executables: tuple[str, ...] = ("node", "adb"),
    run_command: CommandRunner = _default_run_command,
    probe_url: Prober = _default_probe,
    catalog_factory: Callable[[Path], TemplateCatalog] = TemplateCatalog,
) -> PreflightReport:
    report = PreflightReport()

    for executable in required_executables:
        try:
            result = run_command([executable, "--version"], timeout=10.0)
            ok = result.returncode == 0
            detail = result.stdout.strip() or result.stderr.strip()
        except (OSError, subprocess.TimeoutExpired) as exc:
            ok = False
            detail = str(exc)
        report.checks.append(CheckResult(name=f"executable:{executable}", ok=ok, detail=detail))

    try:
        devices_result = run_command(["adb", "devices"], timeout=10.0)
        connected_lines = [
            line
            for line in devices_result.stdout.splitlines()[1:]
            if line.strip() and line.split("\t")[-1].strip() == "device"
        ]
        ok = devices_result.returncode == 0 and len(connected_lines) > 0
        detail = "no authorized devices" if not ok else connected_lines[0]
    except (OSError, subprocess.TimeoutExpired) as exc:
        ok = False
        detail = str(exc)
    report.checks.append(CheckResult(name="adb-device-state", ok=ok, detail=detail))

    appium_ok = probe_url(appium_status_url)
    report.checks.append(
        CheckResult(name="appium-reachable", ok=appium_ok, detail=appium_status_url if not appium_ok else "")
    )

    try:
        packages_result = run_command(["adb", "shell", "pm", "list", "packages", zalo_package], timeout=10.0)
        zalo_ok = packages_result.returncode == 0 and zalo_package in packages_result.stdout
        detail = "" if zalo_ok else f"{zalo_package} not installed"
    except (OSError, subprocess.TimeoutExpired) as exc:
        zalo_ok = False
        detail = str(exc)
    report.checks.append(CheckResult(name="zalo-package-installed", ok=zalo_ok, detail=detail))

    try:
        catalog = catalog_factory(template_root)
        catalog.verify()
        report.checks.append(CheckResult(name="template-manifest-checksum", ok=True))
    except TemplateIntegrityError as exc:
        report.checks.append(CheckResult(name="template-manifest-checksum", ok=False, detail=str(exc)))

    for name, directory in (("data-root", data_root),):
        try:
            directory.mkdir(parents=True, exist_ok=True)
            probe_file = directory / ".preflight-write-check"
            probe_file.write_text("ok")
            probe_file.unlink()
            report.checks.append(CheckResult(name=f"writable:{name}", ok=True))
        except OSError as exc:
            report.checks.append(CheckResult(name=f"writable:{name}", ok=False, detail=str(exc)))

    return report


def run_web_preflight(
    *,
    template_root: Path,
    data_root: Path,
    chrome_profile_dir: Path,
    required_executables: tuple[str, ...] = ("node",),
    run_command: CommandRunner = _default_run_command,
    catalog_factory: Callable[[Path], TemplateCatalog] = TemplateCatalog,
) -> PreflightReport:
    """Preflight for the Web (WebdriverIO + Chrome) execution path: no adb, no Appium,
    no mobile device - Chrome resolves its own driver, and login is a pre-provisioned
    profile directory rather than a live device."""
    report = PreflightReport()

    for executable in required_executables:
        try:
            result = run_command([executable, "--version"], timeout=10.0)
            ok = result.returncode == 0
            detail = result.stdout.strip() or result.stderr.strip()
        except (OSError, subprocess.TimeoutExpired) as exc:
            ok = False
            detail = str(exc)
        report.checks.append(CheckResult(name=f"executable:{executable}", ok=ok, detail=detail))

    profile_ok = chrome_profile_dir.is_dir()
    report.checks.append(CheckResult(
        name="chrome-profile-exists",
        ok=profile_ok,
        detail="" if profile_ok else f"Chrome profile directory not found: {chrome_profile_dir} "
                                      "(complete the one-time manual QR login first)",
    ))

    try:
        catalog = catalog_factory(template_root)
        catalog.verify()
        report.checks.append(CheckResult(name="template-manifest-checksum", ok=True))
    except TemplateIntegrityError as exc:
        report.checks.append(CheckResult(name="template-manifest-checksum", ok=False, detail=str(exc)))

    try:
        data_root.mkdir(parents=True, exist_ok=True)
        probe_file = data_root / ".preflight-write-check"
        probe_file.write_text("ok")
        probe_file.unlink()
        report.checks.append(CheckResult(name="writable:data-root", ok=True))
    except OSError as exc:
        report.checks.append(CheckResult(name="writable:data-root", ok=False, detail=str(exc)))

    return report
