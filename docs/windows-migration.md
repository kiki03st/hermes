# Windows 이관 노트 (AI 에이전트용)

> **대상 독자: 이 리포를 Windows PC에서 이어받는 AI 에이전트.**
> 이 리포는 Ubuntu 리눅스 서버(`kiki-server`)에서 작성됐다 — Android SDK/에뮬레이터,
> AutoCAD/SketchUp/3ds Max, Hermes 게이트웨이 전부 그 환경에 없었다. 코드는 그 제약 안에서
> 검증 가능한 만큼(pytest, `./gradlew build`, lint) 실제로 돌려서 확인했고, 여기 적힌 것만
> Windows에서 마저 하면 된다. **처음부터 다시 설계하지 말 것** — 아래 "이미 확정된 것"은
> 근거가 있어서 그렇게 되어 있다.

## 0. 한 줄 요약 체크리스트

> **이관은 2026-08-28에 끝났다.** 아래 1~6은 전부 완료 상태다. 새로 이어받는 경우
> **§7 "Windows 이관에서 실제로 부딪힌 것"을 먼저 읽을 것** — 이 문서 나머지가 예측이라면
> §7은 실측이다.

1. ✅ `android/local.properties` 재생성 — 단, 아래 §7.2의 정슬래시 형식으로
2. ✅ `platforms;android-36.1` 설치 확인 (33·34도 함께 있음)
3. ✅ `.\gradlew.bat build` **BUILD SUCCESSFUL**, Kotlin 유닛테스트 15개 통과
   — 단, **리포를 `C:\hermes`로 옮긴 뒤에야** 통과했다 (§7.1)
4. ✅ `hermes-config/*.example`의 경로를 `C:\hermes-projects\...`로 정정
   (D: 드라이브 없음). 설정 반영 위치는 `~/.hermes/`가 아니라 `%LOCALAPPDATA%\hermes\` (§7.3)
5. 🟡 `vendor/README.md`의 MCP 중 **google-calendar-mcp만 등록 완료**(npx로 실행, 클론 불필요).
   CAD-MCP/sketchup-mcp/3dsmax-mcp는 해당 앱이 미설치라 Stage 3~5로 미룸
6. ✅ `docs/setup-windows.md` 기준 Stage 0 완료 — 실제 캘린더 이벤트 생성까지 검증
7. **(2026-08-28)** §1.8/§3.1/§4는 Ubuntu 실측 기록이다. Windows 실측은 §7.

---

## 1. Android — 이미 확정된 것 (재검토 불필요)

### 1.1 `local.properties`는 리눅스 경로다 — 지우고 다시 만들 것

`android/local.properties`는 `.gitignore`에 걸려 있지만, 이 리포를 그대로 디렉터리째 복사해왔다면
`sdk.dir=/home/kiki03st/android-toolchain/sdk` 값이 남아있을 수 있다. Windows에서는:

```
sdk.dir=C\:\\Users\\<사용자명>\\AppData\\Local\\Android\\Sdk
```

Android Studio로 `android/` 폴더를 열면 자동 생성되므로, 파일이 없으면 그냥 열기만 하면 된다.
PLAN.md가 이미 확인한 값: `ANDROID_HOME` 미설정 상태이므로 이 파일로 SDK 위치를 명시해야 한다.

### 1.2 `android/toolchain-env.sh`는 리눅스 전용 — Windows에서는 무시

이 스크립트는 이 리포를 리눅스 서버에서 빌드 검증하려고 `~/android-toolchain/`(JDK 21, Gradle 9.3.1,
Android SDK cmdline-tools를 sudo 없이 홈 디렉터리에 독립 설치한 것)를 가리키는 용도로만 만들었다.
Windows에서는 Android Studio가 JDK/Gradle을 자체 관리하므로 **삭제해도 되고 안 해도 무해**하다.

### 1.3 AGP 9의 Kotlin 통합 방식 — `org.jetbrains.kotlin.android` 플러그인을 다시 넣지 말 것

`app/build.gradle.kts`, `wear/build.gradle.kts`에 `org.jetbrains.kotlin.android` 플러그인이
**의도적으로 빠져 있다**. AGP 9.1.1부터 Kotlin 안드로이드 지원이 AGP에 내장되어 이 플러그인을
적용하면 다음 에러가 난다 (리눅스에서 실제로 재현하고 고침):

```
Failed to apply plugin 'org.jetbrains.kotlin.android'.
The 'org.jetbrains.kotlin.android' plugin is no longer required for Kotlin support since AGP 9.0.
```

같은 이유로 `android { kotlinOptions { jvmTarget = ... } }` 블록도 없다 — 그 DSL 자체가 이
플러그인이 제공하던 것이라 지금은 `Unresolved reference`가 난다. JVM 타깃은 `compileOptions`의
`sourceCompatibility`/`targetCompatibility`로만 지정되어 있고, 빌드가 통과하는 걸로 이미 확인했다.

### 1.4 `compileSdk = 36` + `compileSdkMinor = 1` — 이렇게 쓴 이유

PLAN.md가 확정한 환경은 `platforms;android-36.1`이다. AGP 9.1.1에서 이 값을 지정하는 방법은
문자열 오버로드(`compileSdkVersion("android-36.1")`, deprecated, AGP 10에서 제거 예정)가 아니라
아래처럼 두 프로퍼티를 나누는 것이다 (`CommonExtension` 클래스 바이트코드를 직접 역어셈블해서
확인함):

```kotlin
android {
    compileSdk = 36
    compileSdkMinor = 1
}
```

**Windows에서 할 일**: Android Studio SDK Manager 또는 CLI로 이 플랫폼이 설치돼 있는지 확인.

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" --list | Select-String "36.1"
& "$env:LOCALAPPDATA\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-36.1" "build-tools;36.1.0"
```

설치가 안 돼 있거나 이 리비전이 사라졌으면 "1.5 대안"으로 간다.

### 1.5 의존성 버전을 최신에서 일부러 낮춘 이유 — compileSdk 37 요구 충돌

`android/gradle/libs.versions.toml`의 다음 값들은 **당시 최신판이 아니라 의도적으로 내린 버전**이다:

| 라이브러리 | 지금 리포 값 | 당시 최신 (동작 안 함) | 이유 |
|---|---|---|---|
| `androidx.core:core-ktx` | 1.17.0 | 1.19.0 | 1.19.0은 compileSdk 37 요구 |
| `androidx.lifecycle:*` | 2.9.4 | 2.11.0 | 2.11.0은 compileSdk 37 요구 |
| `androidx.activity:activity-compose` | 1.12.4 | 1.13.0 | 1.13.0은 compileSdk 37 요구 |
| `androidx.compose:compose-bom` | 2026.05.00 | 2026.08.00 | 2026.08.00의 compose-ui 1.12.0이 compileSdk 37 요구 |

실제로 최신 버전으로 `./gradlew build`를 돌리면 `:app:checkDebugAarMetadata`에서 이렇게 실패한다:

```
Dependency 'androidx.core:core-ktx:1.19.0' requires libraries and applications that
depend on it to compile against version 37 or later of the Android APIs.
:app is currently compiled against android-36.1.
```

**대안 (compileSdk를 37로 올리는 경우)**: PLAN.md가 android-36.1을 "확인 완료" 항목으로 못박아서
지금은 그걸 존중하는 쪽으로 맞췄다. 만약 Windows 쪽 Android Studio SDK가 이미 37을 쓰고 있거나
최신 Compose를 쓰고 싶다면, `compileSdk = 37`(`compileSdkMinor` 제거)로 바꾸고 위 표의 오른쪽
열 버전으로 올리면 된다 — 둘 중 하나로 통일해야지 섞으면 다시 같은 에러가 난다.

### 1.6 워치 알림 코드의 런타임 권한 체크 — lint가 실제로 잡은 버그였다

`wear/.../PhoneRelayListenerService.kt`의 `notifyResult()`에 `ActivityCompat.checkSelfPermission`
체크가 있는 건 형식적인 게 아니라, `NotificationManagerCompat.notify()`를
`areNotificationsEnabled()`만 확인하고 호출했을 때 실제로 lint가 `MissingPermission` 에러로
빌드를 막았기 때문이다 (`POST_NOTIFICATIONS`는 API 33+ 런타임 권한). 지우지 말 것.

### 1.8 실제 서버로 검증한 앱 버그 3개 — 이미 고쳐져 있음, 되돌리지 말 것

`kiki-server`에 Hermes 게이트웨이를 실제로 띄우고 이 앱을 실기기(폰)에 설치해서 진짜 왕복 테스트를
했다. Fake `HttpTransport`로만 하던 유닛테스트에서는 안 잡히던 버그 3개를 실기기에서 잡았다 —
Windows 이관 후에도 그대로 유지해야 한다:

1. **`AndroidManifest.xml`의 `android:usesCleartextTraffic="true"`**: 서버가 TLS 없는 사설 LAN
   IP(`http://192.168.x.x:8642`)라 targetSdk 28+ 기본 정책(HTTPS만 허용)에 걸려 조용히 연결
   실패한다. Stage 7에서 실제 TLS(Cloudflare Tunnel 등)를 붙이기 전까지는 유지할 것.
2. **`HttpTransport.kt`의 예외 처리**: 원래 `HttpURLConnection` 호출에 `catch`가 없어서 타임아웃/
   연결거부 같은 네트워크 예외가 코루틴 밖으로 튀어나가 **앱이 그냥 죽었다** (에러 메시지도 없이).
   지금은 모든 예외를 잡아 `HttpResult(0, "네트워크 오류: ...")`로 정규화한다.
3. **읽기 타임아웃을 connect 타임아웃과 분리, read를 120초로**: 원래 15초로 묶여 있었는데, Hermes는
   에이전트라 툴 호출을 몇 번 거친 뒤에야 답하므로(실측: 캘린더 등록 1건에 17초, 다단계 작업은 더
   걸림) 15초는 실사용에서 거의 항상 타임아웃 났다.

추가로 `HermesApp.kt`/`WatchRelayListenerService.kt`에 `X-Hermes-Session-Id`(대화창, 리셋 가능)와
`X-Hermes-Session-Key`(기기 영구 키, `SettingsStore.getOrCreateLongTermMemoryKey()`)를 분리해서
넣었다 — 이유는 §3.2 참고.

### 1.9 폰 앱 기본값 주입 (`local.properties` → `BuildConfig`)

`app/build.gradle.kts`가 `local.properties`의 `hermes.serverUrl`/`hermes.apiKey`를 읽어
`BuildConfig.DEFAULT_SERVER_URL`/`DEFAULT_API_KEY`로 심고, `SettingsStore`가 저장된 값이 없을 때
이 기본값을 쓴다 — 재빌드마다 앱에서 서버 URL/키를 손으로 다시 입력할 필요가 없게 하려는
용도다. Windows에서 새 서버 주소로 테스트하려면 `android/local.properties`에 같은 두 줄만
추가하면 된다 (git에 안 올라가는 파일).

### 1.7 Gradle 실행

리포에 `gradlew`(sh)와 `gradlew.bat`이 둘 다 있다. PowerShell/cmd에서는:

```powershell
.\gradlew.bat build
```

`gradle/wrapper/gradle-wrapper.properties`가 Gradle 9.3.1 bin 배포판 URL을 가리키므로 최초 실행 시
자동 다운로드된다 (PLAN.md가 확인한 `~/.gradle/wrapper/dists` 캐시가 이미 있다면 그걸 재사용).

---

## 2. mcp-acad-assist (Python) — Windows에서 달라지는 부분

- 이 서버는 PEP 668(externally-managed-environment) 제약이 있는 Debian/Ubuntu 시스템 Python이라
  `pip install --user --break-system-packages -e ".[dev]"`로 우회 설치했다. **Windows에서는 이
  플래그가 필요 없다** — 그냥 `pip install -e ".[dev]"` (venv 권장).
- `pywin32`는 `pyproject.toml`에 `sys_platform == 'win32'` 마커로 걸려 있어서 Windows에서
  `pip install -e .`를 돌리면 자동으로 같이 설치된다. 리눅스에서는 애초에 설치 대상이 아니었다.
- `com.py`의 `Win32AcadPort`는 `win32com.client`를 메서드 안에서 지연 임포트한다 — 모듈 자체의
  임포트는 어디서든 안 깨지지만, **실제 COM 호출은 AutoCAD가 설치된 Windows에서만** 성립한다.
  진짜 AutoCAD로 확인이 필요한 부분(Stage 3):
  - `capture.py`: `PlotToFile` 용지 크기·배율·플롯 스타일 인자가 지금은 최소 골격뿐
  - `export.py`: DXF 저장용 `AcSaveAsType` 버전 상수가 아직 `NotImplementedError` — 실 AutoCAD
    ActiveX 문서에서 확인해서 채워야 함
- 기존 pytest 18개는 COM을 목(mock)으로 대체한 순수 로직 테스트라 Windows에서도 그대로
  통과해야 한다(`cd mcp-acad-assist && pytest -v`). 통과 안 하면 이관 중 뭔가 깨진 것이니 먼저
  그것부터 잡을 것 — Stage 3 COM 통합 이전에 순수 로직이 깨지면 안 된다.
- MCP SDK는 `mcp>=2.0.0`로 pin했다 — 리눅스에서 실제로 `pip install`했을 때 최신이 2.1.1(2.x대,
  `FastMCP`가 `MCPServer`로 개명된 버전)이었고 `server.py`도 그 API로 맞춰놨다. Windows에서 설치할
  때 혹시 3.x가 나와 있으면 다시 breaking change가 있을 수 있으니 `python -c "from mcp.server.mcpserver import MCPServer"`로 먼저 확인.

---

## 3. Hermes 설정 파일 — 경로 플레이스홀더 교체

`hermes-config/config.yaml.example`과 `env.example`의 `C:\hermes-projects\...` 경로는
**실제 프로젝트 폴더 위치가 아니라 예시**다. `~/.hermes/config.yaml`·`~/.hermes/.env`에 반영할 때:

1. `mcp_servers.acad2d.args`의 CAD-MCP 클론 경로
2. `mcp_servers.max3d.args`의 3dsmax-mcp 클론 경로
3. `mcp_servers.calendar.env.GOOGLE_OAUTH_CREDENTIALS` 실제 OAuth JSON 경로
4. `env.example`의 같은 `GOOGLE_OAUTH_CREDENTIALS`

를 실제 Windows 경로로 바꿀 것. `vendor/README.md`에 각 MCP를 어디로 클론하고 어떻게 실행하는지
(정확한 `command`/`args`는 각 리포 README를 직접 확인해서 적어놓은 것) 정리돼 있다.

`max3d`의 `tools.include` 목록(약 24개)은 3dsmax-mcp README 기준으로 카테고리별로 골라낸
**추정 목록**이다 — 실제 설치 후 `hermes mcp`로 전체 도구명을 다시 확인해서 이름이 안 맞는 게
있으면 보정할 것 (Stage 5 작업, PLAN.md에도 명시돼 있음).

### 3.1 모델 백본이 Timely AI (custom provider)로 바뀌었다

PLAN.md/`docs/hermes-credentials.md` 원안은 Anthropic 또는 OpenRouter 직결이었는데, 실제로는
Timely AI의 OpenAI 호환 브릿지를 쓰는 걸로 확정됐다 — 자세한 배경·트러블슈팅은
`docs/timely_ai_api.md` 참고. Windows에서도 이 4줄을 그대로 쓸 것:

```bash
hermes config set model.provider custom
hermes config set model.base_url https://hello.timelygpt.co.kr/api/v2/chat/bridge/openai
hermes config set model.default "anthropic/claude-haiku-4.5"
hermes config set model.api_key <Timely 키>
```

**주의 (실측, v0.20.6)**: `model.provider: custom`일 때 Hermes는 `OPENAI_API_KEY` 환경변수를
**안 읽는다** — 위처럼 `model.api_key`로 넣어야 한다. 이 키는 `config.yaml`에 **평문 저장**되므로
`chmod 600`(Windows는 파일 속성/ACL로 상응 조치) 해둘 것.

### 3.2 API 서버용 툴셋을 최소로 줄여놨다 — 그대로 유지할 것

기본 설치 상태는 `api_server` 플랫폼에 terminal/code_execution/delegation/cronjob/image_gen/
vision/session_search까지 다 켜져 있는데, 이건 폰 앱 용도로 전부 불필요하고 매 호출마다 전체
스키마가 같이 전송돼 토큰·지연시간만 늘린다 (실측: 캘린더 1건 등록 시 123K → 65K 토큰으로 절반
이하). 게다가 인터넷에 노출된 엔드포인트에 `terminal`/`code_execution`을 켜두는 건 보안적으로도
안 좋다 (게이트웨이 자체가 기동 시 이 경고를 띄운다). 지금 `~/.hermes/config.yaml`의
`platform_toolsets.api_server`는 `file, memory, skills, todo, web`만 남아있다 — Windows에서
새로 설정할 때 아래를 그대로 실행하면 된다 (CAD MCP는 `mcp_servers`로 별도 등록되는 거라 이
목록과 무관하게 항상 켜져 있음):

```bash
hermes tools disable terminal code_execution delegation cronjob image_gen vision session_search --platform api_server
```

### 3.3 세션 연속성: `X-Hermes-Session-Id` vs `X-Hermes-Session-Key`

Android 앱이 두 헤더를 분리해서 보낸다 — 헷갈리기 쉬우니 정리:

- `X-Hermes-Session-Id`: 지금 대화창의 원문 트랜스크립트. 앱의 "새 대화" 버튼이 이걸 리셋한다.
  헤더가 없으면 `/v1/chat/completions`는 기본이 stateless라 매 호출이 완전히 새 대화가 된다.
- `X-Hermes-Session-Key`: 기기 단위로 한 번 생성해 절대 안 바뀌는 장기 기억 스코프. **단, 실측
  확인 결과 외부 메모리 프로바이더(Honcho 등)가 연결돼 있지 않으면 이 값은 사실상 아무 격리도
  안 한다** — 내장 memory 도구는 프로필당 전역 저장소라 session_key와 무관하게 이미 다 기억한다.
  1인 개인용 인스턴스라 지금은 문제 없지만, 나중에 외부 프로바이더를 붙이면 그때부터 이 헤더가
  실제로 격리 기능을 한다 — 미리 배선해둔 것.

### 3.4 게이트웨이는 systemd 서비스로 띄웠다 (Windows는 서비스/작업 스케줄러로)

`nohup hermes gateway &`는 이 환경(systemd user session)에서 부모 프로세스가 정리될 때 같이
죽는 문제가 있어서 `hermes gateway install`로 정식 systemd user 서비스로 전환했다
(`hermes gateway status`/`hermes gateway restart`로 관리, linger 활성화로 로그아웃해도 유지).
Windows에서는 `hermes gateway install`이 launchd/systemd 대신 Windows 서비스 또는 작업
스케줄러 방식으로 동작할 것 — 마찬가지로 `hermes gateway install` 먼저 시도하고, 안 되면
그냥 터미널을 띄워두는 대신 시작 시 자동 실행되는 방식(작업 스케줄러 "로그온 시 실행")을 쓸 것.

### 3.5 Google Calendar OAuth — Windows에서는 훨씬 간단함

이 서버는 헤드리스라 `npx @cocal/google-calendar-mcp auth`가 브라우저를 못 띄워서 SSH
포트포워딩(`ssh -L 3500:localhost:3500 ...`)으로 우회해야 했다 — 자세한 절차는
`docs/hermes-credentials.md` §5 참고. **Windows PC는 실제 브라우저가 있으므로 이 우회가
전혀 필요 없다** — `docs/setup-windows.md` §4 그대로 진행하면 된다. OAuth 클라이언트
JSON(`gcp-oauth.keys.json`)의 `client_secret`은 **생성 시점에만 다운로드 가능**하다는 것만
주의 (Google 정책 변경, 기존 클라이언트는 재다운로드 안 됨 — 새로 만들거나 secret reset).

---

## 4. 이미 실제로 검증된 것 (2026-08-28, kiki-server) — Windows에서 재검증 불필요

옛날 버전 이 문서는 아래 항목들을 "여기서 검증 불가능"으로 분류했었는데, Hermes 게이트웨이를
실제로 이 서버에 띄우고(systemd 서비스) 폰에 실제 APK를 설치해서 왕복 테스트를 완료했다 —
**Windows에서 처음부터 다시 검증할 필요 없음**:

- ✅ Hermes 게이트웨이 `/v1/chat/completions` 실제 호출 — `ChatModels.kt`의 응답 파싱이 실제
  서버 응답과 맞는다는 것 확인 (§1.8의 버그 3개를 이 과정에서 발견·수정)
- ✅ 구글 캘린더에 실제로 이벤트가 생성/삭제됨 (자연어 요청 → MCP 툴 호출 → 실제 캘린더 반영,
  왕복 확인)
- ✅ 세션 연속성(`X-Hermes-Session-Id`)이 실제로 대화 맥락을 이어준다는 것, "새 대화" 리셋 후에도
  장기 기억(memory 도구)은 별도로 유지된다는 것
- ✅ Hermes의 스킬 자동 학습(`skill_manage`, 백그라운드 리뷰)이 실제로 동작 — 반복 작업 후
  `~/.hermes/skills/`에 새 스킬이 자동 생성되는 것까지 실측 확인
- ✅ 외부 네트워크(다른 Wi-Fi/이동통신망)에서 폰 앱 → Hermes 왕복 — Cloudflare quick tunnel로
  검증. 단, quick tunnel은 재시작마다 URL이 바뀌고 SLA가 없는 임시용이라 Windows 이관 시
  PLAN.md Stage 7의 영구 터널(named tunnel + 도메인) 또는 Tailscale로 교체할 것

## 5. 여기서 검증 못 한 것 — Windows PC + 실기기에서 반드시 처음 확인해야 하는 것

리눅스 서버가 아니라 **실제 워치·폰 하드웨어가 없어서** 여전히 확인 불가능한 항목:

- 워치 실기기 ↔ 폰 실기기 Bluetooth Data Layer 페어링/전송 (에뮬레이터로도 페어링 재현이 까다로움)
- `RecognizerIntent` STT 실제 동작, 워치 TTS/진동/알림 실제 소리·촉각
- `WatchRelayListenerService`가 폰 앱 강제종료 상태에서도 시스템이 깨우는지, 삼성 배터리 최적화
  예외 처리
- CAD 3종(AutoCAD/SketchUp/3ds Max) 실제 COM/Ruby/네이티브 브리지 — 이게 바로 Stage 3이고, 이
  문서 이관의 본래 목적이다

이 항목들은 **코드를 고쳐서 될 일이 아니라 Windows PC + 실기기에서 실행해봐야 아는 것**이다 —
빌드가 통과한다고 기능이 된다는 뜻은 아니라는 걸 최종 보고에도 반드시 구분해서 전달할 것.

---

## 7. Windows 이관에서 실제로 부딪힌 것 (2026-08-28 실측)

§1~§5는 리눅스에서 쓴 **예측**이고, 이 절은 Windows PC에서 실제로 돌려보고 남긴 **실측**이다.
어긋나는 경우 이 절이 맞다.

### 7.1 경로에 한글이 있으면 Gradle 빌드가 깨진다 — 리포를 `C:\hermes`로 옮겼다

리포가 원래 `C:\Users\ksy\OneDrive\바탕 화면\hermes`에 있었다. AGP가 바로 거부한다:

```
Your project path contains non-ASCII characters. This will most likely cause the build to fail
on Windows. Please move your project to a different directory.
```

`-Pandroid.overridePathCheck=true`로 우회하면 컴파일·APK 조립까지는 통과하지만
`:app:testDebugUnitTest`가 죽는다 — 테스트 워커 JVM이 한글 경로의 클래스패스를 못 읽는다:

```
Could not execute test class 'com.hermes.app.HermesApiClientTest'.
Caused by: java.lang.ClassNotFoundException: com.hermes.app.HermesApiClientTest
```

`android/gradle.properties`에 `-Dfile.encoding=UTF-8`이 **이미 있는데도** 안 된다.
그래서 리포를 `C:\hermes`로 옮겼고, 옮긴 뒤에는 우회 플래그 없이 `BUILD SUCCESSFUL`이 났다.
부수 효과로 OneDrive가 `build/` 디렉터리를 동기화하는 문제도 없어졌다.

**리포 위치는 `C:\hermes`다.** OneDrive 쪽에 남은 폴더는 `_MOVED_TO_C_HERMES.txt` 표식만
있는 옛 사본이므로 지워도 된다.

### 7.2 `local.properties`는 정슬래시로 쓰는 게 안전하다

`sdk.dir=C\:\\Users\\...` 형식(Android Studio가 생성하는 형태)은 도구를 거치며 백슬래시가
한 겹 사라지면 Java Properties가 `\U`를 잘못 해석해 경로가 망가진다. 정슬래시는 이스케이프가
전혀 필요 없고 Gradle이 그대로 받는다:

```
sdk.dir=C:/Users/ksy/AppData/Local/Android/Sdk
hermes.serverUrl=http://172.30.1.101:8642
hermes.apiKey=<API_SERVER_KEY>
```

### 7.3 설정 위치는 `~/.hermes`가 아니라 `%LOCALAPPDATA%\hermes`

`install.ps1`이 `HERMES_HOME`을 `$env:LOCALAPPDATA\hermes`로 잡고 **사용자 환경변수로 심는다**
(스크립트 주석: *"of the Unix default ~/.hermes"*). 실제 경로:

| 대상 | 경로 |
|---|---|
| 설정 | `C:\Users\ksy\AppData\Local\hermes\config.yaml` |
| 환경변수 파일 | 같은 폴더의 `.env` |
| 설치 트리 | `...\hermes\hermes-agent` (venv 포함, Python 3.11.9) |
| `hermes` 명령 | `...\hermes\bin\hermes.exe` (사용자 PATH에 추가됨) |
| 스킬 | `...\hermes\skills` |

이 문서와 `setup-windows.md`·`hermes-credentials.md`·`timely_ai_api.md`의 `~/.hermes/` 표기는
전부 리눅스 기준이다 — Windows에서는 위 경로로 읽을 것.

또한 **`install.ps1`은 실재하고 네이티브 설치가 된다.** `timely_ai_api.md` 이전 판의
"Windows 네이티브 미지원 → WSL2" 는 틀렸다(해당 절은 정정해 놨다). WSL2였다면
`mcp-acad-assist`의 pywin32 COM이 Windows 호스트의 AutoCAD에 닿지 못해 Stage 3 아키텍처를
갈아야 했다.

비대화형(스크립트/에이전트) 설치는 `-SkipSetup`을 쓰면 설정 마법사에서 멈추지 않는다.
설치 중 **browser tools npm / TUI npm / computer-use 드라이버 3개가 실패**하는데, api_server
용도로는 전부 불필요하다 (browser 툴셋은 어차피 끈다).

### 7.4 MCP stdio 서버의 `command`는 Windows에서 확장자가 필요하다

`config.yaml.example`의 `calendar.command: "npx"`는 Windows에서 실패한다. Hermes는 MCP stdio
서버를 셸 없이 subprocess로 띄우고, Windows subprocess는 PATHEXT를 적용하지 않는다:

```
npx      -> FileNotFoundError: [WinError 2] 지정된 파일을 찾을 수 없습니다
npx.cmd  -> OK (npm 11.16.0, Node v24.18.0)
```

그래서 `command: "npx.cmd"`, `args: ["-y", "@cocal/google-calendar-mcp"]`로 등록했다.
`-y`는 TTY 없는 게이트웨이에서 npx 설치 확인 프롬프트로 멈추지 않게 하는 것이다.
`config.yaml.example`도 같이 고쳐 놨다. Stage 4~5의 `uvx`/`uv`는 `.exe`라 이 문제가 없다.

### 7.5 `hermes mcp add`는 비대화형에서 끝까지 못 간다

`hermes mcp add calendar --command npx.cmd ...`는 13개 도구를 정상 발견하지만
`Enable all 13 tools? [Y/n/select]:` 프롬프트에서 EOF를 만나 `Cancelled`로 종료되고
`config.yaml`에 아무것도 안 쓴다. 비대화형에서는 `config.yaml`의 `mcp_servers` 블록을
직접 병합할 것. 실제로 읽히는 서버별 키(소스 확인):
`command`, `args`, `env`, `cwd`, `connect_timeout`, `enabled`, `lazy`,
`supports_parallel_tool_calls`, `tools.include`, `tools.exclude`.

### 7.6 Google OAuth: `manage-accounts`를 제외하면 Hermes 경유 인증이 막힌다

`config.yaml.example`이 `tools.exclude: ["manage-accounts"]`로 빼놨는데, 그게 바로 인증을
담당하는 도구다. 그래서 `setup-windows.md` §4.6의 *"Hermes에게 인증해달라고 요청"* 경로는
이 필터 상태에서 동작하지 않는다. CLI로 별도 인증하면 된다:

```powershell
$env:GOOGLE_OAUTH_CREDENTIALS = "C:\hermes-projects\secrets\gcp-oauth.keys.json"
npx.cmd -y "@cocal/google-calendar-mcp" auth
```

브라우저가 자동으로 열리고(리다이렉트 `http://localhost:3500/oauth2callback`), 동의하면
토큰이 저장된다. **토큰 위치는 `HERMES_HOME` 밖이다**:

```
C:\Users\ksy\.config\google-calendar-mcp\tokens.json
```

Ubuntu에서 필요했던 `ssh -L 3500:localhost:3500` 우회는 Windows에서 전혀 필요 없다.

### 7.7 `hermes gateway install`은 Windows를 지원한다 — 작업 스케줄러, 실패 시 시작 폴더

`hermes gateway --help`는 "systemd/launchd"만 적고 있지만 실제로는 Windows 경로가 있다.
비승격 상태로 돌리면 UAC를 건너뛰고 시작 폴더로 폴백한다:

```
Scheduled Task install blocked (administrator approval was not used) -- using Startup folder fallback
Installed Windows login item: ...\Startup\Hermes_Gateway.vbs
  Task script: C:\Users\ksy\AppData\Local\hermes\gateway-service\Hermes_Gateway.cmd
```

`hermes gateway status` / `restart` / `stop`으로 관리된다. 더 견고하게 가려면 승격된 창에서
`hermes gateway install`을 다시 돌려 작업 스케줄러로 올릴 것.

### 7.8 게이트웨이 기동 시 나오는 Windows 특이 경고 2개 (둘 다 비치명적)

```
AttributeError: module 'asyncio' has no attribute 'start_unix_server'
  at gateway/shutdown_watchdog.py:572 in loop_heartbeat_forever
```
Windows에 유닉스 소켓이 없어서 liveness probe가 witness 없이 동작한다. 경고 후 진행된다.

```
linked SQLite 3.45.1 is vulnerable to the WAL-reset corruption bug
  -- using journal_mode=DELETE instead of enabling WAL
```
번들 SQLite가 낮아 WAL을 못 켜고 `journal_mode=DELETE`로 폴백한다. `hermes update`로 임베디드
런타임을 복구하라고 안내한다.

### 7.9 방화벽: 이 PC에서는 `setup-windows.md` §5를 그대로 하면 안 된다

실측 네트워크 구성이 문서 가정과 반대다:

| 인터페이스 | IP | 프로필 | 비고 |
|---|---|---|---|
| `이더넷` | 121.147.94.22/24 (DHCP) | **Private** | **공인 IP, NAT 없음.** 기본 경로(metric 1) |
| `Wi-Fi` | 172.30.1.101/24 (**고정**) | **Public** | 폰이 붙는 망. 게이트웨이 172.30.1.254 |

즉 문서의 `-Profile Private` 룰은 공인 IP 쪽을 인터넷에 열고, 정작 폰이 붙는 Wi-Fi는 막는다.

게다가 이 PC에는 게이트웨이가 실제로 실행되는 인터프리터
(`C:\Program Files\WindowsApps\PythonSoftwareFoundation.Python.3.11_...\python3.11.exe`)에 대한
인바운드 룰이 **이미 4개** 있었다 — Public **Block** ×2, Private Allow ×2. Windows Firewall은
Block이 Allow를 이기므로, Public 프로필(Wi-Fi)에 포트 Allow 룰을 새로 추가해도 무효다.

**채택한 구성 (2겹)**:
1. `API_SERVER_HOST=172.30.1.101` — `0.0.0.0`이 아니라 Wi-Fi NIC에만 바인딩. 공인 IP NIC는
   리스닝조차 하지 않는다 (`Get-NetTCPConnection`으로 확인).
2. Public Block 룰 2개를 **비활성화**(삭제 아님)하고, 8642만 주소 스코프로 Allow:
   ```powershell
   New-NetFirewallRule -DisplayName "Hermes API Server (LAN only)" `
     -Direction Inbound -Protocol TCP -LocalPort 8642 -Action Allow `
     -Profile Any -LocalAddress 172.30.1.101 -RemoteAddress 172.30.1.0/24
   ```
   기본 인바운드 정책이 3프로필 전부 `BlockInbound`라 Block 룰을 껴도 열리는 것은 없다.
   재현 스크립트: `C:\hermes-projects\firewall-setup.ps1` (ASCII 전용 — PowerShell 5.1은
   BOM 없는 `.ps1`을 cp949로 읽어 한글 주석이 있으면 파싱이 깨진다).

`-RemoteAddress`에 `LocalSubnet`을 쓰면 **안 된다** — 이더넷 쪽 `121.147.94.0/24`, 즉 같은
대역의 다른 가입자까지 허용된다.

**남는 위험**: 기존 Private Allow 룰이 `LocalPort: Any`/`RemoteIP: Any`라, 저 인터프리터의
모든 인바운드가 Private 프로필에서 열려 있다. 지금은 바인딩 주소가 방어선이므로 노출이 없지만,
`API_SERVER_HOST`를 `0.0.0.0`으로 바꾸면 공인 IP로 즉시 노출된다. Stage 7에서 Cloudflare
named tunnel로 가면 8642 인바운드 자체가 필요 없어진다.

### 7.10 Timely 키 형식

`timely_ai_api.md`는 `sdk_live_...`로 적고 있지만 실제 발급 키는 **`tgpt_sk_...`(72자)** 였다.
저장 위치는 문서대로 `config.yaml`의 `model.api_key` 평문이 맞다 (`OPENAI_API_KEY`는 안 읽힘).
평문이므로 `icacls`로 파일 ACL을 사용자 전용으로 잠갔다 — `config.yaml`, `.env`,
`gcp-oauth.keys.json`, `tokens.json` 모두.

### 7.11 `server.py`의 MCP import는 문제 없었다

`mcp-acad-assist/src/acad_assist/server.py:8`의 `from mcp.server.mcpserver import MCPServer`는
pytest가 `server.py`를 전혀 임포트하지 않아 미검증 상태였는데, 직접 확인 결과 **정상**이다:

```
mcp 2.1.1
from mcp.server.mcpserver import MCPServer  -> OK
import acad_assist.server                   -> OK (<class 'mcp.server.mcpserver.server.MCPServer'>)
acad-assist.exe 콘솔 스크립트 등록 확인
```

Windows에서 `pip install -e ".[dev]"`는 `--break-system-packages` 없이 그대로 되고,
`pywin32 312`가 자동 설치된다. pytest 18개 통과.

---

## 6. 이 문서 자체에 대해

이관이 끝나고 위 항목이 전부 확인됐으면 이 파일은 지워도 된다 — 더 이상 유효한 "할 일"이 아니라
"이미 끝난 일의 기록"이 되기 때문이다. 지우지 않고 남겨두고 싶다면 각 섹션 앞에 완료 표시만
추가하는 걸 권장한다.
