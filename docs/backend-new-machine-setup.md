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
| 3 | 이미지 생성 (`mcp-comfyui-bridge` + 로컬 ComfyUI) | **부분** — MCP 서버는 `git clone`으로 같이 오지만, ComfyUI 자체는 별도 설치 + GPU 필요 | 폰의 "그림 그려줘" 요청을 실제 이미지 파일로 만드는 백엔드 |

세 개 다 옮겨야 폰 앱이 지금 PC에서 하던 걸 새 PC에서도 그대로 할 수 있다. 하나라도
빠지면 그 기능만 조용히 안 됨(예: 이미지 생성 설정 없으면 "그림 그려줘"에 텍스트 아트로
답하거나, 코드 실행 도구를 찾아 헤매다 아무 결과 없이 여러 턴을 낭비함 — 2026-08-29 실측,
아래 §3 참고. 1(a)+2 만 있으면 CAD/캘린더 등 MCP는 다시 등록해야 됨 — CAD는 아직 Stage 3
미완이라 이 문서 범위 밖).

> **2026-08-30 정정 — 실제로 쓰는 건 로컬 ComfyUI다, FAL 아니다.** 이 문서는 한동안
> "ComfyUI는 못 쓴다, `image_gen.provider: fal`만 쓴다"고 적고 있었다. 그 근거였던
> 2026-08-29 실측(에이전트가 이미지를 못 만들고 30턴 넘게 우회만 반복)은 **Hermes
> 내장 `comfyui` 스킬**(터미널로 `comfy` CLI를 직접 실행하는 방식 — 폰이 쓰는
> `api_server`엔 `terminal`/`code_execution` 툴셋이 아예 없어서 이건 지금도 못 쓴다,
> §1.6 참고) 얘기였다. 그 문제를 풀려고 바로 이 리포에 **`mcp-comfyui-bridge`를
> 직접 만들었다** — 터미널이 아니라 REST API(`127.0.0.1:8188`)로 ComfyUI를 호출하는
> 좁은 MCP 서버라 `api_server`에서도 문제없이 동작한다(설계: `docs/superpowers/specs/
> 2026-08-29-comfyui-mcp-bridge-design.md`). 그리고 이게 지금 실제로 쓰이고 있는
> 경로다 — FAL은 유료라 지금 안 쓴다. 아래 §3을 comfyui-bridge 기준으로 정정했다.
> ("~2026-08-29 실측"과 같은 날짜에 "ComfyUI는 못 쓴다"와 "그래서 comfyui-bridge를
> 만들었다"가 둘 다 있었던 셈 — 앞 문장만 남고 뒤 문장(해결책)이 이 문서에 안 반영돼서
> 한동안 서로 모순된 채로 있었다.)

## 사전 준비물 (새 PC)

- Windows 10/11
- `git`
- Python 3.10+ (설치 스크립트가 3.11도 별도로 깔지만, `upload-server`/ComfyUI 설치용 CLI
  도구는 PATH의 `python`을 씀)
- LLM 프로바이더 API 키 하나(Anthropic/OpenRouter/커스텀 브릿지 등 — 아래 §1.2)
- (이미지 생성 쓰려면) ComfyUI + SD1.5 체크포인트 + GPU, 아래 §3 참고. GPU가 없는
  환경이면 §3 끝의 "GPU 없을 때" 대안(FAL 등 유료 API)을 대신 본다.

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

### 1.2b 기본 모델 (Sonnet)

설치 직후 기본값은 보통 Haiku급 저비용 모델이다 — 실측 확인(2026-08-29): 이 모델로는
스킬/도구 선택이 부정확해지고(예: 존재하지 않는 `terminal` 도구를 계속 찾아 헤맴),
`MEDIA:` 태그 지시를 놓치는 등 이 리포의 기능들과 안 맞는 동작이 잦았다. Sonnet으로
바꾸면 확연히 안정적이다:

```powershell
hermes config set model.default anthropic/claude-sonnet-5
hermes gateway restart
```

(커스텀 브릿지 프로바이더를 쓰면 실제 모델 ID가 다를 수 있다 — 그 프로바이더의
`/v1/models`로 사용 가능한 이름을 먼저 확인할 것, §1.2 참고.)

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
리포의 첨부 기능을 쓰려면 명시적으로 더 켜야 한다:

```powershell
hermes tools enable vision --platform api_server
```

(`hermes tools list`로 현재 상태 확인 가능. 이걸 빼먹으면 폰에서 이미지를 첨부해도
에이전트가 못 "본다" — 2026-08-29 실측.)

**`image_gen` 툴셋은 comfyui-bridge를 쓸 거면 필요 없다** — MCP 서버(comfyui-bridge
포함)는 `config.yaml`의 `mcp_servers:` 등록만으로 잡힌다, 이 `hermes tools enable`
화이트리스트를 안 탄다(calendar와 같은 패턴, §1.5 참고). `image_gen` 툴셋은 §3 끝의
"GPU 없을 때" 유료 API 대안(fal 등 **Hermes 내장** provider)을 쓸 때만 필요하다:

```powershell
hermes tools enable image_gen --platform api_server
```

그것도 켜는 것만으론 부족하다 — "카테고리"만 켤 뿐, 실제로 어느 프로바이더로 그릴지
(`image_gen.provider`)는 따로 정해야 도구가 잡힌다(§3 끝부분에서 설정).

`api_server` 플랫폼엔 `terminal`/`code_execution`이 없어서 `comfy` CLI를 직접 부르는
경로(Hermes 내장 `comfyui` 스킬)는 못 쓴다 — 그래서 REST API로 ComfyUI를 부르는
`mcp-comfyui-bridge`(§3)를 따로 만들었다. GPU 자체가 없는 환경이면 §3 끝의 API
호출형 provider(fal 등, 유료)가 대안이다.

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

### 1.9 file-redirect 플러그인 (사용자용 문서를 폰에서 받을 수 있게)

```powershell
Copy-Item -Recurse "hermes-config\plugins\file-redirect" "$env:LOCALAPPDATA\hermes\plugins\file-redirect"
hermes plugins enable file-redirect
```

`write_file`로 만든 문서(md/txt 등)를 폰에서 다운로드 못 받는 문제를 결정론적으로
고쳐준다 — 자세한 이유와 동작 방식은
[`hermes-config/plugins/file-redirect/README.md`](../hermes-config/plugins/file-redirect/README.md)
참고. `hermes gateway restart`는 아래 §검증 전에 어차피 한 번 더 하게 되니 그때
같이 반영됨.

### 1.10 excalidraw 스킬 문서 수정 (불필요한 도구 호출 방지)

Hermes가 기본 제공하는 `excalidraw` 스킬(`%LOCALAPPDATA%\hermes\skills\creative\excalidraw\SKILL.md`)
원본은 "선택적으로 `terminal`로 `scripts/upload.py`를 실행해서 excalidraw.com에 업로드"하라고
안내한다 — 근데 폰 채널(`api_server`)엔 애초에 `terminal` 도구 자체가 없다(§1.6과 같은 이유).
그래서 다이어그램 하나 그릴 때마다 모델이 있지도 않은 도구를 `tool_search`로 몇 번씩
찾아 헤매다가, 사용자에게 "터미널이 없어서 업로드 못 했다"는 불필요한 문구까지 붙여
응답했다(실측, 2026-08-30). 어차피 이 리포는 `write_file`이 file-redirect 플러그인(§1.9)을
거쳐 폰에 바로 렌더링되므로 업로드 자체가 불필요 — 그 단계를 스킬 문서에서 아예 뺐다.

```powershell
Copy-Item -Force "hermes-config\skills\excalidraw\SKILL.md" "$env:LOCALAPPDATA\hermes\skills\creative\excalidraw\SKILL.md"
```

파일 경로가 다르면(스킬 설치 위치가 버전마다 바뀔 수 있음) `hermes skills list`로 실제
설치 경로를 먼저 확인할 것. 코드 변경이 아니라 문서(prompt) 수정이라 게이트웨이
재시작 없이 다음 턴부터 바로 반영된다.

### 검증

```powershell
curl.exe -H "Authorization: Bearer $env:API_SERVER_KEY" http://<HOST>:8642/health
```

`{"status":"ok",...}`가 나오면 완료. 재시작 직후엔 **게이트웨이 프로세스가 중복으로
남아있지 않은지** 한 번 확인하는 게 좋다(실측, 2026-08-30: 로그온 자동시작 항목과
수동 `hermes gateway restart`가 겹쳐서 유휴 상태의 중복 프로세스가 하나 더 떠 있었던
적이 있음 — 기능엔 지장 없지만 리소스 낭비):

```powershell
Get-Process python*, python3.11 -ErrorAction SilentlyContinue |
  Where-Object { $_.Path -like "*hermes*" } |
  Select-Object Id, Path, StartTime
```

`hermes_cli.main gateway run`으로 실행 중인 프로세스가 여러 개면, 실제로 8642를
리스닝 중인 것(`netstat -ano | findstr 8642`로 확인)만 남기고 나머지는 정리한다.

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
| `UPLOAD_SERVER_GENERATED_DIR` | 선택, 기본값 `./generated`로 충분. `comfyui-bridge` 등 생성기가 여기 밑 `<tool>/`에 저장한 파일을 폰 앱이 `GET /generated/{tool}/{filename}`로 받아와 채팅 버블에 렌더링한다(설계 문서: `docs/superpowers/specs/2026-08-29-image-viewer-design.md`) |

방화벽은 §1.7과 완전히 동일한 패턴, 포트만 `8643`(같은 Public Block 룰 함정도 동일).

지금은 **수동 시작**이 정상 운영 방식이다(자동시작은 나중에 외부접속 안정화할 때 같이
설정하기로 결정됨). 실행 명령·검증법: [`upload-server/README.md`](../upload-server/README.md).

---

## 3. 이미지 생성 설정 (`mcp-comfyui-bridge` + 로컬 ComfyUI)

이미지 생성 안 쓸 거면 이 섹션 전체를 건너뛴다 — 나머지 기능(채팅, 캘린더, 파일 첨부,
vision)은 전부 그대로 정상 작동한다.

**GPU가 있는 환경일 것.** 로컬 ComfyUI로 무료로 그리는 대신, GPU 있는 PC에서만 된다.

### 3.1 ComfyUI 자체 설치·기동 (이 리포 밖)

```powershell
comfy launch --background
curl.exe http://127.0.0.1:8188/system_stats   # 확인 — 응답 오면 정상
```

SD1.5 체크포인트(`v1-5-pruned-emaonly.safetensors`)가 이미 받아져 있어야 한다. 상세
설치 절차는 이 문서 범위 밖(ComfyUI 공식 문서 참고) — 여기선 "이미 켜져있다"는 전제로
이어간다. **에이전트가 대신 켜주지 않는다** — 폰이 쓰는 `api_server`엔 터미널 도구가
없어서 `comfy launch`를 자기가 실행할 방법이 없다(§1.6과 같은 이유). 매번 이 PC를
새로 켤 때 사람이 먼저 띄워둬야 한다.

### 3.2 `mcp-comfyui-bridge` 설치

```powershell
cd mcp-comfyui-bridge
python -m pip install -e ".[dev]"
```

`hermes-config/config.yaml.example`의 `mcp_servers.comfyui-bridge` 블록을
`%LOCALAPPDATA%\hermes\config.yaml`에 병합한 뒤:

```powershell
hermes gateway restart
hermes mcp list   # comfyui-bridge가 뜨는지 확인
```

환경변수(전부 선택, 기본값 있음)와 자세한 동작은
[`mcp-comfyui-bridge/README.md`](../mcp-comfyui-bridge/README.md) 참고. 출력 위치는
기본 `upload-server/generated/comfyui/` — `upload-server`(§2)가 폰에 서빙하는 위치라
`UPLOAD_SERVER_GENERATED_DIR`와 짝이 맞아야 한다.

**검증**: 폰에서 "그림 그려줘" → `upload-server/generated/comfyui/`에 실제 PNG가
생기고, 채팅 버블에 렌더링되는지 확인.

### GPU 없을 때 — 유료 API 대안

로컬 GPU가 없는 환경이면 `image_gen.provider`(fal/openai/xai/krea/nous 중 선택,
전부 유료)로 대신할 수 있다:

```powershell
hermes config set FAL_KEY "<fal.ai에서 발급받은 키>"
hermes config set image_gen.provider fal
hermes gateway restart
```

`hermes config set image_gen.provider fal` 실행 시 "not a recognized config key"
경고가 뜰 수 있는데, 무시해도 된다 — CLI의 키 화이트리스트가 이 값을 모를 뿐 실제로는
`config.yaml`에 정확히 `image_gen: {provider: fal}`로 저장되고(소스 코드로 확인,
`hermes_cli/tools_config.py`), 게이트웨이는 이 값을 정상적으로 읽는다. 각 프로바이더에
필요한 키는 [`hermes-credentials.md` §4](./hermes-credentials.md) 참고.

comfyui-bridge와 `image_gen` provider 둘 다 등록해두면, 모델이 상황에 따라 어느 쪽을
고를지는 보장이 없다 — 지금은 **comfyui-bridge만 쓰고 `image_gen.provider`는 안
정한 상태**(FAL은 유료라 실제로 쓴 적 없음). 둘 다 켜고 싶으면 그 조합이 실제로
의도대로 동작하는지 별도 확인이 필요하다(아직 실측 안 함).

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

# 3. 이미지 생성 (설정했다면)
#    comfyui-bridge 쓰는 경우: ComfyUI가 떠있는지 먼저 확인
curl.exe http://127.0.0.1:8188/system_stats
#    ("고양이 그림 그려줘" 같은 실제 /v1/runs 요청으로 진짜 파일이 생기는지 확인하는 게 제일 확실함)
hermes mcp list   # comfyui-bridge가 목록에 있는지
#    GPU 없어서 image_gen provider(fal 등)로 대신하는 경우:
hermes config get image_gen.provider

# 4. 텍스트 왕복 (/v1/runs)
curl.exe -X POST http://<HOST>:8642/v1/runs -H "Authorization: Bearer $env:API_SERVER_KEY" -H "Content-Type: application/json" -d '{\"input\": \"안녕\"}'
```

마지막으로 폰 앱에서 설정 화면 URL을 갱신하고 실제로 메시지/첨부를 한 번씩 보내서
end-to-end로 확인한다.
