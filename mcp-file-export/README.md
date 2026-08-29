# file-export

폰 앱에 보여줄 문서(리포트, 정리 노트, 요약, 가이드 등)를 만드는 좁은 MCP 서버.
`export_file` 함수 하나만 노출한다 — 로컬 디스크에 텍스트 파일 하나 쓰는 것뿐이라
터미널/코드실행/외부 API가 전혀 필요 없다.

`write_file`(Hermes 내장, 범용)과의 차이: `write_file`은 임의 경로에 저장해서 폰 앱이
절대 받아올 수 없는 곳에 남지만, 이 도구는 `upload-server`가 폰에 서빙하는 위치
(`upload-server/generated/files/`)에 저장한다 — `comfyui-bridge`와 같은 패턴(설계
문서: `docs/superpowers/specs/2026-08-29-file-export-design.md`).

## 설치

```bash
cd mcp-file-export
python -m pip install -e ".[dev]"
```

`hermes-config/config.yaml.example`의 `mcp_servers.file-export` 블록을
`%LOCALAPPDATA%\hermes\config.yaml`에 병합한 뒤 `hermes gateway restart`.

## 환경변수 (선택, 기본값 있음)

| 변수 | 기본값 |
|---|---|
| `FILE_EXPORT_OUTPUT_DIR` | `upload-server/generated/files/` |

## 테스트

```bash
python -m pytest -v
```

## 검증

```bash
hermes mcp list   # file-export가 뜨는지
```

폰에서 "정리해서 md 파일로 만들어줘" 요청 → `upload-server/generated/files/` 안에
실제 파일이 생기고, 응답에 `MEDIA:` 태그가 포함되는지 확인.
