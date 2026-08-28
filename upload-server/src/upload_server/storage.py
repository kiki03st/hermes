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
    """[inbox_dir]에 `<uuid8자리>_<새니타이즈된 원본이름>`으로 저장하고 그 경로를 돌려준다."""
    inbox_dir.mkdir(parents=True, exist_ok=True)
    safe_name = sanitize_filename(original_name)
    stored_name = f"{uuid.uuid4().hex[:8]}_{safe_name}"
    target = inbox_dir / stored_name
    target.write_bytes(data)
    return target
