"""폰에 보여줄 문서(리포트/정리/요약)를 만드는 좁은 MCP 서버.

`export_file` 함수 하나만 노출한다 — `write_file`(Hermes 내장, 범용)을 대신해서
`upload-server`가 폰에 서빙하는 위치(`upload-server/generated/files/`)에 저장한다.
`comfyui-bridge`와 같은 패턴(설계 문서:
`docs/superpowers/specs/2026-08-29-file-export-design.md`)."""

__version__ = "0.1.0"
