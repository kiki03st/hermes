"""comfyui-bridge MCP stdio 서버. `generate_image` 하나만 노출한다 — 터미널/코드실행이
전혀 필요 없다, ComfyUI가 이미 노출하는 REST API만 호출한다."""

from __future__ import annotations

import sys
import uuid
from pathlib import Path
from typing import Any

from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError

from .client import (
    ComfyUIExecutionError,
    ComfyUITimeoutError,
    ComfyUIUnavailableError,
    download_output_image,
    submit_prompt,
    wait_for_completion,
)
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
    """사용자가 그림/이미지를 그려달라고, 만들어달라고 요청하면 이 도구를 사용해서
    실제 이미지 파일을 생성한다 — ASCII 아트나 텍스트 설명으로 대체하지 말 것.
    SD1.5로 로컬 GPU에서 무료로 생성해 파일로 저장하고 절대경로를 돌려준다.

    ComfyUI(127.0.0.1:8188)가 이미 실행 중이어야 한다('comfy launch --background') —
    이 도구는 ComfyUI를 대신 켜주지 않는다. 몇 초~수십 초 걸릴 수 있다.
    """
    try:
        workflow = build_workflow(prompt, negative_prompt=negative_prompt, seed=seed, width=width, height=height)
        resolved_seed = workflow["3"]["inputs"]["seed"]

        prompt_id = submit_prompt(BASE_URL, workflow)
        entry = wait_for_completion(BASE_URL, prompt_id, timeout_seconds=TIMEOUT_SECONDS)

        image_info = entry["outputs"]["9"]["images"][0]
        data = download_output_image(
            BASE_URL, image_info["filename"], image_info.get("subfolder", ""), image_info.get("type", "output")
        )
    except (ComfyUIUnavailableError, ComfyUIExecutionError, ComfyUITimeoutError) as exc:
        # client.py는 mcp SDK를 모르는 순수 HTTP 계층으로 남겨둔다(스펙 §client.py) —
        # 그래서 여기 server.py에서 우리 예외를 mcp의 ToolError로 감싼다. **이게 없으면
        # 메시지가 아예 클라이언트한테 안 간다** — mcp SDK 소스(tools/base.py) 확인:
        # ToolError/ResourceError가 아닌 예외는 전부 "크래시"로 취급되어 모델은
        # "Error executing tool <name>"이라는 이름뿐인 메시지만 받고, 우리가 공들여
        # 쓴 실제 사유(예: "comfy launch --background로 켜져있는지 확인하세요")는
        # 서버 로그에만 남고 버려진다(실측 확인, 2026-08-29 — ComfyUI를 꺼둔 채로
        # /v1/runs를 실제로 호출해서 재현·확인했다). ToolError로 감싸면 그 문구가
        # 그대로 모델에게 전달된다.
        raise ToolError(str(exc)) from exc

    output_dir = Path(OUTPUT_DIR)
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / f"{uuid.uuid4().hex[:8]}_{image_info['filename']}"
    target.write_bytes(data)

    return {"path": str(target.resolve()), "seed_used": resolved_seed}


def main() -> None:
    # MCP stdio 프로토콜은 표준입출력으로 JSON을 주고받는다 — 이 Windows PC의 기본
    # 콘솔 인코딩(cp949)은 한글 텍스트의 em-dash(—) 등을 못 담아 UnicodeEncodeError를
    # 낼 수 있다(실측 확인). stdio는 항상 UTF-8이어야 하는 프로토콜이라 명시적으로
    # 강제한다 — 위 ToolError 감싸기와는 별개의, 방어적인 조치다(로깅 등에서 같은
    # 문자가 또 문제를 일으키는 걸 막는다).
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    mcp.run()


if __name__ == "__main__":
    main()
