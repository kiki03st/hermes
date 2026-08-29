# 이미지 뷰어 설계

2026-08-29. 상태: 승인됨 (채팅으로 섹션별 확인 완료).

## 문제

`comfyui-bridge`의 `generate_image`가 만든 이미지는 에이전트 응답 텍스트에
`MEDIA:<절대경로>` 형태로만 나온다(예: `MEDIA:C:\hermes\mcp-comfyui-bridge\generated\b7016299_hermes_00004_.png`,
실측 확인 — `run_a80ffe42806c4e569c21cd50b3c92b85` 세션 export). 이 태그는
게이트웨이가 `/v1/chat/completions`·`/v1/responses` 엔드포인트에서만 자동으로
base64 인라인 처리하고, 앱이 쓰는 `/v1/runs`는 건드리지 않는다(이전 세션에서
게이트웨이 소스로 확인 완료) — 그래서 폰 화면엔 그냥 서버 컴퓨터의 파일 경로
문자열이 리터럴 텍스트로 뜬다. 실제 이미지는 서버 컴퓨터 로컬에서만 볼 수 있다.

Claude·ChatGPT 같은 클라이언트는 이미지 결과를 경로 텍스트가 아니라 실제
이미지 바이트(또는 서명 URL)로 클라이언트에 전달하고 채팅 버블에 바로
렌더링한다 — 이 패턴을 재현한다.

## 목표

- 에이전트가 생성한 이미지를 폰 앱 채팅 버블 안에 실제로 렌더링한다.
- `comfyui-bridge`부터 시작하되, 나중에 추가될 다른 생성기(AutoCAD, 3ds Max,
  SketchUp, V-Ray MCP)도 같은 컨벤션으로 자연스럽게 얹을 수 있어야 한다.
- 임의 경로를 그대로 서빙하는 경로순회 취약점을 만들지 않는다.

## 비목표

- 기존 첨부(사용자→서버 업로드) 흐름 변경 없음 — 이번 작업은 반대 방향(서버가
  만든 파일→폰) 전용.
- 이미지 외 파일 타입(PDF, DXF 등) 렌더링은 범위 밖 — 나중에 필요해지면 같은
  구조 위에 확장.
- 과거에 이미 만들어진 `mcp-comfyui-bridge/generated/` 안의 파일 마이그레이션
  없음 — 재생성 가능하므로 버림.

## 아키텍처

새 컴포넌트 없음. 기존 `upload-server`에 다운로드 라우트 하나를 추가하고,
`comfyui-bridge`의 출력 위치를 그 서버가 서빙하는 디렉터리 밑으로 옮긴다.
Android 쪽은 `MEDIA:` 태그를 파싱해서 그 라우트로 바이트를 받아와 렌더링한다.

```
[comfyui-bridge]  generate_image()
      │  PNG를 upload-server/generated/comfyui/ 에 저장
      ▼
[에이전트]  응답에 MEDIA:C:\...\upload-server\generated\comfyui\<file>.png 포함
      │  (/v1/runs SSE로 앱에 전달, 그대로 텍스트)
      ▼
[Android]  RunCompleted 시점에 최종 텍스트에서 MEDIA: 줄 파싱
      │  generated/ 하위 형식이면: GET /generated/{tool}/{filename} 요청
      ▼
[upload-server]  Bearer 인증 + 경로 검증 후 바이트 응답
      ▼
[Android]  버블에서 원본 텍스트 줄 숨기고 그 자리에 이미지 렌더링
```

## 컴포넌트별 변경

### 1. 저장 위치 통일 (컨벤션 확정)

생성기 산출물은 전부 `upload-server/generated/<tool>/`에 저장한다
(`upload-server`가 이미 열려있는 포트/방화벽 규칙/Bearer 인증을 그대로 재사용
— 새 서버를 안 띄워도 됨).

- `comfyui_bridge/config.py`의 `OUTPUT_DIR` 기본값을
  `<repo-root>/upload-server/generated/comfyui/`로 변경
  (`COMFYUI_BRIDGE_OUTPUT_DIR` env var로 계속 오버라이드 가능).
- `mcp-comfyui-bridge/generated/`는 더 이상 안 씀.
- 나중에 acad/3dsmax/sketchup/vray MCP를 만들 때도 각자
  `upload-server/generated/<tool>/`에 저장하는 동일 패턴을 따른다 —
  지금은 컨벤션만 정하고 그 코드는 안 만든다(YAGNI).

### 2. upload-server: 다운로드 라우트

- `GET /generated/{tool}/{filename}` 신규 (`server.py`의 `handle_download`).
- 인증: 기존 `UPLOAD_SERVER_API_KEY` Bearer 토큰 재사용(별도 키 없음).
- 경로 검증 (`storage.py`의 `resolve_generated_path`):
  1. `tool`/`filename` 각각 기존 `sanitize_filename`으로 정리
     (`..` 등 디렉터리 구성요소는 `Path(...).name`이 빈 문자열이 되어
     이미 `"file"`로 치환됨 — 기존 로직 재사용).
  2. `generated_dir / safe_tool / safe_name`을 `.resolve()`한 뒤
     `generated_dir.resolve()` 하위인지 `is_relative_to()`로 재검증
     (defense-in-depth — sanitize만으로 막히지 않는 경로가 나중에 생겨도
     여기서 한 번 더 막는다).
  3. 검증 실패 시 403, 파일 없으면 404.
- `GENERATED_ROOT` = 새 env var `UPLOAD_SERVER_GENERATED_DIR` (기본 `./generated`).
- `Content-Type`은 `mimetypes.guess_type(filename)` 기반 추정.

### 3. Android: 파싱 + 다운로드 + 렌더링

- `ChatReducer.applyEvent`의 `RunEvent.RunCompleted` 처리 시점(스트리밍 중이
  아니라 최종 텍스트가 확정된 시점 — 승인된 방향)에 텍스트를 줄 단위로
  스캔한다.
  - `MEDIA:<path>` 형태이면서 `path`에 `/generated/<tool>/<filename>`
    (구분자는 `\`/`/` 둘 다 허용, 대소문자 무관) 패턴이 있으면: 그 줄을
    텍스트에서 제거하고, `ChatMedia(tool, filename, status = Loading)`을
    턴에 추가한다.
  - 패턴에 안 맞는 `MEDIA:` 태그(화이트리스트 밖 경로)는 손대지 않고 원본
    텍스트 그대로 둔다 — 안 보이던 게 갑자기 사라지면 더 헷갈리므로, 지원
    안 되는 형식은 있는 그대로 노출한다.
- `ChatConversationState`가 `RunCompleted` 이벤트를 받으면, 새로 생긴
  `Loading` 상태 미디어마다 코루틴을 띄워 `upload-server`의
  `GET /generated/{tool}/{filename}`을 호출한다(`FileUploadClient`에
  `downloadGenerated` 메서드 추가 — 기존 `upload()`와 대칭 구조).
  결과에 따라 `ChatReducer.applyMediaStatus`로 해당 미디어 항목만
  `Loaded(bytes)` 또는 `Failed(message)`로 갱신한다.
- 렌더링(`ChatMessageList.kt`의 `AssistantBubble`): 턴의 `media` 목록을
  순회하며 `Loading`이면 스피너, `Loaded`면 `BitmapFactory.decodeByteArray`로
  디코드해 `Image`로 표시(기존 첨부 썸네일 디코드와 같은 방식, 별도
  라이브러리 없음), `Failed`면 짧은 에러 텍스트.

## 데이터 흐름 / 에러 처리

- 다운로드 실패(네트워크 끊김, 404, 403 등): 채팅 버블에 "이미지를 불러올 수
  없습니다: ..." 표시, 앱은 안 죽음. 재시도 UI는 범위 밖(YAGNI) — 필요해지면
  나중에 추가.
- 스트리밍 중(델타 단계)엔 `MEDIA:` 파싱을 아예 안 한다 — 완성 안 된 태그를
  섣불리 매칭해서 중복 다운로드하거나 태그가 반쯤 잘려 보이는 문제를 원천
  차단한다. 최종 텍스트 확정 후 1회만 스캔.

## 테스트

- `upload-server`: `handle_download` — 정상 다운로드 200, 없는 파일 404,
  경로순회 시도(`..` 포함 등) 403, 인증 실패 401.
- `comfyui_bridge`: `OUTPUT_DIR` 기본값이 `upload-server/generated/comfyui`
  하위를 가리키는지.
- Android:
  - `ChatReducer`: `MEDIA:` 파싱(정상 `generated/` 경로 → 텍스트에서 제거 +
    `ChatMedia` 추가, 화이트리스트 밖 경로 → 텍스트 그대로 유지), `\`/`/`
    구분자 둘 다, `applyMediaStatus` 갱신.
  - `FileUploadClient.downloadGenerated`: 로컬 `HttpServer`로 정상/실패 응답
    유닛테스트(기존 `FileUploadClientTest.kt` 패턴 재사용).
  - `ChatConversationState`: `RunCompleted` 후 다운로드가 트리거되고 결과가
    반영되는지(파일업로드 관련 기존 테스트와 같은 fake 패턴).
