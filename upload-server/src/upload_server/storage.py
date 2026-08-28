"""업로드 파일 저장 — 경로 traversal 방지용 이름 새니타이즈 + uuid 접두어로 충돌 방지."""

from __future__ import annotations

import re
import uuid
from pathlib import Path

_UNSAFE_CHARS = re.compile(r"[^A-Za-z0-9._-]")


def sanitize_filename(name: str) -> str:
    """디렉터리 구성요소를 전부 떼어내고(경로 traversal 방지), 영숫자/`.`/`_`/`-`
    외 문자는 `_`로 바꾼다. 빈 이름은 `file`로 대체한다."""
    base = Path(name).name.strip()
    if not base:
        return "file"
    return _UNSAFE_CHARS.sub("_", base)[:200]


def save_upload(inbox_dir: Path, original_name: str, data: bytes) -> Path:
    """[inbox_dir]에 `<uuid8자리>_<새니타이즈된 원본이름>`으로 저장하고 그 경로를 돌려준다.

    항상 절대경로를 돌려준다 — [inbox_dir]가 상대경로로 넘어와도 마찬가지다. 이 경로는
    HTTP 응답으로 클라이언트(폰 앱)를 거쳐 게이트웨이 에이전트 프로세스에 텍스트로
    전달되는데, 에이전트는 이 서버와 cwd가 다른 완전히 별개 프로세스다 — 상대경로를
    그대로 돌려주면 에이전트 자신의 cwd 기준으로 해석되어 엉뚱한 곳을 찾는다(실측,
    2026-08-29: `UPLOAD_SERVER_INBOX_DIR`을 상대경로로 띄웠더니 에이전트가
    "media file not found" 에러를 냈다 — 에이전트 cwd는 `C:\\Users\\ksy`인데 파일은
    이 서버의 cwd 기준 상대경로에 있었다)."""
    inbox_dir.mkdir(parents=True, exist_ok=True)
    safe_name = sanitize_filename(original_name)
    stored_name = f"{uuid.uuid4().hex[:8]}_{safe_name}"
    target = inbox_dir / stored_name
    target.write_bytes(data)
    return target.resolve()
