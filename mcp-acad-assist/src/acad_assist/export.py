"""acad_export — DWG/DXF/PDF 저장·내보내기. 승인 게이트를 거친다."""

from __future__ import annotations

from typing import Any, Literal, get_args

from . import projects
from .acad_constants import DEFAULT_DWG_VERSION, save_as_type
from .capture import capture_preview_image
from .com import AcadPort, ComWorker
from .confirm import ActionPreview, with_confirmation
from .projects import ensure_parent

ExportFormat = Literal["dwg", "dxf", "pdf"]
EXPORT_FORMATS: tuple[str, ...] = get_args(ExportFormat)

_PDF_PLOTTER = "DWG To PDF.pc3"


class ExportError(ValueError):
    """요청한 내보내기 포맷이 성립하지 않거나 내보내기 자체가 실패했을 때."""


def acad_export(
    worker: ComWorker,
    output_path: str,
    fmt: ExportFormat,
    confirm: bool = False,
    *,
    version: str = DEFAULT_DWG_VERSION,
    project: str | None = None,
    include_preview_image: bool = False,
) -> dict[str, Any]:
    """DWG/DXF를 지정 세대로, PDF를 플롯으로 내보낸다.

    Args:
        version: AutoCAD 파일 포맷 세대(`"2018"` 등). `AcSaveAsType` 상수를 이
            세대 이름으로 찾는다 — 숫자를 직접 받지 않는다. 값을 지어내지 않기
            위해서다(계획 §G): 릴리스마다 값이 바뀌고, 검증된 세대는 지금 2018뿐.
        project: 주어지면 성공한 내보내기를 `<project>/meta.json`의 `cad` 단계
            산출물로 등록한다(`projects.register_artifact`). 파이프라인 밖에서
            단독으로 쓸 때는 생략하면 된다 — 하위 호환.
        include_preview_image: True면 미리보기(confirm=False)에 현재 뷰의 PNG를
            함께 실어 폰 승인 다이얼로그에 보여준다. 기본은 False다 — 미리보기마다
            AutoCAD 에 실제 캡처를 한 번씩 더 시키는 비용이 있고, 이 개발 PC에는
            그 비용이 실제로 얼마인지 실측할 방법이 없어서 기본값을 안전한 쪽
            (끄기)으로 잡았다. 캡처 자체가 실패해도 미리보기는 정상 반환된다
            (`capture_preview_image`가 예외를 삼킨다).

    DXF는 예전엔 `NotImplementedError`였다. `AcSaveAsType`을 하드코딩하지 않고
    런타임 타입 라이브러리에서 조회 → 실패 시 검증된 폴백(2018 세대만)을 쓰는
    `acad_constants.save_as_type`이 생기면서 실제로 동작한다.
    """
    if fmt not in EXPORT_FORMATS:
        # 예전 구현은 알 수 없는 fmt 가 if/elif 를 그냥 통과해 "성공"으로 보고했다.
        # 미리보기 단계에서부터 막아야 승인 UI 에 이상한 요청이 뜨지 않는다.
        raise ExportError(
            f"지원하지 않는 내보내기 포맷: {fmt!r}. 가능한 값: {', '.join(EXPORT_FORMATS)}"
        )

    def _preview() -> ActionPreview:
        image = capture_preview_image(worker) if include_preview_image else None
        return ActionPreview(
            summary=f"export {fmt} -> {output_path}",
            affected_count=1,
            preview_image=image,
        )

    def _execute() -> dict[str, Any]:
        # 디렉터리 생성은 여기(실행이 확정된 뒤)에서만 한다 — confirm=False 인
        # 미리보기는 "실행하지 않는다"는 계약을 파일시스템 변경에도 지킨다.
        output = ensure_parent(output_path)

        def _run(port: AcadPort) -> dict[str, Any]:
            doc = port.active_document()
            if fmt == "pdf":
                ok = doc.Plot.PlotToFile(str(output), _PDF_PLOTTER)
                if ok is False:
                    raise ExportError(
                        f"PDF 내보내기가 실패를 보고했습니다 (path={output}). "
                        "'DWG To PDF.pc3' 플로터가 설치돼 있는지 확인하세요."
                    )
            else:
                filetype = save_as_type(fmt, version)
                doc.SaveAs(str(output), filetype)
            return {"confirm_required": False, "path": str(output), "format": fmt}

        result = worker.call(_run)
        if project is not None:
            projects.register_artifact(project, "cad", fmt, result["path"])
        return result

    result = with_confirmation(confirm, _preview, _execute)
    return result.to_dict() if isinstance(result, ActionPreview) else result
