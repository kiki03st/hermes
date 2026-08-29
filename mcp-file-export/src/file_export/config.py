"""환경변수 기반 설정 — comfyui_bridge/config.py와 같은 패턴."""

from __future__ import annotations

import os


def default_output_dir() -> str:
    """문서 기본 저장 위치 — `upload-server/generated/files/` 밑. `upload-server`가
    이미 열려있는 포트/방화벽 규칙/Bearer 인증으로 이 파일을 그대로 폰에 서빙한다
    (설계 문서: `docs/superpowers/specs/2026-08-29-file-export-design.md`)."""
    repo_root = os.path.dirname(
        os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    )
    return os.path.join(repo_root, "upload-server", "generated", "files")


OUTPUT_DIR = os.environ.get("FILE_EXPORT_OUTPUT_DIR", default_output_dir())
