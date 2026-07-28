"""Typed Local Hub configuration.

Loaded from environment variables (see local-hub/.env.example). All fields are
required — the Hub refuses to start without a complete configuration.
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
    platform: Literal["ANDROID", "WEB"] = "ANDROID"

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
}


def load_config(env: dict | None = None) -> HubConfig:
    """Build a HubConfig from environment variables, raising if any are missing."""
    source = env if env is not None else os.environ
    missing = [name for name in _ENV_MAP.values() if not source.get(name)]
    if missing:
        raise ValueError(f"Missing required Local Hub environment variables: {', '.join(missing)}")
    platform = source.get("OPSHUB_PLATFORM") or "ANDROID"
    return HubConfig(
        backend_url=source[_ENV_MAP["backend_url"]],
        hub_id=source[_ENV_MAP["hub_id"]],
        hub_token=source[_ENV_MAP["hub_token"]],
        template_root=Path(source[_ENV_MAP["template_root"]]),
        data_root=Path(source[_ENV_MAP["data_root"]]),
        platform=platform,
    )
