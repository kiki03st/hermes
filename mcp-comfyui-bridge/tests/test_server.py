from __future__ import annotations

import pytest

from comfyui_bridge import server


def test_generate_image_saves_file_and_returns_path(monkeypatch, tmp_path):
    from pathlib import Path

    monkeypatch.setattr(server, "OUTPUT_DIR", str(tmp_path))
    monkeypatch.setattr(server, "submit_prompt", lambda base_url, workflow, **kw: "prompt-1")
    monkeypatch.setattr(
        server,
        "wait_for_completion",
        lambda base_url, prompt_id, **kw: {
            "outputs": {"9": {"images": [{"filename": "hermes_00001_.png", "subfolder": "", "type": "output"}]}}
        },
    )
    monkeypatch.setattr(server, "download_output_image", lambda *a, **kw: b"\x89PNG-fake-bytes")

    result = server.generate_image(prompt="a red cabin", seed=42)

    assert result["seed_used"] == 42
    saved = Path(result["path"])
    assert saved.exists()
    assert saved.parent == tmp_path.resolve()
    assert saved.read_bytes() == b"\x89PNG-fake-bytes"


def test_generate_image_wraps_comfyui_errors_as_tool_error(monkeypatch):
    # mcp SDK는 ToolError/ResourceError가 아닌 예외를 전부 "크래시"로 취급해 모델에게
    # 이름뿐인 제네릭 메시지만 준다(실측 확인, tools/base.py 소스) — 그래서 우리 예외를
    # ToolError로 감싸야 실제 사유가 모델까지 전달된다. 이 테스트는 그 감싸기 자체를
    # 고정한다(라이브 게이트웨이 없이 검증 가능한 부분).
    from mcp.server.mcpserver.exceptions import ToolError

    from comfyui_bridge.client import ComfyUIUnavailableError

    def _raise(*a, **kw):
        raise ComfyUIUnavailableError("ComfyUI 안 떠있음")

    monkeypatch.setattr(server, "submit_prompt", _raise)

    with pytest.raises(ToolError) as exc_info:
        server.generate_image(prompt="a cat")
    assert "ComfyUI 안 떠있음" in str(exc_info.value)
