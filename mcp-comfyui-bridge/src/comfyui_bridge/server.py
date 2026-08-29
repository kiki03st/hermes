"""comfyui-bridge MCP stdio 서버. `generate_image` 하나만 노출한다 — 터미널/코드실행이
전혀 필요 없다, ComfyUI가 이미 노출하는 REST API만 호출한다."""

from __future__ import annotations

import uuid
from pathlib import Path
from typing import Any

from mcp.server.mcpserver import MCPServer

from .client import download_output_image, submit_prompt, wait_for_completion
from .config import BASE_URL, OUTPUT_DIR, TIMEOUT_SECONDS
from .workflow import build_workflow

mcp = MCPServer("comfyui-bridge")


@mcp.tool()
def generate_image(
    prompt: str,
    negative_prompt: str = "",
    seed: int | None = None,
    width: int = 512,
    height: int = 512,
) -> dict[str, Any]:
    """SD1.5로 이미지를 생성해 파일로 저장하고 절대경로를 돌려준다.

    ComfyUI(127.0.0.1:8188)가 이미 실행 중이어야 한다('comfy launch --background') —
    이 도구는 ComfyUI를 대신 켜주지 않는다. 몇 초~수십 초 걸릴 수 있다.
    """
    workflow = build_workflow(prompt, negative_prompt=negative_prompt, seed=seed, width=width, height=height)
    resolved_seed = workflow["3"]["inputs"]["seed"]

    prompt_id = submit_prompt(BASE_URL, workflow)
    entry = wait_for_completion(BASE_URL, prompt_id, timeout_seconds=TIMEOUT_SECONDS)

    image_info = entry["outputs"]["9"]["images"][0]
    data = download_output_image(
        BASE_URL, image_info["filename"], image_info.get("subfolder", ""), image_info.get("type", "output")
    )

    output_dir = Path(OUTPUT_DIR)
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / f"{uuid.uuid4().hex[:8]}_{image_info['filename']}"
    target.write_bytes(data)

    return {"path": str(target.resolve()), "seed_used": resolved_seed}


def main() -> None:
    mcp.run()


if __name__ == "__main__":
    main()
