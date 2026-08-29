# Hermes Agent ↔ Timely AI API 연동 가이드

> 작성일: 2026-08-27
> 대상: Timely AI에서 발급받은 API 키를 Hermes Agent의 모델 백본으로 사용하려는 경우

---

## TL;DR

Timely에는 **두 개의 API 경로**가 있고, Hermes에 붙는 건 두 번째입니다.

| 경로 | 엔드포인트 | OpenAI 호환 | Hermes 연동 |
|---|---|---|---|
| 네이티브 SDK | `https://hello.timelygpt.co.kr/api/v2/chat` | ✗ | 불가 |
| **OpenAI 호환 브릿지** | `https://hello.timelygpt.co.kr/api/v2/chat/bridge/openai` | ✓ | **가능** |

설정 4줄:

```bash
hermes config set model.provider custom
hermes config set model.base_url https://hello.timelygpt.co.kr/api/v2/chat/bridge/openai
hermes config set model.default "anthropic/claude-sonnet-5"   # §4 참고 — haiku는 도구 선택이 불안정했다(실측)
hermes config set model.api_key tgpt_sk_...      # 실측 형식 (72자)
```

> **2026-08-27 실측 정정**: 아래 초안 원래는 `hermes config set OPENAI_API_KEY ...`로 되어 있었는데, 실제로 Hermes Agent v0.20.6 소스(`agent/credential_pool.py`의 `_seed_custom_pool`)를 확인한 결과 `model.provider == "custom"`일 때는 `OPENAI_API_KEY` 환경변수를 전혀 읽지 않고 `config.yaml`의 `model.api_key`(또는 `custom_providers[].api_key`)만 본다. `OPENAI_API_KEY`로 넣으면 `hermes`가 `HTTP 401: 유효하지 않은 API 키입니다`를 낸다 — 반드시 `model.api_key`로 넣을 것. §2 저장 위치 표도 참고.

단, **툴 콜링 통과 여부가 문서화되어 있지 않아** 0단계 검증을 먼저 해야 합니다.

---

## 0. 사전 검증 (가장 먼저)

Hermes는 에이전트입니다. 매 턴 `tools` 배열을 보내고 `tool_calls`를 받아 터미널·파일·웹검색을 실행합니다. 브릿지가 `tools`를 드롭하면 설치를 다 해도 무용지물이므로 이걸 먼저 확인합니다.

```bash
curl -s https://hello.timelygpt.co.kr/api/v2/chat/bridge/openai/chat/completions \
  -H "Authorization: Bearer $TIMELY_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "anthropic/claude-haiku-4.5",
    "messages": [{"role":"user","content":"서울 날씨 알려줘"}],
    "tools": [{
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "도시의 현재 날씨를 조회",
        "parameters": {
          "type": "object",
          "properties": {"city": {"type":"string"}},
          "required": ["city"]
        }
      }
    }]
  }'
```

**판정 기준**

- `choices[0].message.tool_calls`에 `get_weather` 호출이 담겨 나옴 → **진행**
- 툴이 무시되고 텍스트로만 답변 → 브릿지가 `tools`를 드롭. Hermes 백본으로는 사용 불가
- `401` → 키 확인
- `404` → 모델명 확인 (`제공사/모델` 슬러그 형식)

> 이 문서 작성 시점에 Timely 공식 문서(`OPENAI_SDK_GUIDE.md`)에는 chat completions, 스트리밍, 멀티모달, 이미지 생성 예시만 있고 **툴 콜링 예시가 없습니다.** 지원 여부는 실측으로 확인해야 합니다.

---

## 1. 배경 — 왜 브릿지를 써야 하는가

### 네이티브 SDK 경로 (`/api/v2/chat`)

`@timely/gpt-sdk`가 사용하는 자체 규격입니다. OpenAI SDK와 비슷해 보이지만 스펙이 다릅니다.

- 요청에 `session_id`가 **필수**
- 응답이 `{ type: 'final_response' | 'tool_call_required', message, thinking, ... }` 형태
- OpenAI의 `choices[].message` 구조가 아님
- 툴 재요청 시 `checkpoint_id`를 넣고 **이전과 동일한 request**를 다시 보내야 함
- `output_type` / `output_schema` / `rag_storage_ids` 같은 자체 파라미터

Hermes는 표준 `POST /v1/chat/completions`를 기대하므로 이 경로에 base URL을 꽂으면 400 또는 파싱 실패가 납니다.

### OpenAI 호환 브릿지 경로 (`/api/v2/chat/bridge/openai`)

OpenRouter를 Timely 크레딧으로 결제해 쓰는 패스스루입니다. 기존 OpenAI SDK 코드에서 `baseURL`만 바꾸면 동작합니다. 모델명도 OpenRouter 슬러그를 그대로 씁니다.

> 주의: **호환 모드에서 쓸 수 있는 모델 목록과 모델명은 네이티브 SDK와 다릅니다.** 네이티브 SDK 예시에 나오는 `gpt-5.1` 같은 이름을 브릿지에 넣으면 404입니다.

---

## 2. Hermes 설치

### Windows (현재 작업 환경)

```powershell
iex (irm https://hermes-agent.nousresearch.com/install.ps1)
```

> **정정 (2026-08-28):** 이 문서의 이전 판은 "Windows 네이티브 미지원 → WSL2에서 실행"이라고
> 적고 있었는데 **틀렸다**. `install.ps1`은 실재하고(HTTP 200, 239 KB, 헤더가
> `# Hermes Agent Installer for Windows`) 이 PC에서 실제로 네이티브 설치에 성공했다.
> WSL2였다면 `mcp-acad-assist`의 pywin32 COM이 Windows 호스트의 AutoCAD에 닿지 못해
> Stage 3 아키텍처를 갈아야 했다 — 그럴 필요 없다.

- 스크립트가 uv, Python 3.11, git 클론, venv, 전역 `hermes` 명령까지 전부 처리한다.
- 설치 위치·설정 위치는 **`%LOCALAPPDATA%\hermes`** (= `C:\Users\<사용자>\AppData\Local\hermes`).
  유닉스의 `~/.hermes`가 아니다 — 설치 스크립트가 `HERMES_HOME` 사용자 환경변수를 이 값으로 심는다.
  따라서 설정 파일의 실제 경로는 `%LOCALAPPDATA%\hermes\config.yaml`, 환경변수 파일은
  같은 폴더의 `.env`다.
- 대화형 설정 마법사가 설치 직후 자동 실행된다. 비대화형(스크립트/에이전트) 환경에서는
  `-SkipSetup` 플래그로 건너뛰고 §3의 `hermes config set` 4줄로 설정하면 된다.

### Linux / macOS

```bash
curl -fsSLO https://raw.githubusercontent.com/NousResearch/hermes-agent/main/scripts/install.sh
less install.sh          # 내용 확인
bash install.sh
source ~/.bashrc         # 또는 ~/.zshrc
```

- 스크립트가 uv, Python 3.11, Node.js, ripgrep, ffmpeg까지 챙깁니다.
- 설정 위치는 `~/.hermes/`.
- 라이선스 MIT

설치 직후 설정 마법사가 자동 실행됩니다. 언제든 `hermes setup`으로 재실행 가능합니다.

---

## 3. 연동 설정

### 방법 A — 설정 마법사

`hermes setup` 실행 후:

| 항목 | 입력값 |
|---|---|
| 모델 프로바이더 | **커스텀 OpenAI 호환 엔드포인트** (Nous Portal / OpenRouter 아님) |
| Base URL | `https://hello.timelygpt.co.kr/api/v2/chat/bridge/openai` |
| API Key | Timely 키 (**실제 형식 `tgpt_sk_...`, 72자** — 실측 2026-08-28. 문서 원안의 `sdk_live_...`는 옛 표기) |
| 모델명 | `anthropic/claude-sonnet-5` (§4 참고 — haiku는 도구 선택이 불안정했다, 실측) |
| 컨텍스트 길이 | 자동 감지 |
| 최대 반복 횟수 | **15~20** (기본 60에서 낮출 것 — §5 참고) |
| 컨텍스트 압축 임계값 | 0.85 (기본값 유지) |

### 방법 B — CLI

```bash
hermes config set model.provider custom
hermes config set model.base_url https://hello.timelygpt.co.kr/api/v2/chat/bridge/openai
hermes config set model.default "anthropic/claude-sonnet-5"   # §4 참고 — haiku는 도구 선택이 불안정했다(실측)
hermes config set model.api_key sdk_live_...
```

두 방법 모두 결과는 같습니다.

### 저장 위치

| 대상 | 위치 |
|---|---|
| **커스텀 프로바이더(`model.provider: custom`)의 API 키** | `~/.hermes/config.yaml`의 `model.api_key` — **평문 저장**, `.env`가 아님 |
| Anthropic/OpenRouter 등 내장 프로바이더의 API 키 | `~/.hermes/.env` (`ANTHROPIC_API_KEY`, `OPENROUTER_API_KEY` 등) |
| 그 외 모델·엔드포인트 설정 | `~/.hermes/config.yaml` |

> **`model.provider: custom`일 때 `OPENAI_API_KEY`는 읽히지 않습니다** (실측 확인, v0.20.6). Hermes는 `provider` 문자열별로 어느 env var를 볼지 하드코딩된 레지스트리(`PROVIDER_REGISTRY`)를 갖고 있고, `custom`은 이 레지스트리 경로를 안 타고 `config.yaml`의 `model.api_key`(또는 `custom_providers[].api_key`)만 읽습니다. `.env`의 `OPENAI_BASE_URL`과 `LLM_MODEL`도 마찬가지로 더 이상 읽히지 않습니다. `config.yaml`이 모델·엔드포인트 설정의 단일 진실 공급원입니다.
>
> `config.yaml`에 키가 평문으로 저장되므로, 이 파일을 다른 곳에 공유하거나 git에 커밋하지 않도록 주의하세요 (`chmod 600 ~/.hermes/config.yaml` 권장).

### 컨텍스트 하한

커스텀 엔드포인트 모델은 **최소 64,000 토큰 컨텍스트**를 만족해야 Hermes 시작 단계에서 거부되지 않습니다. 아래 권장 모델은 모두 이 조건을 여유롭게 넘깁니다.

---

## 4. 모델 선택

브릿지에서 사용 가능한 모델 중 에이전트 용도로 유효한 것들:

> **2026-08-30 정정 — 아래 표의 "기본" 추천을 뒤집는다.** 이 문서 작성 당시(2026-08-27)
> Haiku를 기본으로 추천했는데, 실제 폰 채널(`api_server`)에서 며칠간 운영해본 결과 스킬/도구
> 선택이 부정확해지는 문제가 반복 실측됐다 — 존재하지 않는 `terminal` 도구를 매번
> `tool_search`로 찾아 헤매거나(스킬 문서가 잘못 안내한 탓도 있었지만, Sonnet은 같은
> 잘못된 안내를 받고도 한두 번 만에 포기하고 넘어간 반면 Haiku는 매번 반복), MEDIA 태그
> 삽입 같은 명시적 지시를 놓치는 등. `model.default`를 `anthropic/claude-sonnet-5`로
> 바꾸자 확연히 안정적이었다. 비용/속도 우선인 별개 워크로드가 아니라면 Haiku를 기본으로
> 잡지 말 것 — 아래 표는 그 정정을 반영했다.

| 용도 | 모델 | 비고 |
|---|---|---|
| **기본 (일상 작업)** | `anthropic/claude-sonnet-5` | 스킬/도구 선택 안정적(실측, 2026-08-30) |
| 어려운 분석·코드 | `anthropic/claude-opus-5` | 비용 높음. 필요할 때만 전환 |
| 비전 (이미지 입력) | `google/gemini-3-flash-preview` | `image_url` 표준 형식 지원 |
| 비용/속도 우선(도구 선택 부정확 감수) | `anthropic/claude-haiku-4.5` | 툴 콜링 자체는 되지만 스킬/도구 **선택**이 부정확해지는 경우가 실측 확인됨 — 에이전트 백본 기본값으로는 비추천 |

(모델 슬러그는 이 브릿지의 `/v1/models`로 그때그때 확인할 것 — 프로바이더 목록은 시간이
지나면 바뀐다. 위 `-5` 계열도 2026-08-30 시점 확인값이다.)

### ⚠️ `openai/*` 계열은 처음에 피하세요

Timely 문서의 모델 목록 주석에 **"cursor 에디터에는 openai 모델이 사용 불가 (openrouter 공급자 버그)"** 라고 적혀 있습니다. Cursor는 툴 콜링을 강하게 쓰는 클라이언트이고, Hermes도 같은 성격입니다. 같은 버그에 걸릴 가능성이 높습니다.

### 세션 중 모델 전환

```
/model custom                                    # 엔드포인트에서 자동 감지
/model custom:anthropic/claude-opus-4.7          # 특정 모델로 전환
/model openrouter:claude-sonnet-4                # 다른 프로바이더로 복귀
```

---

## 5. 크레딧 · Rate Limit 관리

브릿지는 **크레딧 잔액에 따라 제한이 동적으로 조여지는** 구조입니다. 잔액이 줄면 성능도 함께 나빠집니다.

### 분당 요청 제한

| 크레딧 상태 | RPM |
|---|---|
| 충분 | 60 |
| 50,000 미만 | 30 |
| 10,000 미만 | 20 |
| 5,000 미만 | 10 |
| 2 미만 | **차단** |

### 동시 실행 제한

| 크레딧 상태 | 동시 요청 |
|---|---|
| 충분 | 제한 없음 |
| 50,000 미만 | 3 |
| 10,000 미만 | 2 |
| 5,000 미만 | 1 |

### 에이전트에 특히 불리한 이유

Hermes는 한 작업에서 다음과 같이 호출을 소비합니다.

- 기본 최대 **60회 반복** 루프
- 서브에이전트 **병렬** 실행 (각자 자체 대화·터미널 보유)
- 컨텍스트 압축, 메모리 갱신, 스킬 생성에도 별도 호출

즉 "작업 1개 = 호출 1개"가 아닙니다. 수십 회입니다.

### 권장 운영 수칙

1. **최대 반복 횟수를 15~20으로 낮춘다** (기본 60 → 위험)
2. **서브에이전트 병렬 실행을 처음에는 쓰지 않는다**
3. **크론 자동화는 최소 며칠 뒤에** 붙인다 — 손으로 몇 번 돌려 작업당 호출량을 실측한 뒤
4. 429가 뜨면 반복 횟수를 더 낮춘다 (재시도로 해결하려 하면 악화)

---

## 6. 동작 확인

Hermes를 띄우고 툴이 필요한 작업을 시켜봅니다.

```bash
hermes
> 이 디렉토리의 README.md를 읽고 3줄로 요약해줘
```

**판정**

- 도구 호출 로그가 뜨고 실제 파일 내용 기반 요약이 나옴 → 정상
- 도구 호출 없이 "파일을 읽을 수 없습니다" 류의 답변 → 0단계 검증이 통과했더라도 Hermes가 보내는 툴 스키마 형태에서 브릿지가 막히는 것. 모델을 `haiku` ↔ `opus`로 바꿔 재시도

---

## 7. 트러블슈팅

| 상태/증상 | 원인 | 대응 |
|---|---|---|
| `401` | 인증 실패 | 키 오타, `Bearer` 접두어 확인 |
| `402` | 크레딧 부족 | 잔액 충전 |
| `429` | Rate Limit 초과 | 반복 횟수 하향, 병렬 실행 중단 |
| `400` | 잘못된 요청 | 파라미터 확인 |
| `404` | 모델 없음 | 브릿지용 슬러그(`제공사/모델`) 확인 |
| `500` | 서버 에러 | Timely 측 문의 |
| 시작 단계 거부 | 컨텍스트 64K 미만 감지 | 모델명 재확인 |
| 응답은 오는데 품질이 이상함 | base URL 또는 모델명 오류, 실제로는 비호환 | 별도 클라이언트로 엔드포인트 단독 검증 |
| 툴을 아예 호출하지 않음 | 브릿지가 `tools` 드롭 | 모델 변경 후 재시도, 안 되면 백본 용도 포기 |

---

## 8. 알려진 문서 오류 및 주의사항

1. **LangChain 예제의 baseURL이 잘못되어 있습니다.**
   Timely 문서에 `http://localhost:8000/api-ai/v2/bridge/openai`로 적혀 있습니다. 내부 개발 주소가 그대로 남은 것으로 보입니다. 실제로는 `https://hello.timelygpt.co.kr/api/v2/chat/bridge/openai`를 쓰세요.

2. **네이티브 SDK 예제의 모델명을 브릿지에 쓰면 안 됩니다.** (`gpt-5.1` 등)

3. **`.env`에 base URL을 적어도 무시됩니다.** `config.yaml` 사용.

4. **툴 콜링은 공식 문서에 명시되지 않은 미검증 영역입니다.** 이 문서의 §0 검증 결과에 따라 전체 계획이 달라집니다.

---

## 9. 대안 구성 — 툴 콜링이 안 될 경우

브릿지가 `tools`를 통과시키지 않으면 역할을 분리하는 게 맞습니다.

| 역할 | 사용 | 이유 |
|---|---|---|
| 에이전트 백본 | OpenRouter 키 또는 로컬 LLM (vLLM/Ollama) | 툴 콜링 + 긴 컨텍스트 필요 |
| 파싱 / 요약 워커 | Timely 네이티브 SDK | `output_type: 'JSON'` + `output_schema`가 구조화 출력에 최적 |

에이전트 두뇌와 파싱 워커는 애초에 요구사항이 다릅니다. 전자는 툴 콜링과 컨텍스트 길이, 후자는 스키마 준수와 단가입니다. 하나의 키로 둘 다 하려다 양쪽 다 나빠지는 경우가 많습니다.

---

## 참고 링크

- Timely GPT SDK 레포: https://github.com/timely-hub/timely-gpt-sdk
- OpenAI 호환 가이드: https://github.com/timely-hub/timely-gpt-sdk/blob/master/OPENAI_SDK_GUIDE.md
- Timely REST API 문서: https://hello.timelygpt.co.kr/api/v2/chat/sdk
- Hermes Agent 공식: https://hermes-agent.org/
- Hermes Agent 레포: https://github.com/NousResearch/hermes-agent
- OpenRouter 모델 문서 (모델별 사용법 확인용): https://openrouter.ai/

---

## 체크리스트

- [x] §0 툴 콜링 curl 검증 통과 (2026-08-27, `finish_reason: tool_calls`로 `get_weather` 실호출 확인)
- [x] Hermes 설치 및 `hermes` 명령 인식 확인 (v0.20.6, kiki-server)
- [x] `model.provider = custom`, base URL, 모델명, API 키(`model.api_key`) 설정
- [x] **(2026-08-28, Windows)** 같은 4줄로 Windows PC에서도 재현 — `hermes gateway`의
      `/v1/chat/completions`로 산술·파일읽기 툴콜·캘린더 생성까지 실왕복 확인
- [x] 최대 반복 횟수 20으로 하향 (`agent.max_turns`, 이 버전 기본값은 500 — 문서 작성 당시 "기본 60"과 다름)
- [x] `hermes -z`에서 파일 읽기 작업으로 툴 호출 확인 (PLAN.md 실제 요약 성공)
- [x] 작업 1건당 대략적 호출/크레딧 소모량 실측 (2026-08-27, `--usage-file`로 3건 측정)
- [ ] (실측 후) 크론 자동화 적용 — **의도적으로 보류**. §5 원칙("최소 며칠 뒤에, 손으로 몇 번 돌려본 뒤") 그대로 지금은 안 붙임

### 이 서버(kiki-server)에서의 실측 메모

- `model.provider: custom`일 때 Hermes는 `OPENAI_API_KEY` env var를 **읽지 않는다** — `model.api_key`(config.yaml)에 넣어야 함. 위 §2·§3 정정 참고.
- `config.yaml`에 API 키가 **평문 저장**되므로 `chmod 600 ~/.hermes/config.yaml`을 해뒀다 (`.env`도 마찬가지로 600).
- 원샷 실행은 `hermes chat`이 아니라 `hermes -z "프롬프트"`. (`-p`는 없는 플래그.)
- `hermes -z ... --usage-file <path>`로 작업 1건당 정확한 토큰/호출/비용(estimated)을 JSON으로 뽑을 수 있다. 아래는 단순 1~2스텝 작업 3건의 실측치:

  | 작업 | api_calls | total_tokens | 비용(추정) |
  |---|---|---|---|
  | 디렉토리 하위 폴더 나열 (파일시스템 툴) | 3 | 53,605 | $0.0552 |
  | 파일에 텍스트 쓰기 (파일시스템 툴) | 2 | 33,839 | $0.0343 |
  | 웹 검색으로 날씨 조회 (Firecrawl, 키 없이 동작 확인) | 2 | 39,351 | $0.0402 |

  단순 1~2스텝 작업 기준 평균 **호출 ~2.3회, 토큰 ~42K, 비용 ~$0.043/건**. 매 호출마다 시스템 프롬프트+툴 스키마(전체 도구 목록)가 매번 전체 재전송되는 구조라 `input_tokens`가 실제 대화 내용보다 훨씬 크다 — §5의 "작업 1개 = 호출 1개가 아니다"가 실측으로도 확인됨. 여러 툴을 거치는 복잡한 작업(예: CAD 파이프라인 다단계 호출)은 호출 수·토큰이 이보다 몇 배 커질 것으로 예상되므로, 크론으로 자동 반복시키기 전에 실제 반복 작업으로 몇 번 더 재보는 걸 권장.
