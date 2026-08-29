"""문서 파일 저장 — 경로 traversal 방지용 이름 새니타이즈(upload-server/storage.py의
sanitize_filename과 같은 로직)."""

from __future__ import annotations

import re
from pathlib import Path

_UNSAFE_CHARS = re.compile(r"[^A-Za-z0-9._-]")


def sanitize_filename(name: str) -> str:
    """디렉터리 구성요소를 전부 떼어내고(경로 traversal 방지), 영숫자/`.`/`_`/`-`
    외 문자는 `_`로 바꾼다. 빈 이름은 `file.txt`로 대체한다."""
    base = Path(name).name.strip()
    if not base:
        return "file.txt"
    return _UNSAFE_CHARS.sub("_", base)[:200]


def save_text(output_dir: Path, filename: str, content: str) -> Path:
    """[output_dir]에 `<새니타이즈된 이름>`으로 UTF-8 텍스트를 저장하고 절대경로를
    돌려준다. `save_upload`(업로드 파일)와 다르게 uuid 접두어를 안 붙인다 — 리포트류는
    같은 이름으로 다시 만들면 덮어쓰는 게 자연스럽다(설계 결정, YAGNI).

    항상 절대경로를 돌려준다 — [output_dir]가 상대경로로 넘어와도 마찬가지다. 이 경로가
    응답 텍스트를 거쳐 별개 프로세스(에이전트)로 전달되는데, cwd가 다른 프로세스라
    상대경로를 그대로 주면 엉뚱한 곳을 찾는다(`upload-server/storage.py`의 실측 버그,
    2026-08-29, 여기서도 같은 방어를 반복한다)."""
    output_dir.mkdir(parents=True, exist_ok=True)
    target = output_dir / sanitize_filename(filename)
    target.write_text(content, encoding="utf-8")
    return target.resolve()
