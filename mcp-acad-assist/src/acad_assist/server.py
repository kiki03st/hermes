"""acad-assist MCP stdio 서버. CAD-MCP(그리기 전담)가 채우지 못하는
조회·수정·캡처·내보내기·승인 게이트, 그리고 SketchUp/3ds Max 로 넘길 결정론적
Ruby/MAXScript 생성기를 제공한다 (PLAN.md 참고).

**세 그룹으로 나뉜다** (config.yaml.example 의 서버 등록 3개와 반드시 일치해야
한다 — `test_server.py`가 이 모듈의 튜플과 실제 등록된 도구를 대조한다):

- `READ_ONLY_TOOLS`(5) — AutoCAD COM 조회. `acad-read`(trust: full)로 등록.
- `WRITE_TOOLS`(3) — AutoCAD COM 쓰기. `acad-write`(trust: untrusted)로 등록.
- `PIPELINE_TOOLS`(10) — SketchUp/3ds Max Ruby·MAXScript **텍스트 생성기**.
  COM 을 안 건드리고 부작용이 전혀 없어(문자열만 돌려준다) 전부 읽기 전용이다.
  `cad-pipeline`(trust: full)로 등록. 생성된 텍스트는 Hermes가 `eval_ruby`
  (sketchup-mcp)/`execute_maxscript`(3dsmax-mcp)에 **한 글자도 고치지 않고**
  그대로 넘겨야 한다 — 그래야 골든 테스트가 고정한 계약이 실제 실행에도
  적용된다 (`hermes-config/skills/cad-pipeline.md`가 이 규칙을 명시한다).

전부 `ToolAnnotations(read_only_hint=True)`를 단다. 이 어노테이션은 Hermes
v0.20.6 의 MCP 트러스트 게이트가 "이 도구를 승인 없이 불러도 되는가"를
판단하는 유일한 근거다 — 그런데 그 버전은 SDK 객체에서 `readOnlyHint`
(camelCase)를 찾는 버그가 있어(파이썬 필드명은 `read_only_hint`) **live
discovery 경로에서는 이 값이 절대 안 읽힌다** (실측, 2026-08-28). 그래서
config.yaml.example은 이 서버 코드를 세 이름으로 **여러 번 등록**하고
`tools.include`로 나눈다 — 서버 단위 trust 는 그 버그와 무관하게 동작한다.
여기서 `ToolAnnotations`도 함께 다는 이유는 스펙상 맞고, Hermes가 나중에
버그를 고치거나 schema-cache 경로를 타면(그 경로는 camelCase 를 읽어 이미
지금도 동작한다) 이중 안전장치가 되기 때문이다.
"""

from __future__ import annotations

from typing import Any

from mcp.server.mcpserver import MCPServer
from mcp.types import ToolAnnotations

from . import maxscripts as _maxscripts
from . import sketchup_scripts as _sketchup
from .capture import acad_capture
from .com import ComWorker, Win32AcadPort
from .export import acad_export
from .modify import acad_layer, acad_modify
from .query import acad_get, acad_purge_check, acad_query, acad_status

#: config.yaml 의 acad-read/acad-write/cad-pipeline 등록과 반드시 일치해야 한다 —
#: server.py 테스트가 이 튜플들과 실제 등록된 도구의 어노테이션을 대조한다.
READ_ONLY_TOOLS: tuple[str, ...] = ("status", "query", "get", "purge_check", "capture")
WRITE_TOOLS: tuple[str, ...] = ("modify", "layer", "export")
PIPELINE_TOOLS: tuple[str, ...] = (
    "sketchup_import_dwg_script",
    "sketchup_extrude_walls_script",
    "sketchup_save_skp_script",
    "sketchup_export_fbx_script",
    "sketchup_capture_iso_script",
    "sketchup_unit_check_script",
    "max_import_skp_script",
    "max_setup_camera_script",
    "max_setup_lighting_script",
    "max_render_to_file_script",
)

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


# --------------------------------------------------------------------- #
# 파이프라인 스크립트 생성기 — SketchUp(Ruby)/3ds Max(MAXScript). 전부 부작용
# 없는 텍스트 생성이다 (COM/TCP/파이프를 안 건드린다) — trust:full 로 등록해도
# 안전하다. 실제 실행은 Hermes가 sketchup-mcp의 eval_ruby, 3dsmax-mcp의
# execute_maxscript 로 별도 호출해서 한다 (생성기 방식, PLAN.md Stage 4-3/5-2).
# --------------------------------------------------------------------- #


@mcp.tool(annotations=_READ_ONLY)
def sketchup_import_dwg_script(dwg_path: str) -> str:
    """SketchUp `eval_ruby`에 그대로 넣을 DWG 임포트 Ruby 스크립트를 만든다.

    반환된 텍스트를 한 글자도 고치지 말고 eval_ruby 인자로 넘길 것.
    """
    return _sketchup.import_dwg_script(dwg_path)


@mcp.tool(annotations=_READ_ONLY)
def sketchup_extrude_walls_script(height_mm: float, layer: str = "walls") -> str:
    """지정 레이어의 면을 height_mm만큼 밀어올리는 Ruby 스크립트를 만든다.

    mm 값을 인치로 미리 변환하지 않는다 — 생성된 스크립트가 SketchUp 자신의
    `Numeric#mm`을 써서 정확히 변환한다.
    """
    return _sketchup.extrude_walls_script(height_mm, layer=layer)


@mcp.tool(annotations=_READ_ONLY)
def sketchup_save_skp_script(output_path: str) -> str:
    """모델을 `.skp`로 저장하는 Ruby 스크립트를 만든다."""
    return _sketchup.save_skp_script(output_path)


@mcp.tool(annotations=_READ_ONLY)
def sketchup_export_fbx_script(output_path: str) -> str:
    """FBX로 내보내는 Ruby 스크립트를 만든다 (SketchUp **Pro** 전용 기능, 선택 산출물)."""
    return _sketchup.export_fbx_script(output_path)


@mcp.tool(annotations=_READ_ONLY)
def sketchup_capture_iso_script(output_path: str, width: int = 1920, height: int = 1080) -> str:
    """아이소메트릭 뷰로 전환해 PNG로 저장하는 Ruby 스크립트를 만든다."""
    return _sketchup.capture_iso_script(output_path, width=width, height=height)


@mcp.tool(annotations=_READ_ONLY)
def sketchup_unit_check_script() -> str:
    """모델의 길이 단위 옵션을 있는 그대로 보고하는 Ruby 스크립트를 만든다 (진단용)."""
    return _sketchup.unit_check_script()


@mcp.tool(annotations=_READ_ONLY)
def max_import_skp_script(skp_path: str) -> str:
    """3ds Max `execute_maxscript`에 그대로 넣을 `.skp` 임포트 스크립트를 만든다.

    FBX가 아니라 `.skp`를 직접 임포트하는 게 파이프라인 확정안이다.
    반환된 텍스트를 한 글자도 고치지 말고 execute_maxscript 인자로 넘길 것.
    """
    return _maxscripts.import_skp_script(skp_path)


@mcp.tool(annotations=_READ_ONLY)
def max_setup_camera_script(
    position: list[float],
    target: list[float],
    fov: float = 45.0,
    name: str = "Cam01",
) -> str:
    """타겟 카메라를 만들고 활성 뷰포트 카메라로 지정하는 MAXScript를 만든다.

    position/target 은 [x, y, z] (mm, Max System Unit 기준).
    """
    return _maxscripts.setup_camera_script(
        tuple(position), tuple(target), fov=fov, name=name
    )


@mcp.tool(annotations=_READ_ONLY)
def max_setup_lighting_script(sky_multiplier: float = 1.0) -> str:
    """기본 조명(Skylight + Omni 필 라이트 둘)을 만드는 MAXScript를 만든다."""
    return _maxscripts.setup_lighting_script(sky_multiplier=sky_multiplier)


@mcp.tool(annotations=_READ_ONLY)
def max_render_to_file_script(
    output_path: str,
    preset: str = "final",
    width: int | None = None,
    height: int | None = None,
) -> str:
    """V-Ray 프로덕션 렌더 → 파일 저장 MAXScript를 만든다.

    preset: "preview"(960x540) 또는 "final"(3840x2160). width/height를 주면
    프리셋 해상도를 덮어쓴다. `render_scene`/MAXScript `render()`는 Render
    Setup 설정을 무시하므로, 이 스크립트는 그 글로벌을 직접 설정하고
    `max quick render`로 트리거한다.
    """
    return _maxscripts.render_to_file_script(
        output_path, preset, width=width, height=height
    )


def main() -> None:
    mcp.run()


if __name__ == "__main__":
    main()
