"""file-redirect — 폰(api_server) 채널에서 write_file로 홈 디렉터리에 바로 떨군
문서 파일을 upload-server/generated/files/로 강제 리다이렉트하고, 최종 응답에
MEDIA: 태그를 자동으로 붙인다.

왜 필요한가: 모델이 사용자용 문서를 만들 때 write_file 대신 file-export의
save_document_for_user_to_view_on_phone를 쓰게 docstring/함수명/메모리로 4번
시도했지만 전부 실패했다(실측, 2026-08-29). 모델 설득 대신 결정론적 개입으로
바꾼다 — write_file이 호출된 위치와 무관하게, "홈 디렉터리에 바로 저장된 문서
확장자 파일"이라는 패턴만 보고 강제로 옮긴다.

cwd는 pre_tool_call 파이썬 플러그인 콜백 페이로드에 없다(셸 훅 전용 필드) —
그래서 "경로의 부모 디렉터리가 정확히 홈 디렉터리"만으로 판단한다. 실제 코딩
작업은 프로젝트 하위 디렉터리에 쓰지 홈 루트에 바로 안 쓰므로 이 휴리스틱으로
충분히 구분된다(오탐 시 그냥 파일 위치만 바뀔 뿐, 파괴적이지 않음).
"""

from __future__ import annotations

import os
import re
from pathlib import Path

_DOC_EXTENSIONS = {".md", ".txt", ".csv", ".json"}
# upload-server가 폰에 서빙하는 위치 — 리포마다/기기마다 클론 경로가 다를 수 있어
# env var로 오버라이드 가능하게 한다(comfyui_bridge/config.py, file_export/config.py와
# 같은 패턴). 기본값은 이 리포의 표준 설치 경로(C:\hermes).
_GENERATED_FILES_DIR = Path(
    os.environ.get("FILE_REDIRECT_GENERATED_DIR", r"C:\hermes\upload-server\generated\files"),
)
# Windows에서 실제 금지된 문자만 걸러낸다(upload-server/storage.py의 sanitize_filename과
# 같은 수정 — 예전 [^A-Za-z0-9._-] 방식은 한글 파일명을 "________.md"처럼 통째로
# 깨뜨렸다, 실측 버그 2026-08-29).
_UNSAFE_CHARS = re.compile(r'[<>:"/\\|?*\x00-\x1f]')

# pre_tool_call과 transform_llm_output 사이에 리다이렉트 사실을 넘겨주는 세션별
# 상태 — 모델의 응답 텍스트를 못 믿는다(실측 확인, 2026-08-29: 리다이렉트가 실제로
# 성공했는데도 모델이 자기가 원래 요청한 옛 경로를 그대로 사용자에게 말했다 — tool
# 결과의 resolved_path를 안 챙겨 봄). 그래서 정규식으로 텍스트에서 경로를 찾는 대신,
# "이 세션에서 방금 리다이렉트가 실제로 일어났는가"를 직접 기억해뒀다가 쓴다.
_last_redirect_by_session: dict[str, str] = {}


def _sanitize_filename(name: str) -> str:
    base = Path(name).name.strip()
    if not base:
        return "file.txt"
    return _UNSAFE_CHARS.sub("_", base)[:200]


def redirect_home_dir_writes(tool_name: str = "", args: dict | None = None, session_id: str = "", **kwargs):
    """pre_tool_call — write_file이 홈 디렉터리에 바로 문서를 쓰려 하면 경로를
    upload-server/generated/files/로 바꿔치기한다(modify 지시). 그 외엔 손 안 댐."""
    if tool_name != "write_file" or not args:
        return None

    raw_path = args.get("path")
    if not raw_path:
        return None

    target = Path(raw_path)
    try:
        home = Path.home()
    except Exception:
        return None

    if target.parent != home:
        return None
    if target.suffix.lower() not in _DOC_EXTENSIONS:
        return None

    _GENERATED_FILES_DIR.mkdir(parents=True, exist_ok=True)
    new_path = _GENERATED_FILES_DIR / _sanitize_filename(target.name)
    if session_id:
        _last_redirect_by_session[session_id] = str(new_path)
    return {"action": "modify", "args": {"path": str(new_path)}}


def inject_media_tag(response_text: str = "", session_id: str = "", **kwargs):
    """transform_llm_output — 이번 턴에 [redirect_home_dir_writes]가 실제로
    리다이렉트한 파일이 있으면 그 경로로 MEDIA: 태그를 붙인다. 모델의 응답 텍스트
    안에서 경로를 찾지 않는다 — 모델이 리다이렉트 사실 자체를 못 챙겨서 옛 경로를
    말할 수 있기 때문(위 주석 참고)."""
    path = _last_redirect_by_session.pop(session_id, None) if session_id else None
    if not path:
        return None
    if f"MEDIA:{path}" in (response_text or ""):
        return None
    return (response_text or "").rstrip() + f"\n\nMEDIA:{path}"


def register(ctx):
    ctx.register_hook("pre_tool_call", redirect_home_dir_writes)
    ctx.register_hook("transform_llm_output", inject_media_tag)
