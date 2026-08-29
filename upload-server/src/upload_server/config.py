"""환경변수 기반 설정. 게이트웨이의 API_SERVER_HOST/PORT 패턴(docs/setup-windows.md)과
같은 방식 — LAN NIC 주소를 명시적으로 바인딩하고, 기본값은 이 파일에 정의된 것만
쓴다(하드코딩된 특정 IP 없음)."""

from __future__ import annotations

import os
from dataclasses import dataclass

DEFAULT_PORT = 8643
DEFAULT_RETENTION_DAYS = 14
DEFAULT_MAX_UPLOAD_BYTES = 100 * 1024 * 1024
DEFAULT_SWEEP_INTERVAL_SECONDS = 3600
DEFAULT_GENERATED_DIR = "./generated"


@dataclass(frozen=True)
class Config:
    bind_host: str
    bind_port: int
    api_key: str
    inbox_dir: str
    retention_days: int
    max_upload_bytes: int
    sweep_interval_seconds: int
    generated_dir: str = DEFAULT_GENERATED_DIR

    @staticmethod
    def from_env() -> "Config":
        return Config(
            bind_host=os.environ.get("UPLOAD_SERVER_HOST", "0.0.0.0"),
            bind_port=int(os.environ.get("UPLOAD_SERVER_PORT", str(DEFAULT_PORT))),
            api_key=os.environ["UPLOAD_SERVER_API_KEY"],
            inbox_dir=os.environ.get("UPLOAD_SERVER_INBOX_DIR", "./uploads/inbox"),
            retention_days=int(
                os.environ.get("UPLOAD_SERVER_RETENTION_DAYS", str(DEFAULT_RETENTION_DAYS)),
            ),
            max_upload_bytes=int(
                os.environ.get("UPLOAD_SERVER_MAX_BYTES", str(DEFAULT_MAX_UPLOAD_BYTES)),
            ),
            sweep_interval_seconds=int(
                os.environ.get(
                    "UPLOAD_SERVER_SWEEP_INTERVAL_SECONDS", str(DEFAULT_SWEEP_INTERVAL_SECONDS),
                ),
            ),
            generated_dir=os.environ.get("UPLOAD_SERVER_GENERATED_DIR", DEFAULT_GENERATED_DIR),
        )
