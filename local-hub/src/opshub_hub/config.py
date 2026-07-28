"""Typed Local Hub configuration.

Loaded from environment variables (see local-hub/.env.example). `platforms` lists which
platforms this Hub process runs concurrently, each in its own thread (see main.py) - a Hub
running ANDROID,WEB drives both an Android device and a Chrome profile from one process, one
session per platform, with no cross-platform interference. `template_root` is the *parent*
directory containing one subdirectory per platform (`android/`, `web/`); use
`platform_template_root(platform)` to get a specific platform's catalog root, never
`template_root` directly.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class HubConfig(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    backend_url: str = Field(min_length=1)
    hub_id: str = Field(min_length=1)
    hub_token: str = Field(min_length=1)
    template_root: Path
    data_root: Path
    platforms: tuple[Literal["ANDROID", "WEB"], ...] = ("ANDROID",)
    wdio_project_root: Path
    """A real, installed WebdriverIO project (`wdio.conf.ts`, `wdio.web.conf.ts`, `tsconfig.json`,
    `node_modules`) that every execution's rendered spec is run against - see
    `OPSHUB_WDIO_PROJECT_DIR`. Without this, the runner has no config file or dependencies to
    actually run a spec with."""
    node_executable: Path
    """Node.js binary (>=20) used to run the pinned WebdriverIO CLI directly, bypassing `npx`
    and whatever `node` (if any, and whatever version) happens to be first on `PATH` - see
    `OPSHUB_NODE_EXECUTABLE`."""

    def platform_template_root(self, platform: str) -> Path:
        return self.template_root / platform.lower()

    @property
    def websocket_url(self) -> str:
        base = self.backend_url.rstrip("/")
        ws_base = base.replace("https://", "wss://").replace("http://", "ws://")
        return f"{ws_base}/ws/v1/hubs/{self.hub_id}"

    @property
    def poll_next_job_url(self) -> str:
        return f"{self.backend_url.rstrip('/')}/api/v1/hubs/{self.hub_id}/jobs/next"

    @property
    def heartbeat_url(self) -> str:
        return f"{self.backend_url.rstrip('/')}/api/v1/hubs/{self.hub_id}/heartbeat"

    def lease_renew_url(self, lease_token: str) -> str:
        return f"{self.backend_url.rstrip('/')}/api/v1/leases/{lease_token}/renew"

    @property
    def progress_url(self) -> str:
        return f"{self.backend_url.rstrip('/')}/api/v1/hubs/{self.hub_id}/progress"

    @property
    def results_url(self) -> str:
        return f"{self.backend_url.rstrip('/')}/api/v1/hubs/{self.hub_id}/results"


_ENV_MAP = {
    "backend_url": "OPSHUB_BACKEND_URL",
    "hub_id": "OPSHUB_HUB_ID",
    "hub_token": "OPSHUB_HUB_TOKEN",
    "template_root": "OPSHUB_TEMPLATE_DIR",
    "data_root": "OPSHUB_WORK_DIR",
    "wdio_project_root": "OPSHUB_WDIO_PROJECT_DIR",
    "node_executable": "OPSHUB_NODE_EXECUTABLE",
}


def load_config(env: dict | None = None) -> HubConfig:
    """Build a HubConfig from environment variables, raising if any are missing."""
    source = env if env is not None else os.environ
    missing = [name for name in _ENV_MAP.values() if not source.get(name)]
    if missing:
        raise ValueError(f"Missing required Local Hub environment variables: {', '.join(missing)}")
    platforms_raw = source.get("OPSHUB_PLATFORMS") or "ANDROID"
    platforms = tuple(p.strip() for p in platforms_raw.split(",") if p.strip())
    return HubConfig(
        backend_url=source[_ENV_MAP["backend_url"]],
        hub_id=source[_ENV_MAP["hub_id"]],
        hub_token=source[_ENV_MAP["hub_token"]],
        template_root=Path(source[_ENV_MAP["template_root"]]),
        data_root=Path(source[_ENV_MAP["data_root"]]),
        platforms=platforms,
        wdio_project_root=Path(source[_ENV_MAP["wdio_project_root"]]),
        node_executable=Path(source[_ENV_MAP["node_executable"]]),
    )
