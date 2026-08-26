"""acad_capture — 현재 뷰를 PNG로 저장한다. `PublishToWeb PNG.pc3` 플로터로 출력.

승인 불필요(읽기 성격 — 도면 자체를 바꾸지 않음).
"""

from __future__ import annotations

from typing import Any

from .com import AcadPort, ComWorker

_PNG_PLOTTER = "PublishToWeb PNG.pc3"


def acad_capture(worker: ComWorker, output_path: str) -> dict[str, Any]:
    def _run(port: AcadPort) -> dict[str, Any]:
        doc = port.active_document()
        # TODO(Stage 3): 실제 PlotToFile 인자(용지 크기·배율·플롯 스타일)는
        # 실 AutoCAD에서 확인 후 확정한다. 여기서는 인터페이스 골격만 고정.
        doc.Plot.PlotToFile(output_path, _PNG_PLOTTER)
        return {"path": output_path}

    return worker.call(_run)
