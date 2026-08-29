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
    "가이드로 만들어줘", "md로/문서로 저장해줘" 같은 요청을 하면 **무조건** 이 도구를
    써라 — `write_file`을 쓰지 마라. `write_file`은 임의 경로에 써서 폰 앱이 절대
    받아올 수 없는 곳에 저장되지만(사용자가 그 파일을 영영 못 받는다, 실측 확인된
    버그), 이 도구는 폰 앱이 다운로드/미리보기할 수 있는
    위치(`upload-server/generated/files/`)에 저장한다.

    [filename]은 확장자 포함(예: `"프롬프트_가이드.md"`). [content]는 파일 전체 내용
    (UTF-8 텍스트) — 이 도구가 알아서 그 경로에 그대로 저장한다.

    중요: 응답 텍스트에 반드시 `MEDIA:<이 도구가 돌려준 path 그대로>` 줄을 그대로
    포함해라(다른 문구로 바꿔쓰지 말 것) — 폰 앱이 정확히 이 태그를 파싱해서 다운로드
    버튼과 미리보기를 띄운다. 파일 경로를 그냥 텍스트로만 말하면 사용자는 그 파일을
    절대 받을 수 없다(실측 확인된 버그 패턴, 2026-08-29 — write_file로 만든 문서가
    바로 이 문제였다).
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
