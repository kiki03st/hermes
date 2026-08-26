# Stage 0 — Windows PC에서 Hermes 세우기

앱 코드는 없다. 사용자가 Windows 10 Pro PC에서 직접 수행하는 수동 설치 단계다.
여기서 막히면 이후 모든 단계(폰/워치/CAD 파이프라인)가 의미 없으므로 가장 먼저 끝낸다.

모든 명령·설정 키는 hermes-agent 공식 문서로 확인한 것이다:
[Installation](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/getting-started/installation.md) ·
[API Server](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/api-server.md) ·
[MCP](https://github.com/NousResearch/hermes-agent/blob/main/website/docs/user-guide/features/mcp.md)

## 1. Hermes 설치

PowerShell에서:

```powershell
iex (irm https://hermes-agent.nousresearch.com/install.ps1)
```

설치 스크립트가 Python/Node.js/ripgrep/ffmpeg 등 의존성, 리포 클론, 가상환경, 전역 `hermes`
명령까지 전부 처리한다. 완료 후 새 PowerShell 창을 열어 `hermes --version`으로 확인.

## 2. LLM 프로바이더 + 웹검색 + 이미지 생성

```powershell
hermes setup
```

대화형 마법사에서 Anthropic 또는 OpenRouter 중 하나를 선택해 API 키를 등록한다.
웹 검색 백엔드(Firecrawl/Tavily/Brave 등)와 이미지 생성 키도 같은 흐름에서 설정 — Hermes
내장 기능이라 별도 MCP는 필요 없다 (PLAN.md 참고).

**어떤 키를 어디에 넣는지, 정확한 환경변수 이름과 함정(Anthropic OAuth의 Max+크레딧 조건,
이미지 생성은 키만으론 안 되고 `hermes tools`로 provider 선택 필요 등)은
[`docs/hermes-credentials.md`](./hermes-credentials.md)에 따로 정리했다.**

`hermes chat`으로 간단히 대화가 되는지 먼저 확인한 뒤 다음 단계로 넘어간다 — 여기서 안 되면
게이트웨이/앱을 붙여도 의미가 없다.

## 3. API 서버 활성화

`~/.hermes/.env`에 이 리포의 `hermes-config/env.example`을 참고해 값을 채운다. 최소한
다음 세 줄이 필요하다:

```bash
API_SERVER_ENABLED=true
API_SERVER_KEY=<32자 이상 랜덤 문자열>
API_SERVER_HOST=0.0.0.0   # 기본값은 127.0.0.1(로컬만) — 폰에서 붙으려면 반드시 변경
```

`API_SERVER_KEY`는 PowerShell에서 간단히 생성:

```powershell
-join ((48..57)+(65..90)+(97..122)|Get-Random -Count 40|%{[char]$_})
```

게이트웨이 기동:

```powershell
hermes gateway
```

`[API Server] API server listening on http://127.0.0.1:8642` (또는 `0.0.0.0:8642`)가 뜨면 성공.

## 4. google-calendar-mcp 등록 + OAuth

1. [Google Cloud Console](https://console.cloud.google.com)에서 프로젝트 생성 → Calendar API 활성화
2. Credentials → OAuth client ID → 애플리케이션 유형 **Desktop app**으로 생성 → JSON 다운로드
   (`gcp-oauth.keys.json`)
3. OAuth consent screen → Audience에 본인 이메일을 테스트 사용자로 추가 (전파에 몇 분 걸림)
4. `hermes-config/config.yaml.example`의 `mcp_servers.calendar` 블록을
   `~/.hermes/config.yaml`에 병합하고, `GOOGLE_OAUTH_CREDENTIALS` 경로를 위 JSON 실제 경로로 수정
5. `hermes-config/env.example`의 `GOOGLE_OAUTH_CREDENTIALS`도 동일하게 채워 `.env`에 반영
6. Hermes를 통해 **"Google Calendar로 인증해줘"** 라고 한 번 요청 — 브라우저가 뜨고 OAuth 동의
   화면을 통과하면 완료. 이 단계 없이는 캘린더 도구 호출이 `-32600` 에러로 실패한다.

**주의**: 테스트 모드에서는 토큰이 7일마다 만료된다. 매주 재인증하고 싶지 않으면 OAuth consent
screen에서 "PUBLISH APP"으로 전환한다(대신 Google이 "확인되지 않은 앱" 경고를 띄운다).

## 5. Windows 방화벽 인바운드 허용

PowerShell(관리자):

```powershell
New-NetFirewallRule -DisplayName "Hermes API Server" -Direction Inbound -Protocol TCP -LocalPort 8642 -Action Allow -Profile Private
```

`-Profile Private`로 사설망(같은 Wi-Fi)에서만 열되, 공용망(카페 Wi-Fi 등)에서는 막아둔다.
Stage 7에서 Cloudflare Tunnel로 외부 노출을 대체하기 전까지는 이 상태를 유지한다.

## 검증 (PLAN.md Stage 0 그대로)

PC 터미널에서:

```bash
curl -H "Authorization: Bearer $API_SERVER_KEY" http://<LAN IP>:8642/health

curl -X POST http://<LAN IP>:8642/v1/chat/completions \
  -H "Authorization: Bearer $API_SERVER_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"hermes-agent","messages":[{"role":"user","content":"내일 오후 3시 치과 예약 캘린더에 넣어줘"}]}'
```

→ 구글 캘린더 웹에 이벤트가 실제로 보이면 성공. 폰 브라우저에서 `http://<LAN IP>:8642/health`가
열리는지도 함께 확인한다 (Stage 1 폰 앱의 설정 화면이 같은 엔드포인트를 친다).

## 이 리포에서 참고할 파일

- `hermes-config/env.example` → `~/.hermes/.env`
- `hermes-config/config.yaml.example` → `~/.hermes/config.yaml` (mcp_servers 블록)
- CAD 3종(AutoCAD/SketchUp/3ds Max) MCP 설치는 Stage 3~5에서 별도 문서화 — 지금은 calendar만
