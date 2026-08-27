# Hermes 실행에 필요한 키/입력값 — 어디에 무엇을 넣나

Hermes 게이트웨이를 실제로 띄우고 테스트하려면 최소 "LLM 프로바이더 1개"와 "API 서버 키
(직접 생성)"만 있으면 된다. 나머지(웹검색·이미지생성·캘린더)는 기능별로 선택이다. 전부
hermes-agent 공식 문서(`getting-started/quickstart.md`, `integrations/providers.md`,
`user-guide/features/{api-server,web-search,image-generation}.md`)를 직접 확인해서 정리했다.

## 저장 위치 원칙

Hermes는 시크릿과 일반 설정을 파일로 분리한다.

| 종류 | 파일 |
|---|---|
| API 키·토큰 (시크릿) | `~/.hermes/.env` |
| 모델 선택·도구 설정 등 (비시크릿) | `~/.hermes/config.yaml` |

**직접 파일을 열어 편집해도 되지만, 아래 CLI를 쓰면 어느 파일에 넣을지 Hermes가 알아서 정확히
분류해서 써준다 — 오타로 잘못된 파일에 넣는 실수를 줄인다:**

```bash
hermes config set OPENROUTER_API_KEY sk-or-...   # 시크릿 → .env로
hermes config set model anthropic/claude-opus-4.6 # 설정 → config.yaml로
hermes model    # 프로바이더/모델 대화형 마법사 — 이걸로 시작하는 게 제일 안전
hermes tools    # 웹검색/이미지생성 등 도구 프로바이더 선택
```

---

## 1. 필수 — LLM 프로바이더 (하나는 반드시 있어야 함)

PLAN.md는 "Anthropic 또는 OpenRouter 중 택1"이라고 했는데, Anthropic 쪽에 **billing 함정**이
하나 있어서 그대로 옮긴다.

### Anthropic — 두 가지 인증 경로, 조건이 다르다

| 경로 | 조건 | 명령 |
|---|---|---|
| OAuth (Claude 구독 재사용) | **Claude Max 플랜 + 추가 사용량 크레딧 구매**가 있어야 동작. **Claude Pro는 이 경로 사용 불가** — Pro인데 OAuth를 시도하면 그냥 안 된다 | `hermes model` → Anthropic OAuth |
| API 키 (종량제) | Claude 구독과 무관하게 정상 과금. Pro 사용자는 사실상 이 경로만 가능 | `~/.hermes/.env`에 `ANTHROPIC_API_KEY=sk-ant-...` |

키 발급: https://console.anthropic.com → API Keys

**주의**: Max+추가크레딧 없이 OAuth를 걸면 "됐다"고 나와도 실제 호출에서 막힐 수 있다. Pro
플랜이면 처음부터 `ANTHROPIC_API_KEY`로 가는 게 맞다.

### OpenRouter — 더 단순한 대안

```bash
# ~/.hermes/.env
OPENROUTER_API_KEY=sk-or-...
```

키 발급: https://openrouter.ai/keys. OpenRouter 하나로 여러 모델 프로바이더에 접근하므로,
직접 프로바이더 키를 여러 개 관리하고 싶지 않으면 이쪽이 더 쉽다.

### 확인

```bash
hermes model     # 지금 선택된 프로바이더/모델 확인, 변경도 여기서
hermes chat      # 아무 말이나 걸어서 응답이 오는지 확인 — 여기서 안 되면 다음 단계로 못 감
```

---

## 2. 필수 — API 서버 키 (직접 생성, 발급받는 게 아님)

폰 앱이 붙는 `POST /v1/chat/completions` 등을 열려면 `~/.hermes/.env`에 3줄이 필요하다
(`hermes-config/env.example` 참고):

```bash
API_SERVER_ENABLED=true
API_SERVER_KEY=<32자 이상 랜덤 문자열 — 아무 사이트에서 발급받는 게 아니라 직접 생성>
API_SERVER_HOST=0.0.0.0   # 기본값 127.0.0.1은 로컬만 — 폰에서 붙으려면 반드시 변경
```

`API_SERVER_KEY` 생성 예시 (PowerShell):

```powershell
-join ((48..57)+(65..90)+(97..122)|Get-Random -Count 40|%{[char]$_})
```

이 키는 폰 앱 설정 화면(`android/app`)의 "API 키" 입력란에 **똑같이** 넣어야 한다 — 서버와
클라이언트가 같은 문자열을 Bearer 토큰으로 쓴다.

### 확인

```bash
hermes gateway
# 다른 터미널에서
curl -H "Authorization: Bearer $API_SERVER_KEY" http://127.0.0.1:8642/health
```

---

## 3. 선택 (강력 권장) — 웹 검색 백엔드

Hermes 내장 기능이라 MCP를 따로 만들 필요는 없지만(PLAN.md 확인됨), 키가 없으면 웹검색 도구
자체가 안 뜬다. 아무거나 하나만 있으면 된다 — **키 이름이 서로 미묘하게 다르니 정확히 맞출 것**:

| 프로바이더 | 환경변수 (정확한 이름) | 무료 티어 | 발급 |
|---|---|---|---|
| Firecrawl (기본값) | `FIRECRAWL_API_KEY` | 월 500 크레딧, 키 없이도 일부 동작 | https://firecrawl.dev |
| Brave Search | `BRAVE_SEARCH_API_KEY` (⚠️ `BRAVE_API_KEY` 아님) | 월 2,000쿼리 | https://brave.com/search/api |
| Tavily | `TAVILY_API_KEY` | 월 1,000회 | https://tavily.com |
| Exa | `EXA_API_KEY` | 키 있으면 월 1,000회 | https://exa.ai |
| Parallel | `PARALLEL_API_KEY` | 키 있으면 유료 | https://parallel.ai |

```bash
# ~/.hermes/.env — 하나만 넣으면 됨
TAVILY_API_KEY=tvly-...
```

**한 번도 웹 백엔드를 선택한 적이 없으면** 위 키 중 있는 것을 자동 감지해서 쓰지만, **한 번이라도
`hermes tools`로 명시적으로 고른 뒤에는 키를 추가해도 자동으로 안 바뀐다** — 바꾸려면 다시
`hermes tools`로 들어가야 한다.

---

## 4. 선택 — 이미지 생성

이것도 Hermes 내장이지만(PLAN.md 확인됨) **env에 키만 넣는다고 되는 게 아니라 프로바이더를
명시적으로 선택해야 동작한다** — 이게 웹검색과 다른 점이라 따로 적는다.

```bash
hermes tools   # image_gen.provider를 fal / openai / xai / krea / nous 중에서 선택
```

| 프로바이더 | 필요한 키 |
|---|---|
| FAL (기본값 후보) | `FAL_KEY` (https://fal.ai) |
| OpenAI | `OPENAI_API_KEY` |
| xAI | OAuth 또는 `XAI_API_KEY` |
| Krea | `KREA_API_KEY` |
| Nous Portal | 없음 (구독으로 커버) |

`FAL_KEY`를 `.env`에 넣어도 `image_gen.provider`가 다른 값으로 저장돼 있으면 무시된다 —
안 되면 먼저 `hermes tools`에서 provider가 뭐로 돼 있는지부터 확인할 것.

---

## 5. Stage 0 필수 — Google Calendar (MVP 합격선)

PLAN.md의 MVP 합격선("워치에 말하면 구글 캘린더에 일정이 들어간다")에 필요. 상세 절차는
`docs/setup-windows.md` 4번 섹션에 이미 정리돼 있고, 여기서는 입력값만 요약:

| 입력값 | 어디서 발급 | 어디에 입력 |
|---|---|---|
| OAuth 클라이언트 JSON (`gcp-oauth.keys.json`) | Google Cloud Console → OAuth client ID → **Desktop app** 타입 | 파일로 저장, 경로를 아래 두 곳에 |
| `GOOGLE_OAUTH_CREDENTIALS` | (위 JSON 파일 경로) | `~/.hermes/.env` |
| 같은 경로 | — | `~/.hermes/config.yaml`의 `mcp_servers.calendar.env.GOOGLE_OAUTH_CREDENTIALS` |

등록 후 **"Google Calendar로 인증해줘"** 라고 한 번 말해서 브라우저 OAuth를 완료해야 실제로
작동한다 — 이 스텝을 빼먹으면 캘린더 도구 호출이 `-32600` 에러로 실패한다 (google-calendar-mcp
README에 명시된 알려진 증상).

**2026-08-28 실측 (kiki-server, 헤드리스 Ubuntu)**: OAuth 클라이언트의 `client_secret`은
**생성 시점에만** Google Cloud Console에서 다운로드 가능하다 — 기존에 만들어둔 클라이언트는
나중에 재다운로드가 안 되므로(Google 정책), 없으면 새로 Desktop app 타입 클라이언트를 만들거나
"Reset secret"으로 재발급받아야 한다. `gcp-oauth.keys.json`은 최상위 키가 `"installed"`여야
하고(`"web"`이면 잘못된 타입), `client_id`는 `.apps.googleusercontent.com`, `client_secret`은
보통 `GOCSPX-`로 시작한다.

**브라우저가 없는 헤드리스 서버에서 OAuth 완료하는 법** (Windows PC에는 해당 없음 — 실제
브라우저가 있으므로 이 우회가 필요 없다. 이 서버에서 Hermes를 테스트할 때만 참고):

1. `GOOGLE_OAUTH_CREDENTIALS=<경로> npx -y @cocal/google-calendar-mcp auth` 를 서버에서 실행
   → `🔗 Authentication URL: https://accounts.google.com/...&redirect_uri=http://localhost:3500/oauth2callback`
   형태의 URL을 출력하고, 로컬 3500 포트에서 콜백을 기다리며 대기한다.
2. 다른 터미널에서 **SSH 포트포워딩을 추가해** 같은 서버에 재접속:
   `ssh -L 3500:localhost:3500 <평소 접속 명령 그대로>` (기존 SSH 접속 포트 옵션과는 독립적 —
   `-p`로 지정하는 SSH 접속 포트와 `-L`의 포워딩 포트는 별개다)
3. 터널이 연결된 채로 1번에서 받은 긴 `accounts.google.com` URL을 **본인 컴퓨터 브라우저**에서
   열고 로그인·동의 완료 → 마지막 리다이렉트(`localhost:3500/...`)가 터널을 타고 서버의 인증
   서버로 전달되며 완료된다. 터널 없이 URL만 열면 마지막 리다이렉트 단계에서 실패한다.
4. 성공 시 `Tokens saved successfully ... Authentication completed successfully!`가 뜨고
   `~/.config/google-calendar-mcp/tokens.json`이 생성된다.
5. `hermes -z "내일 오후 3시에 치과 예약 일정을 구글 캘린더에 30분짜리로 등록해줘."`로
   실제 이벤트 생성까지 end-to-end 검증 완료 (PLAN.md MVP 합격선).

---

## 6. 아직 필요 없음 — CAD 3종 (Stage 3~5)

AutoCAD(`acad2d`/`acad-assist`)·SketchUp(`sketchup`)·3ds Max(`max3d`) MCP는 **API 키가 없다** —
전부 로컬 COM/TCP/네이티브 브리지라 인증 자체가 없는 구조. Stage 0 테스트에는 등록할 필요조차
없다. `hermes-config/config.yaml.example`에 이미 넣어놨더라도 해당 앱(AutoCAD 등)이 안 떠 있으면
Hermes가 해당 MCP만 연결 실패로 건너뛴다.

---

## 전체 체크리스트 (Stage 0 테스트 기준)

| # | 항목 | 필수 여부 | 어디에 |
|---|---|---|---|
| 1 | LLM 프로바이더 키 (Anthropic 또는 OpenRouter) | **필수** | `.env` |
| 2 | `API_SERVER_ENABLED`/`KEY`/`HOST` | **필수** | `.env` |
| 3 | 웹 검색 키 (아무거나 1개) | 권장 | `.env` |
| 4 | Google OAuth 클라이언트 JSON + 경로 | MVP 필수 | `.env` + `config.yaml` |
| 5 | 이미지 생성 키 + provider 선택 | 선택 | `.env` + `hermes tools` |
| 6 | CAD 3종 키 | 불필요 | — |

모두 넣은 뒤 최종 검증:

```bash
hermes gateway
curl -H "Authorization: Bearer $API_SERVER_KEY" http://127.0.0.1:8642/health
curl -X POST http://127.0.0.1:8642/v1/chat/completions \
  -H "Authorization: Bearer $API_SERVER_KEY" -H "Content-Type: application/json" \
  -d '{"model":"hermes-agent","messages":[{"role":"user","content":"내일 오후 3시 치과 예약 캘린더에 넣어줘"}]}'
```

구글 캘린더 웹에 이벤트가 실제로 뜨면 Stage 0 + MVP 합격선 완료.
