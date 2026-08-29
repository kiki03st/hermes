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


def test_generate_image_propagates_comfyui_errors(monkeypatch):
    from comfyui_bridge.client import ComfyUIUnavailableError

    def _raise(*a, **kw):
        raise ComfyUIUnavailableError("ComfyUI 안 떠있음")

    monkeypatch.setattr(server, "submit_prompt", _raise)

    with pytest.raises(ComfyUIUnavailableError):
        server.generate_image(prompt="a cat")
