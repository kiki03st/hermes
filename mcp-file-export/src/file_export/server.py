"""file-export MCP stdio 서버. `export_file` 하나만 노출한다 — 로컬 디스크에 텍스트
파일 하나 쓰는 것뿐이라 터미널/코드실행/외부 API 전혀 필요 없다."""

from __future__ import annotations

import sys
from pathlib import Path

from mcp.server.mcpserver import MCPServer

from .config import OUTPUT_DIR
from .storage import save_text

mcp = MCPServer("file-export")


@mcp.tool()
def save_document_for_user_to_view_on_phone(filename: str, content: str) -> dict[str, str]:
    """사용자가 "정리해줘", "~파일로 만들어줘", "리포트로 만들어줘", "요약해줘",
    "가이드로 만들어줘", "md로/문서로 저장해줘" 같은 요청을 할 때 쓸 수 있는 도구.

    **`write_file`도 이제 똑같이 잘 된다** — 이 서버와 별개로 `file-redirect`
    플러그인이 홈 디렉터리 트리 밑에 쓰는 문서 파일을 자동으로 폰이 받을 수 있는
    위치로 리다이렉트하고 MEDIA 태그도 자동으로 붙여준다(결정론적으로 동작, 실측
    확인 2026-08-30). 예전엔 `write_file`로 쓴 문서를 사용자가 영영 못 받는 버그가
    있어서(2026-08-29) 이 도구를 강제했었지만, 지금은 그 버그가 다른 방식(플러그인)
    으로 고쳐졌다 — 둘 중 뭘 써도 결과는 같다. 이 도구는 그냥 남겨둔 대안일 뿐,
    반드시 이걸 써야 하는 건 아니다.

    [filename]은 확장자 포함(예: `"프롬프트_가이드.md"`). [content]는 파일 전체 내용
    (UTF-8 텍스트) — 이 도구가 알아서 그 경로에 그대로 저장한다.

    이 도구를 직접 쓸 경우: 응답 텍스트에 `MEDIA:<이 도구가 돌려준 path 그대로>` 줄을
    포함해야 폰 앱이 다운로드 버튼과 미리보기를 띄운다(이 도구는 `write_file`과 달리
    자동으로 붙여주지 않는다 — MEDIA 태그 자동 삽입은 `file-redirect` 플러그인 쪽
    기능이라 이 도구를 거치면 안 탄다).
    """
    output_dir = Path(OUTPUT_DIR)
    target = save_text(output_dir, filename, content)
    return {"path": str(target)}


def main() -> None:
    # MCP stdio 프로토콜은 표준입출력으로 JSON을 주고받는다 — 이 Windows PC의 기본
    # 콘솔 인코딩(cp949)은 한글 텍스트를 못 담아 UnicodeEncodeError를 낼 수 있다
    # (comfyui_bridge/server.py에서 실측 확인된 것과 같은 문제). stdio는 항상 UTF-8이어야
    # 하는 프로토콜이라 명시적으로 강제한다.
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    mcp.run()


if __name__ == "__main__":
    main()
