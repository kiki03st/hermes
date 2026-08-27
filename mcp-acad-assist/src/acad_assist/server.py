"""acad-assist MCP stdio 서버. CAD-MCP(그리기 전담)가 채우지 못하는
조회·수정·캡처·내보내기·승인 게이트만 제공한다 (PLAN.md 참고).

**읽기 5 / 쓰기 3 으로 나뉜다** — `status`/`query`/`get`/`purge_check`/`capture`는
`ToolAnnotations(read_only_hint=True)`를 달고, `modify`/`layer`/`export`는 안 단다.

이 어노테이션은 Hermes v0.20.6 의 MCP 트러스트 게이트가 "이 도구를 승인 없이
불러도 되는가"를 판단하는 유일한 근거다 — 그런데 그 버전은 SDK 객체에서
`readOnlyHint`(camelCase)를 찾는 버그가 있어(파이썬 필드명은 `read_only_hint`)
**live discovery 경로에서는 이 값이 절대 안 읽힌다** (실측, 2026-08-28). 그래서
`hermes-config/config.yaml.example`은 이 서버 코드를 `acad-read`(trust: full)와
`acad-write`(trust: untrusted)로 **두 번 등록**하고 `tools.include`로 나눈다 —
서버 단위 trust 는 그 버그와 무관하게 동작한다. 여기서 `ToolAnnotations`도 함께
다는 이유는 스펙상 맞고, Hermes가 나중에 버그를 고치거나 schema-cache 경로를
타면(그 경로는 camelCase 를 읽어 이미 지금도 동작한다) 이중 안전장치가 되기
때문이다 — 둘 중 하나만으로는 이 버전에서 불충분하다.
"""

from __future__ import annotations

from typing import Any

from mcp.server.mcpserver import MCPServer
from mcp.types import ToolAnnotations

from .capture import acad_capture
from .com import ComWorker, Win32AcadPort
from .export import acad_export
from .modify import acad_layer, acad_modify
from .query import acad_get, acad_purge_check, acad_query, acad_status

#: config.yaml 의 acad-read/acad-write 분리와 반드시 일치해야 한다 —
#: server.py 테스트가 이 튜플과 실제 등록된 도구의 어노테이션을 대조한다.
READ_ONLY_TOOLS: tuple[str, ...] = ("status", "query", "get", "purge_check", "capture")
WRITE_TOOLS: tuple[str, ...] = ("modify", "layer", "export")

_READ_ONLY = ToolAnnotations(read_only_hint=True)

mcp = MCPServer("acad-assist")
_worker = ComWorker(Win32AcadPort)


@mcp.tool(annotations=_READ_ONLY)
def status() -> dict[str, Any]:
    """연결 상태, 열린 도면, 레이어 목록, 현재 단위를 조회한다."""
    return acad_status(_worker)


@mcp.tool(annotations=_READ_ONLY)
def query(layer: str | None = None, entity_type: str | None = None) -> list[dict[str, Any]]:
    """엔티티 목록 — 핸들·타입·레이어. layer/entity_type으로 필터링."""
    return acad_query(_worker, layer=layer, entity_type=entity_type)


@mcp.tool(annotations=_READ_ONLY)
def get(handle: str) -> dict[str, Any]:
    """핸들로 엔티티 상세 조회 — 타입별 지오메트리·색상·바운딩 박스까지."""
    return acad_get(_worker, handle=handle)


@mcp.tool(annotations=_READ_ONLY)
def purge_check() -> dict[str, Any]:
    """저장 전 점검 — 미저장 변경 여부, 잠긴/동결 레이어 목록."""
    return acad_purge_check(_worker)


@mcp.tool()
def modify(
    handles: list[str],
    operation: str,
    params: dict[str, Any] | None = None,
    confirm: bool = False,
) -> dict[str, Any]:
    """move/copy/rotate/scale/offset/erase. confirm=False면 미리보기만 반환하고
    실행하지 않는다 — 사용자 승인 후 confirm=True로 재호출한다.

    좌표는 [x, y] 또는 [x, y, z]. rotate 의 angle 은 기본 도(degree) —
    params 에 angle_unit="radians" 를 주면 라디안으로 받는다.
    """
    return acad_modify(_worker, handles=handles, operation=operation, params=params, confirm=confirm)


@mcp.tool()
def layer(
    action: str,
    name: str,
    params: dict[str, Any] | None = None,
    confirm: bool = False,
) -> dict[str, Any]:
    """레이어 생성·전환·색상·동결/해동·잠금/해제·삭제. confirm=False면 미리보기만 반환한다."""
    return acad_layer(_worker, action=action, name=name, params=params, confirm=confirm)


@mcp.tool(annotations=_READ_ONLY)
def capture(output_path: str, plot_config: str | None = None) -> dict[str, Any]:
    """현재 뷰를 PNG로 캡처해 base64 로 함께 돌려준다. 도면 자체는 바꾸지 않는다."""
    return acad_capture(_worker, output_path=output_path, plot_config=plot_config)


@mcp.tool()
def export(
    output_path: str,
    fmt: str,
    confirm: bool = False,
    version: str = "2018",
    project: str | None = None,
    include_preview_image: bool = False,
) -> dict[str, Any]:
    """DWG/DXF/PDF로 저장·내보내기. confirm=False면 미리보기만 반환한다.

    project 를 주면 성공한 내보내기를 `<project>/meta.json`의 `cad` 단계 산출물로
    등록한다 — SketchUp 단계(Stage 4)가 여기서 만든 `01-cad/plan.dwg`를 찾을 때
    이 기록을 읽는다.
    """
    return acad_export(
        _worker,
        output_path=output_path,
        fmt=fmt,
        confirm=confirm,
        version=version,
        project=project,
        include_preview_image=include_preview_image,
    )


def main() -> None:
    mcp.run()


if __name__ == "__main__":
    main()
