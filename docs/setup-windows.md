# Stage 0 — Windows PC에서 Hermes 세우기

> **상태: ✅ 2026-08-28 완료.** 이 문서는 원래 "앞으로 할 일"로 썼고, 실제로 수행한 뒤
> 실측과 어긋난 부분을 정정했다. 정정 근거와 더 자세한 함정 목록은
> [`windows-migration.md` §7](./windows-migration.md)에 있다.
>
> **경로 주의**: Windows의 설정 디렉터리는 `~/.hermes`가 **아니라**
> `%LOCALAPPDATA%\hermes` (= `C:\Users\<사용자>\AppData\Local\hermes`)다.
> 아래에서 `~/.hermes/`로 적힌 곳은 모두 이 경로로 읽을 것.

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

설치 스크립트가 uv/Python 3.11, 리포 클론, 가상환경, 전역 `hermes` 명령까지 전부 처리하고
`HERMES_HOME` 사용자 환경변수를 `%LOCALAPPDATA%\hermes`로 심는다.
완료 후 **새** PowerShell 창을 열어 `hermes --version`으로 확인 (설치 전에 열린 창은 PATH 미반영).

실측 메모 (2026-08-28, v0.20.6):
- 대화형 설정 마법사가 설치 직후 자동 실행된다. TTY가 없는 환경(스크립트/에이전트)에서는
  `-SkipSetup`으로 건너뛰고 §2를 `hermes config set`으로 처리할 것.
- 설치 중 **browser tools npm / TUI npm / computer-use 드라이버** 3개가 실패하는데,
  api_server(폰 앱) 용도로는 전부 불필요하다 — browser 툴셋은 §3에서 어차피 끈다.

## 2. LLM 프로바이더 + 웹검색 + 이미지 생성

> **정정**: 원안은 대화형 마법사였지만, 실제로는 마법사 대신 `hermes config set`으로
> 직접 처리하는 게 안정적이다(TTY 없는 환경/스크립트에서도 동일하게 재현 가능).
> 어떤 LLM 키를 쓸지는 환경마다 다르다 — 아래 두 경로 중 그 환경에서 실제로 쓸 키에 맞는
> 쪽을 고른다. 특정 프로바이더에 묶인 요구사항이 아니다.

**표준 경로 (Anthropic 직결 / OpenRouter)**: [`hermes-credentials.md` §1](./hermes-credentials.md) 참고.

**커스텀 OpenAI 호환 브릿지**(사내/서드파티 게이트웨이 등 — 이 리포는 지금까지 Timely AI를
써왔지만, 다른 환경에서는 다른 브릿지/키를 쓸 수 있다. 아래는 그 형태의 공통 골격이고,
Timely 고유의 값(base_url, 키 형식 등)은 [`timely_ai_api.md`](./timely_ai_api.md)에 분리해뒀다):

```powershell
hermes config set model.provider custom
hermes config set model.base_url <그 브릿지의 OpenAI 호환 엔드포인트>
hermes config set model.default <그 브릿지에서 실제로 쓸 모델 ID>   # 그 프로바이더의 /v1/models로 먼저 확인할 것
hermes config set model.api_key <그 브릿지의 키>
hermes config set agent.max_turns 20      # 기본 500 -- 크레딧 소모 방어
```

- **`OPENAI_API_KEY` 환경변수는 읽히지 않는다** (실측 v0.20.6, 커스텀 브릿지 공통). `model.api_key`여야 한다.
- 이 키는 `config.yaml`에 **평문 저장**된다(어느 브릿지를 쓰든 공통) — 파일 ACL을 사용자
  전용으로 잠글 것:
  ```powershell
  icacls "$env:LOCALAPPDATA\hermes\config.yaml" /inheritance:r /grant:r "$($env:USERNAME):F"
  ```
- Timely AI를 그대로 쓸 경우의 정확한 `base_url`·키 형식(`tgpt_sk_...`)·트러블슈팅은
  [`timely_ai_api.md`](./timely_ai_api.md) 참고.

웹 검색 백엔드(Firecrawl/Tavily/Brave 등)와 이미지 생성 키가 필요하면 `hermes setup` 또는
`hermes config set`으로 추가 — Hermes 내장 기능이라 별도 MCP는 필요 없다 (PLAN.md 참고).
Stage 0 검증에는 없어도 된다.

**어떤 키를 어디에 넣는지, 정확한 환경변수 이름과 함정(Anthropic OAuth의 Max+크레딧 조건,
이미지 생성은 키만으론 안 되고 `hermes tools`로 provider 선택 필요 등)은
[`docs/hermes-credentials.md`](./hermes-credentials.md)에 따로 정리했다.**

`hermes chat`으로 간단히 대화가 되는지 먼저 확인한 뒤 다음 단계로 넘어간다 — 여기서 안 되면
게이트웨이/앱을 붙여도 의미가 없다.

## 3. API 서버 활성화

`%LOCALAPPDATA%\hermes\.env`에 이 리포의 `hermes-config/env.example`을 참고해 값을 채운다.
최소한 다음 네 줄이 필요하다:

```bash
API_SERVER_ENABLED=true
API_SERVER_KEY=<32자 이상 랜덤 문자열>
API_SERVER_HOST=<이 PC의 LAN IP>   # 기본값 127.0.0.1(로컬만) -- 폰에서 붙으려면 변경 필요
API_SERVER_PORT=8642
```

> **`0.0.0.0` 대신 LAN IP를 쓸 것.** 이 PC는 NIC가 두 개이고 그중 하나(`이더넷`)가
> **공인 IP를 직접 들고 있다**(NAT 없음). `0.0.0.0`으로 바인딩하면 그 NIC에서도 리스닝하므로
> Bearer 키 하나만 뚫리면 파일·메모리·웹 도구를 가진 에이전트가 인터넷에 노출된다.
> 폰이 붙는 NIC의 주소만 지정하면 나머지 NIC는 리스닝조차 하지 않는다.
> 이 PC의 실제 값: `API_SERVER_HOST=172.30.1.101` (Wi-Fi, 고정 IP). 근거는
> `windows-migration.md` §7.9.

**API 서버는 `API_SERVER_KEY`가 충분히 길 때만 켜진다** — `API_SERVER_ENABLED=true`만
있으면 활성화되지 않는다 (`gateway/config.py`의 `_has_usable_api_server_key`).

`API_SERVER_KEY`는 PowerShell에서 간단히 생성:

```powershell
-join ((48..57)+(65..90)+(97..122)|Get-Random -Count 40|%{[char]$_})
```

**툴셋 트리밍 — 반드시 할 것.** 기본 상태는 `terminal`/`code_execution`/`browser`까지 켜져
있는데, 폰 앱 용도로 전부 불필요하고 매 호출마다 전체 스키마가 전송돼 토큰·지연시간만 늘린다.
인터넷에 노출되는 엔드포인트에 `terminal`을 켜두는 것 자체가 위험하다:

```powershell
hermes tools disable terminal code_execution delegation cronjob image_gen vision session_search browser --platform api_server
```

→ `hermes tools list --platform api_server`가 `web, file, skills, todo, memory`만 남기면 성공.
(토큰 실측: 이 상태에서 캘린더 MCP 등록 전 prompt 9.5K, 등록 후 34~39K.)

게이트웨이 기동:

```powershell
hermes gateway
```

`API server listening on http://<지정한 LAN IP>:8642`가 뜨면 성공.
`Get-NetTCPConnection -LocalPort 8642 -State Listen`으로 **의도한 NIC에만** 붙었는지 확인할 것.

기동 로그의 Windows 특이 경고 2개는 비치명적이다 —
`asyncio has no attribute 'start_unix_server'`, SQLite WAL 폴백. 상세는
`windows-migration.md` §7.8.

## 4. google-calendar-mcp 등록 + OAuth

1. [Google Cloud Console](https://console.cloud.google.com)에서 프로젝트 생성 → Calendar API 활성화
2. Credentials → OAuth client ID → 애플리케이션 유형 **Desktop app**으로 생성 → JSON 다운로드
   (`gcp-oauth.keys.json`)
3. OAuth consent screen → Audience에 본인 이메일을 테스트 사용자로 추가 (전파에 몇 분 걸림)
4. `hermes-config/config.yaml.example`의 `mcp_servers.calendar` 블록을
   `%LOCALAPPDATA%\hermes\config.yaml`에 병합하고, `GOOGLE_OAUTH_CREDENTIALS` 경로를 실제
   경로로 수정. **`command`는 `npx`가 아니라 `npx.cmd`여야 한다** — Hermes는 MCP stdio 서버를
   셸 없이 subprocess로 띄우고 Windows subprocess는 PATHEXT를 적용하지 않는다
   (`npx` → `FileNotFoundError [WinError 2]`). `args`에 `-y`도 넣어 TTY 없는 게이트웨이에서
   설치 프롬프트로 멈추지 않게 한다. 상세는 `windows-migration.md` §7.4.

   `hermes mcp add`는 쓰지 말 것 — 도구 발견까지는 되지만
   `Enable all 13 tools? [Y/n/select]:` 프롬프트에서 EOF를 만나 아무것도 쓰지 않고 취소된다 (§7.5).
5. 게이트웨이 재시작 후 `hermes mcp list`로 `✓ enabled` 확인
6. **OAuth 인증은 CLI로 별도 수행한다.** `config.yaml.example`이 `manage-accounts`를
   `tools.exclude`로 빼놨는데 그게 바로 인증 담당 도구라, Hermes에게 *"Google Calendar로
   인증해줘"* 라고 요청하는 경로는 이 필터 상태에서 동작하지 않는다 (§7.6):

   ```powershell
   $env:GOOGLE_OAUTH_CREDENTIALS = "C:\hermes-projects\secrets\gcp-oauth.keys.json"
   npx.cmd -y "@cocal/google-calendar-mcp" auth
   ```

   브라우저가 자동으로 열리고(리다이렉트 `http://localhost:3500/oauth2callback`) 동의하면
   `Authentication completed successfully!`가 뜬다. 이 단계 없이는 캘린더 도구 호출이
   `-32600` 에러로 실패한다.

   **토큰 저장 위치는 `HERMES_HOME` 밖이다**:
   `C:\Users\<사용자>\.config\google-calendar-mcp\tokens.json` — 이 파일도 ACL을 잠글 것.

**주의**: 테스트 모드에서는 토큰이 7일마다 만료된다. 매주 재인증하고 싶지 않으면 OAuth consent
screen에서 "PUBLISH APP"으로 전환한다(대신 Google이 "확인되지 않은 앱" 경고를 띄운다).

## 5. Windows 방화벽 인바운드 허용

> **이 절의 원안(`-Profile Private` 룰)은 이 PC에서 정반대로 동작한다.** 근거와 전체 분석은
> `windows-migration.md` §7.9. 요약:
>
> | 인터페이스 | IP | 프로필 |
> |---|---|---|
> | `이더넷` | 121.147.94.22/24 (**공인 IP, NAT 없음**) | **Private** |
> | `Wi-Fi` | 172.30.1.101/24 (고정, 폰이 붙는 망) | **Public** |
>
> `-Profile Private`는 공인 IP 쪽을 열고 정작 폰이 붙는 Wi-Fi는 막는다. 게다가 게이트웨이가
> 실행되는 인터프리터(`WindowsApps\...\python3.11.exe`)에 대한 인바운드 룰이 이미 4개
> 존재한다 — Public **Block** ×2, Private Allow ×2. Windows Firewall은 Block이 Allow를
> 이기므로 Public 프로필에 Allow 룰을 새로 넣어도 무효다.

**채택한 구성 (2겹)**

1. `API_SERVER_HOST`를 폰이 붙는 NIC의 주소로 (§3). 공인 IP NIC는 리스닝 자체를 안 한다.
2. 관리자 PowerShell에서 Public Block 룰을 **비활성화**하고, 8642만 주소 스코프로 Allow.
   재현 스크립트: `C:\hermes-projects\firewall-setup.ps1`

   ```powershell
   New-NetFirewallRule -DisplayName "Hermes API Server (LAN only)" `
     -Direction Inbound -Protocol TCP -LocalPort 8642 -Action Allow `
     -Profile Any -LocalAddress 172.30.1.101 -RemoteAddress 172.30.1.0/24
   ```

기본 인바운드 정책이 3프로필 전부 `BlockInbound`이므로 Block 룰을 껴도 새로 열리는 것은 없다.
**`-RemoteAddress LocalSubnet`은 쓰지 말 것** — 이더넷 쪽 `121.147.94.0/24`(같은 대역의 다른
가입자)까지 허용된다.

`.ps1`을 새로 쓸 때는 **ASCII 전용으로 작성**할 것 — Windows PowerShell 5.1은 BOM 없는 `.ps1`을
cp949로 읽어서 한글 주석·메시지가 있으면 문자열 종결자가 깨지고 파싱이 실패한다.

`Set-NetConnectionProfile`로 Wi-Fi를 Private로 바꾸는 대안도 있지만, 그러면 기존 Private Allow
룰(`LocalPort: Any`/`RemoteIP: Any`)에 얹히게 되어 훨씬 넓게 열린다 — 위 방식이 더 좁다.

Stage 7에서 Cloudflare named tunnel로 넘어가면 이 인바운드 규칙 자체가 필요 없어진다.

## 6. 게이트웨이 상시 기동

```powershell
hermes gateway install
```

Windows에서는 작업 스케줄러를 먼저 시도하고, UAC 승인이 없으면 시작 폴더
(`...\Startup\Hermes_Gateway.vbs`)로 폴백한다. `hermes gateway status` / `restart` / `stop`으로
관리한다. 더 견고하게 가려면 **승격된** 창에서 다시 실행해 작업 스케줄러로 올릴 것 (§7.7).

## 검증 (PLAN.md Stage 0 그대로)

PC 터미널에서:

```powershell
$k = "<API_SERVER_KEY>"
curl.exe -H "Authorization: Bearer $k" http://172.30.1.101:8642/health

# 한글 본문은 인코딩 사고가 잦다. JSON 을 UTF-8 파일로 저장해서 보내거나,
# Invoke-WebRequest 에 [Text.Encoding]::UTF8.GetBytes($body) 를 넘길 것.
curl.exe -X POST http://172.30.1.101:8642/v1/chat/completions `
  -H "Authorization: Bearer $k" -H "Content-Type: application/json" `
  --data-binary "@request.json"
```

(PowerShell 에서는 `curl` 이 `Invoke-WebRequest` alias 라 `curl.exe` 로 부를 것.)

→ 구글 캘린더 웹에 이벤트가 실제로 보이면 성공. 폰 브라우저에서 `http://<LAN IP>:8642/health`가
열리는지도 함께 확인한다 (Stage 1 폰 앱의 설정 화면이 같은 엔드포인트를 친다).

**실측 결과 (2026-08-28, 전부 통과)**

| 검증 | 결과 |
|---|---|
| `/health` | 200 `{"status":"ok","platform":"hermes-agent","version":"0.20.6"}` |
| 키 없는 `/v1/chat/completions` | 401 거부 |
| 모델 왕복 (산술) | 정답, 23초, prompt 9.5K (캘린더 MCP 등록 전) |
| 툴 호출 (파일 읽기) | `PLAN.md` 첫 줄 정확히 반환, 8초 |
| **캘린더 생성** | *"내일 오후 3시 치과 예약 캘린더에 넣어줘"* → 실제 이벤트 생성, 28초 |
| **독립 확인** | 별도 요청으로 조회 → Event ID `a590lvk7mthmerbqr53u2s5sd8`, 2026-08-29 15:00–16:00 KST |
| 폰 도달 | 폰 브라우저에서 `http://172.30.1.101:8642/health` 200 |

## 이 리포에서 참고할 파일

- `hermes-config/env.example` → `%LOCALAPPDATA%\hermes\.env`
- `hermes-config/config.yaml.example` → `%LOCALAPPDATA%\hermes\config.yaml` (mcp_servers 블록)
- `C:\hermes-projects\firewall-setup.ps1` — §5 방화벽 설정 재현 스크립트
- CAD 3종(AutoCAD/SketchUp/3ds Max) MCP 설치는 Stage 3~5에서 별도 문서화 — 지금은 calendar만
