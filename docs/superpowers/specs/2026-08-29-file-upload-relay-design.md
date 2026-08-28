# 폰 → Hermes 에이전트 파일/이미지 업로드 릴레이 설계

## 배경

Hermes 안드로이드 앱은 지금 텍스트만 보낼 수 있다. 게이트웨이(`/v1/runs`) 프로토콜
자체도 파일 업로드를 받지 않는다(`_handle_runs`는 JSON `input` 문자열만 받음).
반면 게이트웨이 쪽 에이전트는 이미 `file` 툴셋(`platform_toolsets.api_server`에
포함)으로 로컬 파일 경로를 읽을 수 있고, `vision` 툴셋을 켜면(`hermes tools enable
vision --platform api_server`) `vision_analyze` 툴로 이미지 경로를 실제로 "볼" 수
있다. 즉 필요한 건 새 프로토콜이 아니라 **폰에서 올린 파일을 게이트웨이가 도는 그
컴퓨터의 디스크 위 어딘가로 옮겨주는 릴레이**뿐이다 — 그 이후는 이미 있는 텍스트
채널 + 에이전트의 기존 툴이 처리한다.

## 목표

- 폰에서 이미지/파일을 선택해 채팅에 첨부하듯 보낼 수 있다.
- 에이전트는 그 파일을 기존 `file`/`vision_analyze` 툴로 읽는다 — 새 에이전트 능력
  불필요.
- 게이트웨이(`hermes-agent`) 코드는 한 줄도 안 건드린다 — 별도 git 관리 설치라 직접
  수정하면 `hermes update`(git pull 기반, `updates.non_interactive_local_changes:
  stash` 설정 확인됨) 때 조용히 stash되어 날아갈 위험이 있음(실측: `hermes update
  --help` = "Pull the latest changes from git and reinstall dependencies").
- 오래 안 쓸 파일은 자동 정리되고, 에이전트가 중요하다고 판단한 파일은 남는다.

## 범위 밖

- 외부망(집 Wi-Fi 밖) 접속 — 게이트웨이 자체도 아직 임시(Cloudflare quick tunnel,
  재시작마다 URL 바뀜) 단계라 이번 작업 범위 밖. 도메인 없으면 나중에 별도 작업으로.
- 파일 타입별 저장 경로 분리 — 논의 후 기각(보관 필요성은 파일 타입이 아니라 내용의
  중요도 축이라 안 맞물림, YAGNI).
- 이미지 전용 카메라 촬영 플로우, 갤러리/문서 선택기 분리 — 시스템 파일 선택기
  하나로 통일.

## 아키텍처

```
[Android 앱] --multipart POST(Bearer 인증)--> [upload-server, 새 포트] --파일 저장--> uploads/inbox/
                                                                              |
[Android 앱] --텍스트(경로+note 포함) POST--> [게이트웨이 /v1/runs] --file/vision_analyze 툴로 읽음
```

두 서버(게이트웨이, upload-server)는 완전히 독립된 프로세스. 공유하는 건 (1) 같은
PC 디스크(업로드된 파일 경로를 게이트웨이 쪽 에이전트가 읽을 수 있어야 함) (2) 같은
Bearer API 키(인증 재사용) 뿐.

## 컴포넌트

### 1. `upload-server/` (신규, 이 리포)

`mcp-acad-assist/`처럼 독립 파이썬 프로젝트(`pyproject.toml`). 스택: `aiohttp`
(멀티파트 파싱, 게이트웨이 자체 스택과 결이 맞음).

- 바인딩: 게이트웨이와 같은 LAN NIC 주소(`172.30.1.101` 등, 설정 가능), 새 포트
  (기본 `8643`).
- 인증: `Authorization: Bearer <API_SERVER_KEY>` — 게이트웨이가 쓰는 것과 동일한 키
  재사용. 다른 키는 안 만듦(같은 신뢰 경계 — LAN 전용, 1인 사용).
- 엔드포인트 하나: `POST /upload`
  - 요청: `multipart/form-data`, 파일 파트 하나.
  - 응답 200: `{"path": "<서버 절대경로>", "note": "<자동삭제 안내 문구>"}`.
  - 응답 400: 크기 초과/빈 파일/인증 실패 등, `{"error": "..."}`.
- 저장 위치: `uploads/inbox/<uuid4 앞 8자리>_<원본파일명>` — 충돌 방지, 원본 이름은
  사람이 알아보기 위해 보존.
- 크기 제한: 기본 100MB (설정 가능한 상수).
- 파일 타입 제한 없음 — 서버는 게이트키핑 안 하고 그대로 저장, 무엇을 할지는 에이전트
  쪽 툴이 알아서 판단.
- 정리(sweep): 서버 프로세스 안에서 도는 주기적 `asyncio` 백그라운드 태스크. 기본
  14일(설정 가능) 지난 `uploads/inbox/` 안 파일을 삭제. 에이전트가 `file` 툴로
  다른 위치로 옮긴 파일은 이미 `inbox/` 밖이라 안 건드림 — 이게 "장기 보관 여부"
  판단 메커니즘의 전부다(별도 플래그/DB 불필요).
- `note` 문구 예시: `"(이 파일은 14일 후 자동 삭제됩니다. 계속 보관하려면 다른
  위치로 옮겨두세요.)"` — 정책 문구가 서버 쪽에 있어서 앱 업데이트 없이 문구/기간
  변경 가능.

### 2. Android 앱 변경

- `SettingsStore.kt` / `HermesSettings`: `uploadServerUrl: String = ""` 필드 추가.
  기존 `serverUrl`/`apiKey`와 동일 패턴(`stringPreferencesKey`, `BuildConfig.
  DEFAULT_UPLOAD_SERVER_URL` ← `local.properties`의 `hermes.uploadServerUrl`).
- `ui/settings/SettingsScreen.kt`: 업로드 서버 URL 입력란 하나 추가(기존 두 필드
  옆에 같은 스타일로).
- 새 `FileUploadClient.kt`: 기존 `HttpTransport.kt`와 같은 스타일 — 순수
  `HttpURLConnection`으로 멀티파트 바디 직접 구성(새 의존성 안 씀). 시그니처:
  `fun upload(fileName: String, mimeType: String, bytes: ByteArray): UploadOutcome`
  (`sealed interface UploadOutcome { data class Success(path, note); data class
  Failure(statusCode, message) }`).
- `ui/chat/ChatScreen.kt`: 기존 마이크 `IconButton` 옆에 "첨부" `IconButton` 추가.
  `rememberLauncherForActivityResult(ActivityResultContracts.GetContent())`로 시스템
  파일 선택기 열기(이미지/문서 다 커버). 선택되면:
  1. `ContentResolver`로 바이트+MIME 읽기 (`Dispatchers.IO`)
  2. `FileUploadClient.upload(...)` 호출
  3. 성공 시 `state.submit(userTypedText + "\n" + note + " (경로: " + path + ")")`
     — 정확히는 note 안에 정책 문구, 별도로 경로 문자열도 명시해서 에이전트가 어떤
     텍스트로도 경로를 놓치지 않게 함.
  4. 실패 시 채팅에 `ChatMessage.SystemNotice`로 에러 표시, 전송 안 함.
  - 업로드 중엔 첨부 버튼 로딩/비활성 상태만 — 별도 진행률 화면 없음.

## 에러 처리

| 상황 | 처리 |
|---|---|
| 업로드 서버 응답 없음/타임아웃 | `SystemNotice`, 재시도는 사용자가 버튼 다시 누름 |
| 400(크기초과 등) | 서버가 준 사유 그대로 `SystemNotice` |
| 401(인증 실패) | `SystemNotice` — 설정의 API 키/업로드 URL 확인 안내 |
| 파일 선택 취소 | 아무 것도 안 함 |

## 보안 고려

- LAN 전용 바인딩 + 방화벽 스코프(게이트웨이와 동일 패턴) — 외부 노출 없음.
- Bearer 키 재사용 — 새 공격면 추가 아님(이미 존재하는 신뢰 경계 그대로 확장).
- 업로드 파일명은 서버가 `uuid` 접두어를 붙여 저장하되, 원본 파일명 문자열 자체를
  경로 조립에 그대로 쓰지 않는다(디렉터리 traversal 방지 — `os.path.basename` +
  화이트리스트 문자만 허용하는 새니타이즈 필요, 구현 시 명시).

## 테스트 계획

- `upload-server`: `pytest` — 업로드 성공/크기초과/인증실패/멀티파트 파싱 실패 케이스,
  스윕 로직(오래된 파일 삭제, 최근 파일 보존, `inbox/` 밖 파일 안 건드림)을 실제
  파일시스템(임시 디렉터리) 대상으로 검증.
- Android: `FileUploadClient`는 `RunsClientTest.kt`와 같은 패턴으로 가짜
  `HttpURLConnection`/로컬 소켓 기반 단위테스트. `ChatScreen`의 UI 로직은 이 리포
  기존 관례상 Compose 화면 자체는 테스트 안 함(기존 `ChatScreen.kt` 무테스트 선례
  따름) — 실기기/에뮬레이터에서 첨부 → 업로드 → 에이전트가 실제로 파일 읽는지까지
  수동 실측으로 검증.

## 검증 계획 (구현 완료 후)

1. `upload-server` 단위테스트 그린.
2. `.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest` 그린.
3. 실기기: 이미지 하나 첨부 → 전송 → 에이전트가 `vision_analyze`로 실제 내용을
   답변에 반영하는지 확인(예: 사진 속 물체를 정확히 설명하는지).
4. 실기기: 일반 문서(txt 등) 첨부 → 전송 → 에이전트가 `file` 툴로 내용을 읽어
   답변에 반영하는지 확인.
5. `upload-server`를 직접 켜서 `inbox/`에 과거 mtime으로 조작한 더미 파일을 넣고
   스윕 주기가 실제로 지우는지, 최근 파일은 안 지우는지 확인.
