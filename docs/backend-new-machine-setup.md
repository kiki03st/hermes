# 백엔드를 새 컴퓨터로 옮기기 (git clone부터 전체 재구성까지)

> 이 문서는 "이 리포를 GitHub에서 다른 Windows PC로 `git clone`한 뒤, 그 PC를 Hermes
> 백엔드로 온전히 작동시키려면 뭘 해야 하는가"에 대한 **하나로 모은 실행 순서**다.
> 세부 근거·트러블슈팅·실측 기록은 각 단계에서 기존 문서를 링크로 가리킨다 —
> 여기서 전부 다시 설명하면 두 문서가 따로 낡아간다.

## 백엔드는 서로 독립된 3개 조각이다

| # | 이름 | 이 리포 안? | 용도 |
|---|---|---|---|
| 1 | `hermes-agent` (게이트웨이) | **아니오** — 공식 설치 스크립트로 별도 설치 | 실제 에이전트, `/v1/runs` 등 API 서버 |
| 2 | `upload-server` | **예** — `git clone`으로 같이 옴 | 폰이 올린 파일/이미지를 게이트웨이가 읽을 디스크로 릴레이 |
| 3 | 이미지 생성 (`image_gen.provider: fal`) | **아니오** — `config.yaml`/env 파일 설정만, 별도 설치 없음 | 폰의 "그림 그려줘" 요청을 실제 이미지 파일로 만드는 백엔드 |

세 개 다 옮겨야 폰 앱이 지금 PC에서 하던 걸 새 PC에서도 그대로 할 수 있다. 하나라도
빠지면 그 기능만 조용히 안 됨(예: 이미지 생성 설정 없으면 "그림 그려줘"에 텍스트 아트로
답하거나, 코드 실행 도구를 찾아 헤매다 아무 결과 없이 여러 턴을 낭비함 — 2026-08-29 실측,
아래 §3 참고. 1(a)+2 만 있으면 CAD/캘린더 등 MCP는 다시 등록해야 됨 — CAD는 아직 Stage 3
미완이라 이 문서 범위 밖).

> **ComfyUI는 쓰지 않는다** — 2026-08-29에 실제로 설치해서 테스트해본 결과, ComfyUI는
> `comfy` CLI를 **터미널에서 실행**해야 동작하는데 폰이 쓰는 `api_server` 플랫폼엔
> `terminal`/`code_execution` 툴셋 자체가 없다(§1.6 참고, CLI 세션 전용). 그래서
> 에이전트가 스킬만 찾아보다가 이미지를 못 만들고 계속 다른 방법으로 우회 시도만
> 반복했다(30턴 넘게, 실측). 폰에서 실제로 되는 유일한 경로는 아래 §3의 내장
> `image_gen` provider 방식이다 — GPU도 필요 없다.

## 사전 준비물 (새 PC)

- Windows 10/11
- `git`
- Python 3.10+ (설치 스크립트가 3.11도 별도로 깔지만, `upload-server`/ComfyUI 설치용 CLI
  도구는 PATH의 `python`을 씀)
- LLM 프로바이더 API 키 하나(Anthropic/OpenRouter/커스텀 브릿지 등 — 아래 §1.2)
- (이미지 생성 쓰려면) FAL API 키(https://fal.ai) — GPU 불필요, 아래 §3 참고

---

## 0. 리포 클론

```powershell
git clone <이 리포의 GitHub URL> C:\hermes
cd C:\hermes
```

아래 §5 표에 있는 파일들은 **`.gitignore`에 걸려 clone에 안 따라온다** — 이 문서에서
하나씩 새로 만든다. 지금 안 만들어도 상관없고, 필요할 때 §5를 다시 펼쳐 보면 된다.

---

## 1. `hermes-agent` 설치 (게이트웨이)

### 1.1 설치

```powershell
iex (irm https://hermes-agent.nousresearch.com/install.ps1)
```

새 PowerShell 창을 열어 `hermes --version` 확인(설치 전에 열려있던 창은 PATH 미반영).
자세한 설치 스크립트 동작·정정 사항: [`setup-windows.md` §1](./setup-windows.md#1-hermes-설치).

### 1.2 LLM 프로바이더

둘 중 하나:

- **Anthropic / OpenRouter 표준 경로** → [`hermes-credentials.md` §1](./hermes-credentials.md)
- **커스텀 OpenAI 호환 브릿지(예: Timely)** → [`timely_ai_api.md`](./timely_ai_api.md) —
  `model.api_key`는 env 파일이 아니라 `config.yaml`에 평문 저장된다는 점 주의(§3 참고)

`hermes chat`으로 아무 말이나 걸어서 응답 오는지 먼저 확인하고 다음으로 간다.

### 1.3 API 서버 활성화

`%LOCALAPPDATA%\hermes` 안의 env 파일(`hermes-config/env.example` 템플릿 참고)에 최소 3줄:

```
API_SERVER_ENABLED=true
API_SERVER_KEY=<32자 이상 랜덤 문자열 — 새로 생성, 기존 PC 것 재사용해도 됨>
API_SERVER_HOST=<이 PC의 LAN NIC IP — 0.0.0.0 아님, 아래 §1.7 이유 참고>
```

`API_SERVER_KEY`는 **upload-server(§2)와 안드로이드 앱 설정에도 똑같이** 들어간다 —
셋이 같은 Bearer 토큰을 공유한다. 생성 예시: [`hermes-credentials.md` §2](./hermes-credentials.md).

### 1.4 웹 검색 백엔드 (권장)

env 파일에 `TAVILY_API_KEY` 등 하나만. 상세: [`hermes-credentials.md` §3](./hermes-credentials.md).

### 1.5 Google Calendar 등록 + OAuth (일정 기능 쓸 경우)

`config.yaml`의 `mcp_servers.calendar`(`hermes-config/config.yaml.example` 참고) +
env 파일의 `GOOGLE_OAUTH_CREDENTIALS`. **`gcp-oauth.keys.json`은 git에 안 올라가고, Google
Cloud Console에서 `client_secret`은 생성 시점에만 받을 수 있다** — 기존 PC의 파일을 그대로
복사해오거나(제일 간단, 새 PC로 파일만 옮기면 됨), 새로 OAuth 클라이언트를 만들어야 한다.
등록 후 "Google Calendar로 인증해줘"로 브라우저 OAuth 1회 필수. 상세:
[`hermes-credentials.md` §5](./hermes-credentials.md) · [`setup-windows.md` §4](./setup-windows.md#4-google-calendar-mcp-등록--oauth).

### 1.6 폰 채널(`api_server`)용 툴셋 활성화

기본으로 `api_server` 플랫폼엔 `file`/`memory`/`skills`/`todo`/`web`만 켜져있다. 이
리포의 첨부/이미지 기능을 쓰려면 명시적으로 더 켜야 한다:

```powershell
hermes tools enable vision --platform api_server
hermes tools enable image_gen --platform api_server
```

(`hermes tools list`로 현재 상태 확인 가능. 이 두 줄을 빼먹으면 폰에서 이미지를 첨부해도
에이전트가 못 "보고", "그림 그려줘"도 텍스트 아트로 대체된다 — 2026-08-29 실측.)

**`image_gen` 툴셋을 켜는 것만으론 부족하다** — 그건 "카테고리"만 켤 뿐, 실제로 어느
프로바이더로 그릴지(`image_gen.provider`)는 따로 정해야 실제 내장 이미지 생성 도구가
잡힌다(안 정하면 에이전트가 그 도구 자체를 못 찾는다 — §3에서 이어서 설정).
`api_server` 플랫폼엔 `terminal`/`code_execution`이 없어서 ComfyUI 같은 CLI 실행형
경로는 아예 못 쓴다 — 그래서 §3의 API 호출형 provider가 폰에서 유일하게 동작하는 방식이다.

### 1.7 방화벽 (LAN 전용, 최초 1회)

관리자 PowerShell:

```powershell
New-NetFirewallRule -DisplayName "Hermes API Server (LAN only)" `
  -Direction Inbound -Protocol TCP -LocalPort 8642 -Action Allow `
  -Profile Any -LocalAddress <위 §1.3의 API_SERVER_HOST> -RemoteAddress <폰이 붙는 LAN 대역>/24
```

**반드시 확인할 함정** — Wi-Fi NIC가 `Public` 프로필로 분류돼있고, 그 PC의 `python.exe`에
이미 Public **Block** 인바운드 룰이 걸려있으면 위 Allow 룰이 무효가 된다(Block이 항상
이김). 확인/해제 방법: [`upload-server/README.md` "방화벽" 절](../upload-server/README.md) —
8643용으로 적었지만 8642에도 동일하게 적용된다. 상세 배경:
[`windows-migration.md` §7.9](./windows-migration.md).

### 1.8 로그온 시 자동 시작

```powershell
hermes gateway install
```

상세: [`setup-windows.md` §6](./setup-windows.md#6-게이트웨이-상시-기동).

### 검증

```powershell
curl.exe -H "Authorization: Bearer $env:API_SERVER_KEY" http://<HOST>:8642/health
```

`{"status":"ok",...}`가 나오면 완료.

---

## 2. `upload-server` 설치 (이 리포 안)

```powershell
cd C:\hermes\upload-server
python -m pip install -e ".[dev]"
```

실행에 필요한 환경변수:

| 변수 | 값 |
|---|---|
| `UPLOAD_SERVER_API_KEY` | §1.3의 `API_SERVER_KEY`와 **동일한 값** |
| `UPLOAD_SERVER_HOST` | §1.3과 같은 LAN IP |
| `UPLOAD_SERVER_INBOX_DIR` | **절대경로**로 지정할 것(예: `C:\hermes\upload-server\uploads\inbox`) — 상대경로를 주면 게이트웨이 프로세스가 다른 cwd에서 그 경로를 찾다가 실패한다(2026-08-29 실측 버그, 코드 자체는 이제 항상 절대경로로 정규화하지만 습관적으로 절대경로를 주는 게 안전) |

방화벽은 §1.7과 완전히 동일한 패턴, 포트만 `8643`(같은 Public Block 룰 함정도 동일).

지금은 **수동 시작**이 정상 운영 방식이다(자동시작은 나중에 외부접속 안정화할 때 같이
설정하기로 결정됨). 실행 명령·검증법: [`upload-server/README.md`](../upload-server/README.md).

---

## 3. 이미지 생성 설정 (`image_gen` provider — FAL)

이미지 생성 안 쓸 거면 이 섹션 전체를 건너뛴다 — 나머지 기능(채팅, 캘린더, 파일 첨부,
vision)은 전부 그대로 정상 작동한다.

별도 설치·GPU·모델 다운로드 전혀 필요 없다 — 호스팅된 API를 호출하는 내장 도구를
켜는 것뿐이다:

```powershell
hermes config set FAL_KEY "<fal.ai에서 발급받은 키>"
hermes config set image_gen.provider fal
hermes gateway restart
```

`hermes config set image_gen.provider fal` 실행 시 "not a recognized config key"
경고가 뜰 수 있는데, 무시해도 된다 — CLI의 키 화이트리스트가 이 값을 모를 뿐 실제로는
`config.yaml`에 정확히 `image_gen: {provider: fal}`로 저장되고(소스 코드로 확인,
`hermes_cli/tools_config.py`), 게이트웨이는 이 값을 정상적으로 읽는다.

`image_gen.provider`로 고를 수 있는 값은 fal/openai/xai/krea/nous — 각각 필요한 키는
[`hermes-credentials.md` §4](./hermes-credentials.md)에 정리돼있다. FAL을 기본으로
추천하는 이유도 거기 있다.

**설정 반영에 `hermes gateway restart`가 필요하다** — `config.yaml`/env 파일을 고친
뒤 게이트웨이가 이미 떠 있으면 새 값을 안 읽는다(실측 확인).

---

## 4. 안드로이드 앱을 새 백엔드로 연결

- **앱을 다시 안 만든다면**: 폰 앱 설정 화면에서 "서버 URL"/"업로드 서버 URL"만 새 PC의
  LAN IP로 바꾸면 끝. API 키는 §1.3과 같은 값이면 그대로 둬도 됨.
- **이 새 PC에서 앱을 다시 빌드하려면**: `android/local.properties`도 새로 작성해야 한다
  (이 파일도 gitignore 대상, §5 참고).

---

## 5. gitignore된 파일 — 전부 재작성 대상

`git clone`만으로는 아래 파일들이 안 온다. 각각 어디서 값을 구하는지:

| 파일 | 무엇을 담음 | 어디서 구하나 |
|---|---|---|
| `%LOCALAPPDATA%\hermes` 안의 env 파일 | LLM/API서버/웹검색/캘린더/이미지생성 시크릿 | `hermes-config/env.example` 템플릿 + §1.2~1.5, §3 |
| `%LOCALAPPDATA%\hermes\config.yaml` | 모델 설정, MCP 등록, 툴셋 목록 | `hermes-config/config.yaml.example` 템플릿 + §1.5~1.6 |
| `gcp-oauth.keys.json` (경로는 env 파일이 가리킴) | 구글 캘린더 OAuth 클라이언트 | 기존 PC에서 파일 복사, 또는 Google Cloud Console 재발급(§1.5) |
| `android/local.properties` | 안드로이드 빌드 기본값(서버 URL/API 키/업로드 서버 URL) | 앱을 이 PC에서 재빌드할 때만 필요, §4 |
| (`UPLOAD_SERVER_API_KEY` 등) | 파일이 아니라 실행 시 환경변수 | §1.3의 `API_SERVER_KEY`와 동일한 값을 그때그때 넘김 |

---

## 6. 전체 검증 체크리스트

```powershell
# 1. 게이트웨이
curl.exe -H "Authorization: Bearer $env:API_SERVER_KEY" http://<HOST>:8642/health

# 2. 업로드 서버
curl.exe -X POST http://<HOST>:8643/upload -H "Authorization: Bearer $env:API_SERVER_KEY" -F "file=@아무파일"

# 3. 이미지 생성 (설정했다면) — image_gen.provider가 잡히는지, 도구 검색으로 확인
#    ("고양이 그림 그려줘" 같은 실제 /v1/runs 요청으로 진짜 파일이 생기는지 확인하는 게 제일 확실함)
hermes config get image_gen.provider

# 4. 텍스트 왕복 (/v1/runs)
curl.exe -X POST http://<HOST>:8642/v1/runs -H "Authorization: Bearer $env:API_SERVER_KEY" -H "Content-Type: application/json" -d '{\"input\": \"안녕\"}'
```

마지막으로 폰 앱에서 설정 화면 URL을 갱신하고 실제로 메시지/첨부를 한 번씩 보내서
end-to-end로 확인한다.
