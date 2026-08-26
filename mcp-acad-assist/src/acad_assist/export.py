"""acad_export — DWG/DXF/PDF 저장·내보내기. 승인 게이트를 거친다."""

from __future__ import annotations

from typing import Any, Literal

from .com import AcadPort, ComWorker
from .confirm import ActionPreview, with_confirmation

ExportFormat = Literal["dwg", "dxf", "pdf"]

_PDF_PLOTTER = "DWG To PDF.pc3"


def acad_export(
    worker: ComWorker,
    output_path: str,
    fmt: ExportFormat,
    confirm: bool = False,
) -> dict[str, Any]:
    def _preview() -> ActionPreview:
        return ActionPreview(summary=f"export {fmt} -> {output_path}", affected_count=1)

    def _execute() -> dict[str, Any]:
        def _run(port: AcadPort) -> dict[str, Any]:
            doc = port.active_document()
            if fmt == "dwg":
                doc.SaveAs(output_path)
            elif fmt == "pdf":
                doc.Plot.PlotToFile(output_path, _PDF_PLOTTER)
            elif fmt == "dxf":
                # TODO(Stage 3): AcSaveAsType의 정확한 DXF 버전 상수를 실 AutoCAD에서
                # 확인 후 채운다 (예: acR2018_DXF). 지금은 스켈레톤이라 미구현.
                raise NotImplementedError("DXF export constant pending Stage 3 verification")
            return {"confirm_required": False, "path": output_path, "format": fmt}

        return worker.call(_run)

    result = with_confirmation(confirm, _preview, _execute)
    return result.to_dict() if isinstance(result, ActionPreview) else result
