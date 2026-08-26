# Windows 이관 노트 (AI 에이전트용)

> **대상 독자: 이 리포를 Windows PC에서 이어받는 AI 에이전트.**
> 이 리포는 Ubuntu 리눅스 서버(`kiki-server`)에서 작성됐다 — Android SDK/에뮬레이터,
> AutoCAD/SketchUp/3ds Max, Hermes 게이트웨이 전부 그 환경에 없었다. 코드는 그 제약 안에서
> 검증 가능한 만큼(pytest, `./gradlew build`, lint) 실제로 돌려서 확인했고, 여기 적힌 것만
> Windows에서 마저 하면 된다. **처음부터 다시 설계하지 말 것** — 아래 "이미 확정된 것"은
> 근거가 있어서 그렇게 되어 있다.

## 0. 한 줄 요약 체크리스트

1. `android/local.properties` 삭제 후 재생성 (리눅스 경로가 박혀 있음)
2. `sdkmanager`로 `platforms;android-36.1`, `build-tools;36.1.0` 설치 확인
3. `./gradlew build` 한 번 돌려서 Windows에서도 그대로 통과하는지 확인 — 실패하면 "3. 재현 안 되면"부터
4. `hermes-config/*.example`의 `D:\hermes-projects\...` 경로를 실제 경로로 바꿔서 `~/.hermes/`에 반영
5. `vendor/README.md` 보고 CAD-MCP/sketchup-mcp/3dsmax-mcp/google-calendar-mcp 클론
6. `docs/setup-windows.md` 그대로 Stage 0 진행

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

`hermes-config/config.yaml.example`과 `env.example`의 `D:\hermes-projects\...` 경로는
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

---

## 4. 여기서 검증 못 한 것 — Windows에서 반드시 처음 확인해야 하는 것

이 서버에서 확인 **불가능**했고 가짜 데이터로 대체하지도 않은 항목. Stage 1 "검증" 섹션과 동일:

- 워치 실기기 ↔ 폰 실기기 Bluetooth Data Layer 페어링/전송 (에뮬레이터로도 페어링 재현이 까다로움)
- `RecognizerIntent` STT 실제 동작, 워치 TTS/진동/알림 실제 소리·촉각
- `WatchRelayListenerService`가 폰 앱 강제종료 상태에서도 시스템이 깨우는지 (Stage 2 항목이지만
  Stage 1에서 한 번 만져보는 게 좋음), 삼성 배터리 최적화 예외 처리
- Hermes 게이트웨이 `/v1/chat/completions` 실제 호출 — 지금 `HermesApiClient`/
  `WatchRelayListenerService`는 fake `HttpTransport`로만 검증됨, 진짜 서버 응답 스키마가
  `ChatModels.kt`의 가정과 다르면 파싱이 깨질 수 있음
- 구글 캘린더에 실제로 이벤트가 들어가는지

이 항목들은 **코드를 고쳐서 될 일이 아니라 Windows PC + 실기기에서 실행해봐야 아는 것**이다 —
빌드가 통과한다고 기능이 된다는 뜻은 아니라는 걸 최종 보고에도 반드시 구분해서 전달할 것.

---

## 5. 이 문서 자체에 대해

이관이 끝나고 위 항목이 전부 확인됐으면 이 파일은 지워도 된다 — 더 이상 유효한 "할 일"이 아니라
"이미 끝난 일의 기록"이 되기 때문이다. 지우지 않고 남겨두고 싶다면 각 섹션 앞에 완료 표시만
추가하는 걸 권장한다.
