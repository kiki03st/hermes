"""acad-assist MCP stdio 서버. CAD-MCP(그리기 전담)가 채우지 못하는
조회·수정·캡처·내보내기·승인 게이트만 제공한다 (PLAN.md 참고)."""

from __future__ import annotations

from typing import Any

from mcp.server.mcpserver import MCPServer

from .capture import acad_capture
from .com import ComWorker, Win32AcadPort
from .export import acad_export
from .modify import acad_layer, acad_modify
from .query import acad_get, acad_purge_check, acad_query, acad_status

mcp = MCPServer("acad-assist")
_worker = ComWorker(Win32AcadPort)


@mcp.tool()
def status() -> dict[str, Any]:
    """연결 상태, 열린 도면, 레이어 목록, 현재 단위를 조회한다."""
    return acad_status(_worker)


@mcp.tool()
def query(layer: str | None = None, entity_type: str | None = None) -> list[dict[str, Any]]:
    """엔티티 목록 — 핸들·타입·레이어. layer/entity_type으로 필터링."""
    return acad_query(_worker, layer=layer, entity_type=entity_type)


@mcp.tool()
def get(handle: str) -> dict[str, Any]:
    """핸들로 엔티티 상세 조회."""
    return acad_get(_worker, handle=handle)


@mcp.tool()
def purge_check() -> dict[str, Any]:
    """저장 전 점검 — 미저장 변경 여부, 잠긴 레이어 목록."""
    return acad_purge_check(_worker)


@mcp.tool()
def modify(
    handles: list[str],
    operation: str,
    params: dict[str, Any] | None = None,
    confirm: bool = False,
) -> dict[str, Any]:
    """move/copy/rotate/scale/offset/erase. confirm=False면 미리보기만 반환하고
    실행하지 않는다 — 사용자 승인 후 confirm=True로 재호출한다."""
    return acad_modify(_worker, handles=handles, operation=operation, params=params, confirm=confirm)


@mcp.tool()
def layer(
    action: str,
    name: str,
    params: dict[str, Any] | None = None,
    confirm: bool = False,
) -> dict[str, Any]:
    """레이어 생성·전환·색상·동결. confirm=False면 미리보기만 반환한다."""
    return acad_layer(_worker, action=action, name=name, params=params, confirm=confirm)


@mcp.tool()
def capture(output_path: str) -> dict[str, Any]:
    """현재 뷰를 PNG로 캡처한다."""
    return acad_capture(_worker, output_path=output_path)


@mcp.tool()
def export(output_path: str, fmt: str, confirm: bool = False) -> dict[str, Any]:
    """DWG/DXF/PDF로 저장·내보내기. confirm=False면 미리보기만 반환한다."""
    return acad_export(_worker, output_path=output_path, fmt=fmt, confirm=confirm)


def main() -> None:
    mcp.run()


if __name__ == "__main__":
    main()
