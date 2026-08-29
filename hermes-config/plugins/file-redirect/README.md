# file-redirect

Hermes 플러그인 — 모델이 사용자용 문서(md/txt/csv/json)를 만들 때 `write_file`을
쓰든 `file-export`를 쓰든 상관없이, 폰 앱이 다운로드/미리보기할 수 있는 위치에
저장되도록 **강제**한다.

## 왜 필요한가

`write_file`(Hermes 내장, 범용)로 만든 문서는 임의 경로(보통 사용자 홈 디렉터리
바로 밑)에 저장돼서 폰 앱이 절대 받아올 수 없다. 전용 도구
`file-export`(`mcp-file-export/`)를 만들어서 대신 쓰게 유도해봤지만
docstring 강화, 함수명 노골적으로 변경, 메모리(MEMORY.md) 주입까지 4번
시도해도 모델이 계속 `write_file`을 골랐다(실측, 2026-08-29). 모델 설득을
포기하고 결정론적 개입으로 바꿨다:

1. **`pre_tool_call` 훅**(`redirect_home_dir_writes`) — `write_file`이
   홈 디렉터리에 바로 문서 확장자 파일을 쓰려 하면, 그 경로를
   `upload-server/generated/files/`로 조용히 바꿔치기한다(`modify` 지시).
   모델은 자기가 지정한 원래 경로를 쓴 줄 알지만 실제로는 리다이렉트된
   위치에 저장된다.
2. **`transform_llm_output` 훅**(`inject_media_tag`) — 최종 응답이 배달되기
   직전, 이번 턴에 리다이렉트가 실제로 일어났으면 `MEDIA:<리다이렉트된 경로>`
   줄을 응답에 자동으로 붙인다. 모델의 응답 텍스트 안에서 경로를 찾지
   않는다 — 실측 확인: 리다이렉트가 성공해도 모델은 자기가 원래 말한
   (틀린) 경로를 그대로 사용자에게 보고하는 경우가 있었다. 그래서 두 훅
   사이에 세션별 상태(`_last_redirect_by_session`)로 "진짜 무슨 일이
   있었는지"를 직접 넘긴다.

**중요**: `hermes sessions export`로 보는 세션 기록은 이 변환이 적용되기 *전*
DB 원본이라 `MEDIA:` 태그가 안 보인다 — 실제 `/v1/runs` SSE로 배달되는
내용에는 있다(실측 확인, 원문 SSE 스트림 직접 캡처해서 확인함).

## 판별 휴리스틱

`pre_tool_call` 파이썬 플러그인 콜백 페이로드엔 `cwd`가 없다(셸 훅 전용 필드라
플러그인 훅에는 안 옴, 소스 확인). 그래서 "경로의 부모 디렉터리가 정확히 홈
디렉터리"만으로 판단한다 — 실제 코딩 작업(CLI)은 프로젝트 하위 디렉터리에
쓰지 홈 루트에 바로 안 쓰므로 이 정도로 충분히 구분된다. 오탐이 나도 파괴적이지
않다(그냥 파일 위치만 바뀔 뿐).

## 설치

```powershell
Copy-Item -Recurse "hermes-config\plugins\file-redirect" "$env:LOCALAPPDATA\hermes\plugins\file-redirect"
hermes plugins enable file-redirect
hermes gateway restart
```

`plugins.enabled: [file-redirect]`가 `config.yaml`에 등록됐는지 `hermes plugins`로
확인.

## 환경변수 (선택)

| 변수 | 기본값 |
|---|---|
| `FILE_REDIRECT_GENERATED_DIR` | `C:\hermes\upload-server\generated\files` |

## 검증

폰(또는 `/v1/runs` 직접 호출)에서 "~ 정리해서 md 파일로 만들어줘" 요청 →

1. `upload-server/generated/files/`에 실제 파일 생기는지 확인
2. 실제 배달되는 SSE에 `MEDIA:` 태그 있는지 확인(세션 export론 안 보임, 위 참고)
