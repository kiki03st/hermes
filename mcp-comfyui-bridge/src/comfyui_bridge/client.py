"""ComfyUI REST API 왕복 — 순수 HTTP 호출만, 워크플로 내용은 모른다(Task 1의 workflow.py
가 만든 dict를 그대로 받아 전달할 뿐). `http` 인자는 기본값이 `requests` 모듈 자체다 —
`requests.post`/`requests.get`과 같은 모양(`.post(url, json=...)`/`.get(url)`, 반환값에
`.status_code`/`.json()`/`.content`)이면 뭐든 넣을 수 있어, 테스트에서는 가짜로 교체한다."""

from __future__ import annotations

import time
from typing import Any

import requests


class ComfyUIUnavailableError(RuntimeError):
    """ComfyUI 서버에 연결할 수 없을 때 — 안 떠있거나 방화벽에 막혔을 때."""


class ComfyUIExecutionError(RuntimeError):
    """워크플로 자체가 ComfyUI에서 에러로 끝났을 때(node_errors, 실행 중 에러)."""


class ComfyUITimeoutError(RuntimeError):
    """제한 시간 안에 완료되지 않았을 때."""


def submit_prompt(base_url: str, workflow: dict[str, Any], *, http: Any = requests) -> str:
    try:
        response = http.post(f"{base_url}/prompt", json={"prompt": workflow})
    except requests.exceptions.ConnectionError as exc:
        raise ComfyUIUnavailableError(
            f"ComfyUI({base_url})에 연결할 수 없습니다 — 'comfy launch --background'로 켜져있는지 확인하세요."
        ) from exc

    body = response.json()
    node_errors = body.get("node_errors") or {}
    if node_errors:
        raise ComfyUIExecutionError(f"워크플로 검증 실패: {node_errors}")

    prompt_id = body.get("prompt_id")
    if not prompt_id:
        raise ComfyUIExecutionError(f"응답에서 prompt_id를 찾을 수 없습니다: {body}")
    return prompt_id


def wait_for_completion(
    base_url: str,
    prompt_id: str,
    *,
    timeout_seconds: float = 120,
    poll_interval_seconds: float = 1.0,
    http: Any = requests,
) -> dict[str, Any]:
    start = time.monotonic()
    while True:
        try:
            response = http.get(f"{base_url}/history/{prompt_id}")
        except requests.exceptions.ConnectionError as exc:
            raise ComfyUIUnavailableError(
                f"ComfyUI({base_url})에 연결할 수 없습니다 — 'comfy launch --background'로 켜져있는지 확인하세요."
            ) from exc

        body = response.json()
        entry = body.get(prompt_id)
        if entry is not None:
            status = entry.get("status", {})
            if status.get("status_str") == "error":
                raise ComfyUIExecutionError(f"ComfyUI 실행 중 에러: {status}")
            if status.get("completed"):
                return entry

        if time.monotonic() - start > timeout_seconds:
            raise ComfyUITimeoutError(
                f"{timeout_seconds}초 안에 완료되지 않았습니다(prompt_id={prompt_id})."
            )
        time.sleep(poll_interval_seconds)


def download_output_image(
    base_url: str, filename: str, subfolder: str, file_type: str, *, http: Any = requests
) -> bytes:
    try:
        response = http.get(
            f"{base_url}/view?filename={filename}&subfolder={subfolder}&type={file_type}"
        )
    except requests.exceptions.ConnectionError as exc:
        raise ComfyUIUnavailableError(
            f"ComfyUI({base_url})에 연결할 수 없습니다 — 'comfy launch --background'로 켜져있는지 확인하세요."
        ) from exc
    return response.content
