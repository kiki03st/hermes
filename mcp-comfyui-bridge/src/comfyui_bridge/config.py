"""환경변수 기반 설정 — upload-server/config.py와 같은 패턴."""

from __future__ import annotations

import os

BASE_URL = os.environ.get("COMFYUI_BASE_URL", "http://127.0.0.1:8188")
TIMEOUT_SECONDS = float(os.environ.get("COMFYUI_TIMEOUT_SECONDS", "120"))
OUTPUT_DIR = os.environ.get(
    "COMFYUI_BRIDGE_OUTPUT_DIR",
    os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "generated"),
)
