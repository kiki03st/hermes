# 이미지 뷰어 구현 계획

스펙: `docs/superpowers/specs/2026-08-29-image-viewer-design.md`.
main에 직접 커밋(이 세션 관례). 각 태스크 TDD: 실패하는 테스트 먼저, 구현,
통과 확인.

## Task 1 — upload-server: 다운로드 라우트

1. `config.py`: `Config`에 `generated_dir: str` 필드 추가,
   `from_env()`에서 `UPLOAD_SERVER_GENERATED_DIR`(기본 `./generated`) 읽기.
2. `storage.py`: `resolve_generated_path(generated_dir, tool, filename) -> Path | None`
   — `sanitize_filename`으로 각각 정리 후 조립, `.resolve()` 후
   `generated_dir.resolve()` 하위인지 `is_relative_to()`로 검증, 아니면 `None`.
   - 테스트(`test_storage.py`): 정상 경로 반환, `..`/절대경로 등 traversal 시도
     시 `None`.
3. `server.py`: `handle_download` 추가 — Bearer 인증 → `resolve_generated_path`
   → 없으면 403, 파일 없으면 404, 있으면 바이트 + `mimetypes.guess_type` 기반
   `Content-Type`. `make_app`에 `GET /generated/{tool}/{filename}` 라우트 등록.
   - 테스트(`test_server.py`): 정상 200(바이트 일치), 인증 실패 401, 없는 파일
     404, tool/filename에 `..` 넣은 요청 403(또는 404 — sanitize가 "file"로
     치환하니 그 이름의 파일이 없으면 404가 나올 수 있음, 실제 동작대로 단언).
4. `README.md`에 `GET /generated/{tool}/{filename}` 엔드포인트 문서화 +
   `UPLOAD_SERVER_GENERATED_DIR` env var 추가.

## Task 2 — comfyui-bridge: 출력 위치 변경

1. `config.py`: `OUTPUT_DIR` 기본값을
   `<repo-root>/upload-server/generated/comfyui/`로 변경.
   - 테스트(`test_config.py`, 신규): env var 없을 때 기본값이
     `upload-server`, `generated`, `comfyui`로 끝나는 경로인지 (정확한 절대
     경로 대신 경로 구성요소로 단언 — 실행 환경마다 repo 위치가 다름).
2. `.gitignore`: `mcp-comfyui-bridge/generated/` → `upload-server/generated/`로
   교체.
3. `mcp-comfyui-bridge/README.md`의 출력 경로 언급 갱신.

## Task 3 — Android: 모델 + 파싱

1. `ChatMessage.kt`: `ChatMedia(id, tool, filename, status: MediaStatus = Loading)`,
   `MediaStatus`(`Loading`/`Loaded(bytes: ByteArray)`/`Failed(message: String)`)
   추가. `AssistantTurn`에 `media: List<ChatMedia> = emptyList()` 필드 추가.
2. `ChatReducer.kt`:
   - `parseGeneratedMediaPath(path: String): Pair<String, String>?` — `\`를
     `/`로 정규화 후 `/generated/` 위치 찾아 그 다음 `tool/filename` 두 조각만
     파싱, 형식 안 맞으면 `null`.
   - `extractMedia(text: String): Pair<String, List<ChatMedia>>` — 줄 단위로
     `MEDIA:<path>` 매칭, `parseGeneratedMediaPath` 성공하면 그 줄 제거하고
     `ChatMedia` 누적, 실패하면 줄 그대로 유지.
   - `RunEvent.RunCompleted` 분기에서 최종 텍스트에 `extractMedia` 적용,
     결과 텍스트 + `media`를 턴에 반영.
   - `applyMediaStatus(messages, turnId, mediaId, status): List<ChatMessage>`
     추가.
   - 테스트(`ChatReducerTest.kt`): `parseGeneratedMediaPath` 정상/화이트리스트
     밖/`\`與`/` 둘 다, `extractMedia`가 태그 줄 제거 + 나머지 텍스트 보존,
     `RunCompleted` 이벤트 적용 시 `media` 채워지고 텍스트에서 태그 사라짐,
     `applyMediaStatus`가 해당 항목만 갱신.

## Task 4 — Android: 다운로드 클라이언트

1. `FileUploadClient.kt`: `MediaDownloadClient` 인터페이스(`downloadGenerated`),
   `DownloadOutcome`(`Success(bytes)`/`Failure(statusCode, message)`) 추가.
   `FileUploadClient`가 `MediaDownloadClient` 구현 — `GET {url}/generated/{tool}/{filename}`,
   Bearer 인증, 실패 시 상태코드 + 본문.
   - 테스트(`FileUploadClientTest.kt`): 로컬 `HttpServer`로 정상 다운로드,
     404, 401 각각 확인(기존 업로드 테스트와 같은 패턴).

## Task 5 — Android: 코루틴 접합 + 렌더링

1. `ChatConversationState.kt`: 생성자에 `mediaClient: () -> MediaDownloadClient`
   추가(필수 파라미터, 기존 `client`와 동급). `collect()`에서 `RunCompleted`
   수신 시 `downloadPendingMedia()` 호출 — 마지막 턴의 `Loading` 미디어마다
   코루틴 띄워 `mediaClient().downloadGenerated(tool, filename)` 실행,
   결과를 `ChatReducer.applyMediaStatus`로 반영.
   - 테스트(`ChatConversationStateTest.kt`): 가짜 `MediaDownloadClient`로
     `RunCompleted` 후 `Loading → Loaded`(또는 `Failed`) 전이 확인.
2. `HermesRuntime.kt`: `ChatConversationState` 생성 시 `mediaClient` 인자로
   `FileUploadClient(...)` 전달(업로드에 쓰는 것과 같은 `uploadServerUrl`/
   `apiKey` 클로저 재사용).
3. `ChatMessageList.kt`: `AssistantBubble`에 `turn.media` 렌더링 추가 —
   `Loading`(스피너)/`Loaded`(`BitmapFactory.decodeByteArray` → `Image`)/
   `Failed`(에러 텍스트). 신규 `import android.graphics.BitmapFactory`,
   `androidx.compose.foundation.Image`, `androidx.compose.ui.graphics.asImageBitmap`,
   `androidx.compose.runtime.remember`.

## Task 6 — 전체 검증 + 커밋 + 문서

1. Python: `pytest` (upload-server, mcp-comfyui-bridge) 전부 통과 확인.
2. Android: `./gradlew testDebugUnitTest assembleDebug` 통과 확인.
3. `docs/backend-new-machine-setup.md`에 `UPLOAD_SERVER_GENERATED_DIR` 관련
   언급 필요한지 확인하고 필요시 반영.
4. 커밋 + push.
