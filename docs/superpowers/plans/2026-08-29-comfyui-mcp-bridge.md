# ComfyUI MCP 브릿지 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 폰(`api_server` 플랫폼)에서 "그림 그려줘" 요청 시, 터미널 접근 없이 로컬
ComfyUI로 실제 이미지 파일을 생성하는 좁은 MCP 서버(`mcp-comfyui-bridge`)를 만든다.

**Architecture:** `mcp-acad-assist`와 동일한 패턴의 독립 파이썬 MCP stdio 서버.
`generate_image()` 함수 하나만 노출하고, 내부에서 이미 떠있는 ComfyUI(`comfy launch
--background`, `127.0.0.1:8188`)의 REST API(`/prompt`, `/history`, `/view`)를 HTTP로
호출한다. 터미널/코드실행 도구가 전혀 필요 없다.

**Tech Stack:** Python 3.11+, `mcp` SDK(mcp-acad-assist와 동일 버전), `requests`(신규
의존성 — HTTP 호출용), `pytest`.

**Spec:** `docs/superpowers/specs/2026-08-29-comfyui-mcp-bridge-design.md`

## Global Constraints

- ComfyUI 프로세스 자체는 이 서버가 대신 켜주지 않는다 — 안 떠있으면 명확한 에러만
  반환한다(스펙 §에러 처리).
- 노출하는 MCP 함수는 `generate_image` 딱 하나뿐이다 — 터미널/임의 코드 실행 기능을
  추가하지 않는다.
- 기본 모델은 SD1.5(`v1-5-pruned-emaonly.safetensors`), 기본 워크플로는 comfyui 스킬의
  `workflows/sd15_txt2img.json` 5노드 그래프를 그대로 쓴다 — 변형하지 않는다.
- `config.yaml`에 `mcp_servers.comfyui-bridge`로 등록하되 `trust: full`(승인 게이트
  불필요) — `platform_toolsets`는 건드리지 않는다(MCP 서버는 그걸로 안 걸림, 실측
  확인됨).
- FAL(`image_gen.provider`) 설정은 그대로 둔다 — 이 작업으로 끄거나 대체하지 않는다.

---

## Task 1: 프로젝트 스캐폴딩 + 워크플로 템플릿 주입 로직

**Files:**
- Create: `mcp-comfyui-bridge/pyproject.toml`
- Create: `mcp-comfyui-bridge/src/comfyui_bridge/__init__.py`
- Create: `mcp-comfyui-bridge/src/comfyui_bridge/workflow.py`
- Test: `mcp-comfyui-bridge/tests/test_workflow.py`

**Interfaces:**
- Produces: `build_workflow(prompt: str, negative_prompt: str = "", seed: int | None =
  None, width: int = 512, height: int = 512) -> dict` — Task 3(`server.py`)가 이걸로
  워크플로 dict를 만들어 Task 2(`client.py`)의 `submit_prompt`에 넘긴다.

- [ ] **Step 1: `pyproject.toml` 작성**

```toml
[project]
name = "comfyui-bridge"
version = "0.1.0"
description = "폰(api_server)에서 로컬 ComfyUI로 이미지를 생성하는 좁은 MCP 서버 — 터미널 접근 없이 REST API만 호출한다"
requires-python = ">=3.11"
dependencies = [
    "mcp>=2.0.0",
    "requests>=2.31",
]

[project.optional-dependencies]
dev = ["pytest>=8.0"]

[project.scripts]
comfyui-bridge = "comfyui_bridge.server:main"

[tool.pytest.ini_options]
testpaths = ["tests"]

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["src/comfyui_bridge"]
```

- [ ] **Step 2: `src/comfyui_bridge/__init__.py` 작성**

```python
"""폰에서 로컬 ComfyUI로 이미지를 생성하는 좁은 MCP 서버.

`generate_image` 함수 하나만 노출한다 — 터미널/코드실행 없이 ComfyUI의 REST API
(127.0.0.1:8188)를 HTTP로만 호출한다. `mcp-acad-assist`와 같은 패턴."""

__version__ = "0.1.0"
```

- [ ] **Step 3: 실패하는 테스트 작성 (`tests/test_workflow.py`)**

```python
from comfyui_bridge.workflow import build_workflow


def test_build_workflow_injects_positive_and_negative_prompt():
    wf = build_workflow("a red cabin", negative_prompt="blurry")

    assert wf["6"]["inputs"]["text"] == "a red cabin"
    assert wf["7"]["inputs"]["text"] == "blurry"


def test_build_workflow_defaults_negative_prompt_to_empty_string():
    wf = build_workflow("a cat")

    assert wf["7"]["inputs"]["text"] == ""


def test_build_workflow_uses_explicit_seed():
    wf = build_workflow("a cat", seed=42)

    assert wf["3"]["inputs"]["seed"] == 42


def test_build_workflow_randomizes_seed_when_omitted():
    seeds = {build_workflow("a cat")["3"]["inputs"]["seed"] for _ in range(20)}

    assert len(seeds) > 1  # 20번 중 전부 같은 값일 확률은 무시할 수준


def test_build_workflow_uses_default_resolution():
    wf = build_workflow("a cat")

    assert wf["5"]["inputs"]["width"] == 512
    assert wf["5"]["inputs"]["height"] == 512


def test_build_workflow_accepts_custom_resolution():
    wf = build_workflow("a cat", width=768, height=768)

    assert wf["5"]["inputs"]["width"] == 768
    assert wf["5"]["inputs"]["height"] == 768


def test_build_workflow_keeps_checkpoint_and_graph_wiring_fixed():
    wf = build_workflow("a cat")

    assert wf["4"]["inputs"]["ckpt_name"] == "v1-5-pruned-emaonly.safetensors"
    assert wf["9"]["class_type"] == "SaveImage"
    assert wf["9"]["inputs"]["images"] == ["8", 0]
```

- [ ] **Step 4: 테스트 실행해서 실패 확인**

Run (`mcp-comfyui-bridge` 디렉터리에서): `python -m pip install -e ".[dev]" && python -m pytest tests/test_workflow.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'comfyui_bridge.workflow'`

- [ ] **Step 5: `src/comfyui_bridge/workflow.py` 구현**

```python
"""SD1.5 txt2img 워크플로 템플릿 — comfyui 스킬의 workflows/sd15_txt2img.json을 그대로
파이썬 상수로 옮기고, prompt/negative/seed/해상도만 주입한다. 그래프 배선(노드 번호,
class_type, 노드 간 연결)은 절대 바꾸지 않는다 — 바뀌면 ComfyUI가 이 그래프를 다르게
해석한다."""

from __future__ import annotations

import random
from typing import Any

_CHECKPOINT_NAME = "v1-5-pruned-emaonly.safetensors"

# 32비트 부호 없는 정수 범위 — ComfyUI의 시드 필드가 기대하는 범위(comfyui 스킬 문서
# 확인: -1은 스킬 스크립트의 전처리일 뿐 ComfyUI 서버 자체 관례가 아니라서, 여기서
# 직접 랜덤 정수를 굴린다).
_MAX_SEED = 2**32 - 1


def build_workflow(
    prompt: str,
    negative_prompt: str = "",
    seed: int | None = None,
    width: int = 512,
    height: int = 512,
) -> dict[str, Any]:
    resolved_seed = seed if seed is not None else random.randint(0, _MAX_SEED)

    return {
        "3": {
            "class_type": "KSampler",
            "inputs": {
                "seed": resolved_seed,
                "steps": 20,
                "cfg": 8.0,
                "sampler_name": "euler",
                "scheduler": "normal",
                "denoise": 1.0,
                "model": ["4", 0],
                "positive": ["6", 0],
                "negative": ["7", 0],
                "latent_image": ["5", 0],
            },
        },
        "4": {
            "class_type": "CheckpointLoaderSimple",
            "inputs": {"ckpt_name": _CHECKPOINT_NAME},
        },
        "5": {
            "class_type": "EmptyLatentImage",
            "inputs": {"width": width, "height": height, "batch_size": 1},
        },
        "6": {
            "class_type": "CLIPTextEncode",
            "inputs": {"text": prompt, "clip": ["4", 1]},
        },
        "7": {
            "class_type": "CLIPTextEncode",
            "inputs": {"text": negative_prompt, "clip": ["4", 1]},
        },
        "8": {
            "class_type": "VAEDecode",
            "inputs": {"samples": ["3", 0], "vae": ["4", 2]},
        },
        "9": {
            "class_type": "SaveImage",
            "inputs": {"filename_prefix": "hermes", "images": ["8", 0]},
        },
    }
```

- [ ] **Step 6: 테스트 실행해서 통과 확인**

Run: `python -m pytest tests/test_workflow.py -v`
Expected: PASS (7 tests)

- [ ] **Step 7: 커밋**

```bash
git add mcp-comfyui-bridge/pyproject.toml mcp-comfyui-bridge/src/comfyui_bridge/__init__.py mcp-comfyui-bridge/src/comfyui_bridge/workflow.py mcp-comfyui-bridge/tests/test_workflow.py
git commit -m "feat(comfyui-bridge): scaffold project, add SD1.5 workflow template injection"
```

---

## Task 2: ComfyUI REST API 클라이언트

**Files:**
- Create: `mcp-comfyui-bridge/src/comfyui_bridge/client.py`
- Test: `mcp-comfyui-bridge/tests/test_client.py`

**Interfaces:**
- Consumes: 없음(Task 1과 독립 — `workflow.py`가 만든 dict를 인자로 받을 뿐 직접
  import하지 않는다).
- Produces: `submit_prompt(base_url: str, workflow: dict, *, http=requests) -> str`,
  `wait_for_completion(base_url: str, prompt_id: str, *, timeout_seconds: float = 120,
  poll_interval_seconds: float = 1.0, http=requests) -> dict`,
  `download_output_image(base_url: str, filename: str, subfolder: str, file_type: str,
  *, http=requests) -> bytes`, 예외 `ComfyUIUnavailableError`, `ComfyUIExecutionError`,
  `ComfyUITimeoutError` — Task 3(`server.py`)이 이 셋을 그대로 쓴다.

- [ ] **Step 1: 실패하는 테스트 작성 (`tests/test_client.py`)**

가짜 HTTP 클라이언트로 실제 소켓 없이 검증한다(mcp-acad-assist의 fake COM 패턴과 같은
철학 — `requests` 모듈과 같은 모양의 `.post()`/`.get()`만 흉내낸다).

```python
from __future__ import annotations

import pytest
import requests

from comfyui_bridge.client import (
    ComfyUIExecutionError,
    ComfyUITimeoutError,
    ComfyUIUnavailableError,
    download_output_image,
    submit_prompt,
    wait_for_completion,
)


class _FakeResponse:
    def __init__(self, status_code: int = 200, json_body: dict | None = None, content: bytes = b""):
        self.status_code = status_code
        self._json_body = json_body or {}
        self.content = content

    def json(self):
        return self._json_body


class _FakeHttp:
    def __init__(self, post_response=None, get_responses=None):
        self.post_response = post_response
        self.get_responses = list(get_responses or [])
        self.get_calls: list[str] = []
        self.post_calls: list[tuple[str, dict]] = []

    def post(self, url, json=None, **kwargs):
        self.post_calls.append((url, json))
        return self.post_response

    def get(self, url, **kwargs):
        self.get_calls.append(url)
        return self.get_responses.pop(0)


def test_submit_prompt_returns_prompt_id():
    http = _FakeHttp(post_response=_FakeResponse(200, {"prompt_id": "abc-123", "node_errors": {}}))

    prompt_id = submit_prompt("http://127.0.0.1:8188", {"3": {}}, http=http)

    assert prompt_id == "abc-123"
    assert http.post_calls[0][0] == "http://127.0.0.1:8188/prompt"


def test_submit_prompt_raises_on_node_errors():
    http = _FakeHttp(post_response=_FakeResponse(200, {"prompt_id": "abc", "node_errors": {"3": ["bad seed"]}}))

    with pytest.raises(ComfyUIExecutionError):
        submit_prompt("http://127.0.0.1:8188", {"3": {}}, http=http)


def test_submit_prompt_raises_unavailable_on_connection_error():
    class _RaisingHttp:
        def post(self, url, json=None, **kwargs):
            raise requests.exceptions.ConnectionError("refused")

    with pytest.raises(ComfyUIUnavailableError):
        submit_prompt("http://127.0.0.1:8188", {"3": {}}, http=_RaisingHttp())


def test_wait_for_completion_polls_until_done():
    pending = _FakeResponse(200, {"abc": {"status": {"status_str": "", "completed": False}}})
    done = _FakeResponse(
        200,
        {
            "abc": {
                "status": {"status_str": "success", "completed": True},
                "outputs": {"9": {"images": [{"filename": "hermes_00001_.png", "subfolder": "", "type": "output"}]}},
            }
        },
    )
    http = _FakeHttp(get_responses=[pending, done])

    result = wait_for_completion("http://127.0.0.1:8188", "abc", poll_interval_seconds=0, http=http)

    assert result["status"]["completed"] is True


def test_wait_for_completion_raises_execution_error_on_failure():
    failed = _FakeResponse(200, {"abc": {"status": {"status_str": "error", "completed": True}, "outputs": {}}})
    http = _FakeHttp(get_responses=[failed])

    with pytest.raises(ComfyUIExecutionError):
        wait_for_completion("http://127.0.0.1:8188", "abc", poll_interval_seconds=0, http=http)


def test_wait_for_completion_raises_timeout(monkeypatch):
    import time

    pending = _FakeResponse(200, {"abc": {"status": {"status_str": "", "completed": False}}})
    http = _FakeHttp(get_responses=[pending] * 3)
    clock = iter([0.0, 0.5, 10.0])
    monkeypatch.setattr(time, "monotonic", lambda: next(clock))

    with pytest.raises(ComfyUITimeoutError):
        wait_for_completion("http://127.0.0.1:8188", "abc", timeout_seconds=1.0, poll_interval_seconds=0, http=http)


def test_download_output_image_returns_bytes():
    http = _FakeHttp()
    http.get_responses = [_FakeResponse(200, content=b"\x89PNG...")]

    data = download_output_image("http://127.0.0.1:8188", "hermes_00001_.png", "", "output", http=http)

    assert data == b"\x89PNG..."
    assert "filename=hermes_00001_.png" in http.get_calls[0]
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `python -m pytest tests/test_client.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'comfyui_bridge.client'`

- [ ] **Step 3: `src/comfyui_bridge/client.py` 구현**

```python
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
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `python -m pytest tests/test_client.py -v`
Expected: PASS (7 tests)

- [ ] **Step 5: 커밋**

```bash
git add mcp-comfyui-bridge/src/comfyui_bridge/client.py mcp-comfyui-bridge/tests/test_client.py
git commit -m "feat(comfyui-bridge): add ComfyUI REST API client with fake-HTTP tests"
```

---

## Task 3: MCP 서버 진입점 — `generate_image`

**Files:**
- Create: `mcp-comfyui-bridge/src/comfyui_bridge/config.py`
- Create: `mcp-comfyui-bridge/src/comfyui_bridge/server.py`
- Test: `mcp-comfyui-bridge/tests/test_server.py`

**Interfaces:**
- Consumes: `build_workflow`(Task 1), `submit_prompt`/`wait_for_completion`/
  `download_output_image`/예외 3종(Task 2).
- Produces: MCP 도구 `generate_image(prompt: str, negative_prompt: str = "", seed:
  int | None = None, width: int = 512, height: int = 512) -> dict[str, Any]` — 성공 시
  `{"path": str, "seed_used": int}`. config.yaml 등록(Task 4)이 이 서버(`comfyui-bridge`
  콘솔 스크립트)를 가리킨다.

- [ ] **Step 1: `src/comfyui_bridge/config.py` 작성**

```python
"""환경변수 기반 설정 — upload-server/config.py와 같은 패턴."""

from __future__ import annotations

import os

BASE_URL = os.environ.get("COMFYUI_BASE_URL", "http://127.0.0.1:8188")
TIMEOUT_SECONDS = float(os.environ.get("COMFYUI_TIMEOUT_SECONDS", "120"))
OUTPUT_DIR = os.environ.get(
    "COMFYUI_BRIDGE_OUTPUT_DIR",
    os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "generated"),
)
```

- [ ] **Step 2: 실패하는 테스트 작성 (`tests/test_server.py`)**

```python
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
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `python -m pytest tests/test_server.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'comfyui_bridge.server'`

- [ ] **Step 4: `src/comfyui_bridge/server.py` 구현**

```python
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
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `python -m pytest -v`
Expected: PASS (Task 1+2+3 합쳐 16개)

- [ ] **Step 6: 커밋**

```bash
git add mcp-comfyui-bridge/src/comfyui_bridge/config.py mcp-comfyui-bridge/src/comfyui_bridge/server.py mcp-comfyui-bridge/tests/test_server.py
git commit -m "feat(comfyui-bridge): add generate_image MCP tool entrypoint"
```

---

## Task 4: config.yaml 등록 템플릿 + README + gitignore

**Files:**
- Modify: `hermes-config/config.yaml.example`
- Create: `mcp-comfyui-bridge/README.md`
- Modify: `.gitignore`

**Interfaces:** 없음(문서/설정만).

- [ ] **Step 1: `hermes-config/config.yaml.example`에 등록 블록 추가**

`mcp_servers:` 블록의 `calendar:` 항목 위나 아래, 같은 들여쓰기 수준에 추가:

```yaml
  # --- 이미지 생성 (comfyui-bridge, 이 리포 신규 구현) ---
  # 로컬 ComfyUI의 REST API(127.0.0.1:8188)를 대신 호출하는 좁은 MCP 서버 —
  # generate_image 함수 하나만 노출한다. platform_toolsets 등록 불필요(MCP 서버는
  # 그걸로 안 걸림, calendar와 동일). ComfyUI 자체는 별도로 미리 켜둬야 한다:
  # comfy launch --background (docs/backend-new-machine-setup.md §3 참고).
  comfyui-bridge:
    command: "comfyui-bridge"
    trust: full
```

- [ ] **Step 2: `mcp-comfyui-bridge/README.md` 작성**

```markdown
# comfyui-bridge

폰 앱에서 로컬 ComfyUI로 이미지를 생성하는 좁은 MCP 서버. `generate_image` 함수 하나만
노출한다 — 터미널/코드실행 없이 ComfyUI의 REST API(127.0.0.1:8188)만 HTTP로 호출한다.

## 사전 조건

ComfyUI가 SD1.5 체크포인트(`v1-5-pruned-emaonly.safetensors`)와 함께 이미 떠있어야
한다:

```powershell
comfy launch --background
curl.exe http://127.0.0.1:8188/system_stats   # 확인
```

## 설치

```bash
cd mcp-comfyui-bridge
python -m pip install -e ".[dev]"
```

`hermes-config/config.yaml.example`의 `mcp_servers.comfyui-bridge` 블록을
`%LOCALAPPDATA%\hermes\config.yaml`에 병합한 뒤 `hermes gateway restart`.

## 환경변수 (전부 선택, 기본값 있음)

| 변수 | 기본값 |
|---|---|
| `COMFYUI_BASE_URL` | `http://127.0.0.1:8188` |
| `COMFYUI_TIMEOUT_SECONDS` | `120` |
| `COMFYUI_BRIDGE_OUTPUT_DIR` | `mcp-comfyui-bridge/generated/` |

## 테스트

```bash
python -m pytest -v
```

## 검증

```bash
hermes mcp list   # comfyui-bridge가 뜨는지
```

폰에서 "그림 그려줘" 요청 → `generated/` 안에 실제 PNG 파일이 생기는지 확인.
```

- [ ] **Step 3: `.gitignore`에 출력 폴더 추가**

`.gitignore`의 `upload-server/uploads/` 줄 근처에 추가:

```
# comfyui-bridge 런타임 산출물 — 생성된 이미지 파일, 소스 아님
mcp-comfyui-bridge/generated/
```

- [ ] **Step 4: 커밋**

```bash
git add hermes-config/config.yaml.example mcp-comfyui-bridge/README.md .gitignore
git commit -m "docs(comfyui-bridge): add config registration template, README, gitignore"
```

---

## Task 5: ComfyUI 재설치 + 실측 검증 (실행 인프라, 유닛테스트 아님)

**Files:** 없음(설치·실측만).

- [ ] **Step 1: ComfyUI + comfy-cli 재설치**

```powershell
python -m pip install --user pipx
pipx install comfy-cli
comfy --skip-prompt tracking disable
comfy --skip-prompt install --nvidia
```

- [ ] **Step 2: SD1.5 체크포인트 다운로드**

```powershell
comfy model download --url "https://huggingface.co/stable-diffusion-v1-5/stable-diffusion-v1-5/resolve/main/v1-5-pruned-emaonly.safetensors" --relative-path models/checkpoints
```

- [ ] **Step 3: ComfyUI 기동 + 헬스체크**

```powershell
comfy launch --background
curl.exe http://127.0.0.1:8188/system_stats
```

Expected: JSON 응답, `devices`에 CUDA GPU가 보임.

- [ ] **Step 4: `hermes mcp list`로 등록 확인**

Task 4에서 병합한 `config.yaml`로 `hermes gateway restart` 후:

```powershell
hermes mcp list
```

Expected: `comfyui-bridge`가 `✓ enabled`로 나옴.

- [ ] **Step 5: 실제 이미지 생성 실측 (curl로 `/v1/runs` 직접 호출)**

```powershell
$body = '{"input": "귀여운 고양이 그림을 comfyui-bridge로 생성해줘"}'
curl.exe -X POST http://<HOST>:8642/v1/runs -H "Authorization: Bearer $env:API_SERVER_KEY" -H "Content-Type: application/json" -d $body
# 응답의 run_id로 이벤트 스트리밍 확인
curl.exe -N http://<HOST>:8642/v1/runs/<run_id>/events -H "Authorization: Bearer $env:API_SERVER_KEY"
```

Expected: `tool.started`/`tool.completed`에 `generate_image`가 찍히고, 최종 답변에
`mcp-comfyui-bridge/generated/` 밑 실제 파일 경로가 언급됨. 그 경로를 직접 열어서
진짜 이미지가 맞는지 확인.

- [ ] **Step 6: 실패 케이스 실측 — ComfyUI 끈 상태로 재시도**

```powershell
comfy stop
# 위와 같은 curl 요청 재실행
```

Expected: 에이전트가 "ComfyUI가 실행 중인지 확인하세요" 류의 명확한 에러를 답함(무한
루프나 다른 방법 우회 시도 없이).
