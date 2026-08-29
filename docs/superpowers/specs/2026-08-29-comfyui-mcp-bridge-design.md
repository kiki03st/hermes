# ComfyUI MCP 브릿지 — 폰에서 쓰는 로컬 무료 이미지 생성

## 배경

폰 앱(`api_server` 플랫폼)에서 이미지 생성을 시도해본 결과(2026-08-29 실측) 두 가지가
확인됐다:

1. **Hermes 내장 `image_gen`(FAL 등 provider 방식)** — 설정 자체는 됐고 실제로 API
   호출까지 감(FAL 계정 크레딧 부족으로 결과물은 못 받음). 이건 유료 API 호출 방식이라
   써도 되지만, 크레딧이 있어야만 동작한다.
2. **ComfyUI(공식 `comfyui` 스킬)** — 로컬 GPU로 완전 무료로 그릴 수 있지만, 그 스킬은
   `comfy` CLI를 **터미널에서 실행**해야 하는 방식이다. `api_server` 플랫폼엔
   `terminal`/`code_execution` 툴셋이 아예 없어서(의도적으로 좁혀둔 것 — 네트워크에
   노출된 채널이라 임의 명령 실행을 안 준다) 에이전트가 스킬을 찾아내고도 실행할 방법이
   없어 30턴 넘게 우회 시도만 반복하다 실패했다.

`terminal`/`code_execution`을 `api_server`에 통째로 열어주는 방법도 있지만, 그러면
이미지 생성 하나 때문에 폰 채널 전체가 "이 PC에서 임의 명령 실행 가능" 상태가 된다 —
과도한 위험(오늘 확인한 캘린더 오작동 사례처럼, 에이전트가 잘못 판단했을 때의 피해
범위가 완전히 달라진다).

대신, 캘린더 MCP(`@cocal/google-calendar-mcp`)나 이 리포의 `mcp-acad-assist`가 이미
쓰는 패턴 — **딱 필요한 함수 하나만 노출하는 좁은 MCP 서버** — 를 ComfyUI에도 그대로
적용한다. ComfyUI 자체는 REST API(`127.0.0.1:8188`)를 이미 갖고 있어서, 터미널 없이도
HTTP 호출만으로 이미지를 생성/조회할 수 있다.

## 목표

- 폰에서 "그림 그려줘" 요청 시, 터미널 접근 없이도 로컬 GPU로 실제 이미지 파일이
  생성된다.
- 새 MCP 서버는 딱 이미지 생성 함수 하나만 노출 — 터미널/파일시스템 전반에 대한 접근권을
  주지 않는다.
- 기존 FAL(`image_gen.provider`) 경로는 그대로 둔다 — 서로 대체가 아니라 병행.

## 범위 밖

- ComfyUI 자체 설치/실행 자동화(예: 우리 서버가 ComfyUI를 대신 켜주는 것) — 지금은
  upload-server처럼 사람이 `comfy launch --background`로 미리 켜둔다는 전제.
- img2img/inpaint/ControlNet 등 고급 워크플로 — 처음엔 텍스트→이미지(txt2img)만.
- 비디오/오디오 생성 — comfyui 스킬이 지원하지만 이번 범위 밖.
- Comfy Cloud 경로 — 로컬 전용으로 시작(무료가 목적이라 로컬이 맞음).

## 아키텍처

```
[Hermes 에이전트] --MCP(stdio, JSON-RPC)--> [mcp-comfyui-bridge] --HTTP(REST)--> [ComfyUI :8188]
                                                    |
                                                    +--> generated/<uuid8>_<prefix>.png 저장
```

`mcp-comfyui-bridge`는 `mcp-acad-assist`와 완전히 같은 패턴의 독립 파이썬 프로젝트다 —
`mcp` SDK, `MCPServer`, `@mcp.tool()`. ComfyUI 프로세스 자체와는 별개이며, ComfyUI가
안 떠있으면 명확한 에러를 돌려줄 뿐 대신 실행하지 않는다(단순함 유지, upload-server와
같은 원칙).

## 컴포넌트

### `workflow.py` — 순수 로직, 네트워크 없음

SD1.5 txt2img 워크플로(comfyui 스킬의 `workflows/sd15_txt2img.json`, `CheckpointLoaderSimple`
→ `CLIPTextEncode`(긍정/부정) → `KSampler` → `VAEDecode` → `SaveImage` 5노드 그래프)를
파이썬 상수로 내장한다.

```python
def build_workflow(
    prompt: str,
    negative_prompt: str = "",
    seed: int | None = None,
    width: int = 512,
    height: int = 512,
) -> dict:
    """SD1.5 txt2img 템플릿에 값을 주입한 워크플로 dict를 돌려준다."""
```

seed가 `None`이면 매번 랜덤(음수 없이 32비트 범위 내에서 `random.randint`) — ComfyUI
자체의 "-1 = 랜덤" 관례를 그대로 따르지 않고 우리가 직접 굴린다(REST API는 seed
필드가 항상 정수를 기대하고, `-1`을 랜덤으로 해석하는 건 skill 스크립트의 앞단
전처리이지 ComfyUI 서버 자체 동작이 아니라서 — comfyui 스킬 문서 재확인 완료).

### `client.py` — HTTP 왕복, 순수 함수

```python
def submit_prompt(base_url: str, workflow: dict) -> str:
    """POST /prompt, prompt_id를 돌려준다. node_errors가 있으면 예외."""

def wait_for_completion(base_url: str, prompt_id: str, *, timeout_seconds: float = 120) -> dict:
    """/history/{id}를 폴링. status_str == "error"면 즉시 실패, 완료되면 history 엔트리를 돌려준다."""

def download_output_image(base_url: str, filename: str, subfolder: str, file_type: str) -> bytes:
    """/view로 실제 PNG 바이트를 받아온다."""
```

`requests` 라이브러리 사용(이미 mcp-acad-assist류 프로젝트에 익숙한 최소 의존성).
연결 실패(ComfyUI 안 떠있음)는 `ComfyUIUnavailableError`로 감싸서 서버 쪽에서 사람이
읽을 에러 메시지로 변환한다.

### `server.py` — MCP 진입점, 함수 하나

```python
@mcp.tool()
def generate_image(
    prompt: str,
    negative_prompt: str = "",
    seed: int | None = None,
    width: int = 512,
    height: int = 512,
) -> dict[str, Any]:
    """SD1.5로 이미지를 생성해 파일로 저장하고 경로를 돌려준다."""
```

성공 시 `{"path": "<절대경로>", "seed_used": <int>}`. 실패 시(ComfyUI 미기동, 타임아웃,
node_errors, execution_error) 명확한 사유가 담긴 예외 — MCP 프로토콜이 이를 도구 에러로
변환해 에이전트에게 전달한다(에이전트가 "ComfyUI가 안 켜져있나봐요, 확인해주세요" 같은
답을 할 수 있게).

출력 폴더: `mcp-comfyui-bridge/generated/`(리포 안, upload-server의 `uploads/`와 같은
성격 — .gitignore 대상, 자동 정리 없음·필요해지면 나중에 추가).

## 등록 (`config.yaml`)

```yaml
mcp_servers:
  comfyui-bridge:
    command: "comfyui-bridge"
    trust: full
```

`platform_toolsets`는 안 건드린다 — MCP 서버는 캘린더처럼 등록만 하면 `api_server`에서
바로 쓰인다(2026-08-29 실측 확인, 오늘 세션 대화 기록).

## 에러 처리

| 상황 | 처리 |
|---|---|
| ComfyUI 연결 안 됨(포트 8188 무응답) | `ComfyUIUnavailableError` — "ComfyUI가 실행 중인지 확인하세요(`comfy launch --background`)" |
| `/prompt` 응답에 `node_errors` 존재 | 워크플로 자체 문제 — 그대로 노출(우리 템플릿은 고정이라 사실상 안 나야 정상, 나오면 버그) |
| 폴링 중 `status_str == "error"` | ComfyUI 실행 중 에러(모델 없음, VRAM 부족 등) — 원문 메시지 그대로 전달 |
| 타임아웃(기본 120초) | `ComfyUITimeoutError` — 생성이 너무 오래 걸림(다른 작업이 큐에 밀렸거나 VRAM 경합) |

## 테스트 계획

- `workflow.py`: 순수 함수 — prompt/negative/seed/width/height 주입이 정확한 노드에
  들어가는지, seed 생략 시 매번 다른 값이 나오는지.
- `client.py`: `requests`를 가짜(fake response)로 교체해 성공/실패/타임아웃/연결거부
  각각 검증(mcp-acad-assist의 fake COM 패턴과 동일한 철학).
- `server.py`: `client.py`를 모의(monkeypatch)해서 `generate_image`가 파일을 실제로
  쓰고 절대경로를 돌려주는지, 에러 케이스마다 올바른 예외 타입으로 이어지는지.
- 실기기/실제 검증(구현 완료 후, 유닛테스트로 대체 불가): ComfyUI 재설치 + SD1.5 모델
  다운로드 + 실제로 폰에서(또는 `/v1/runs` curl로) "그림 그려줘" 요청 → 진짜 PNG 파일이
  생성되는지, `hermes mcp list`에 `comfyui-bridge`가 뜨는지.

## 구현 단계 개요 (계획 문서에서 세분화 예정)

1. `mcp-comfyui-bridge` 프로젝트 스캐폴딩 + `workflow.py`(TDD)
2. `client.py`(TDD, fake HTTP)
3. `server.py` + MCP 등록(TDD)
4. `config.yaml.example`/README 갱신
5. ComfyUI 재설치 + SD1.5 모델 다운로드(실행 인프라 준비)
6. 실측 검증(실제 이미지 생성 확인)
