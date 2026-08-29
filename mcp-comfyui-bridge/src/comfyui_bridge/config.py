"""환경변수 기반 설정 — upload-server/config.py와 같은 패턴."""

from __future__ import annotations

import os


def default_output_dir() -> str:
    """생성 이미지 기본 저장 위치 — `upload-server/generated/comfyui/` 밑.
    `upload-server`가 이미 열려있는 포트/방화벽 규칙/Bearer 인증으로 이 파일을
    그대로 폰에 서빙한다(설계 문서: `docs/superpowers/specs/2026-08-29-image-viewer-design.md`
    §저장 위치 통일 — `mcp-comfyui-bridge/generated/`는 더 이상 안 씀)."""
    repo_root = os.path.dirname(
        os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    )
    return os.path.join(repo_root, "upload-server", "generated", "comfyui")


BASE_URL = os.environ.get("COMFYUI_BASE_URL", "http://127.0.0.1:8188")
TIMEOUT_SECONDS = float(os.environ.get("COMFYUI_TIMEOUT_SECONDS", "120"))
OUTPUT_DIR = os.environ.get("COMFYUI_BRIDGE_OUTPUT_DIR", default_output_dir())
