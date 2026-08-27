"""acad_capture — 현재 뷰를 PNG로 저장한다. `PublishToWeb PNG.pc3` 플로터로 출력.

승인 불필요(읽기 성격 — 도면 자체를 바꾸지 않음).
"""

from __future__ import annotations

import base64
from typing import Any

from .com import AcadPort, ComWorker
from .projects import ensure_parent

_PNG_PLOTTER = "PublishToWeb PNG.pc3"


class CaptureError(RuntimeError):
    """`PlotToFile`이 실패를 보고했거나 결과 파일이 생기지 않았을 때."""


def set_plot_config(doc: Any, *, config_name: str | None = None) -> None:
    """PC3(플로터 구성)를 지정한다. 아무것도 안 주면 현재 레이아웃 설정을 그대로 둔다.

    조사 결과(계획 §G): 용지 크기·배율·플롯 스타일은 `PlotToFile`의 인자가 아니라
    `ActiveLayout.ConfigName`(PC3 파일명)과 그 PC3 안의 설정이 정한다. `capture.py`의
    예전 TODO는 "PlotToFile 인자를 확인해서 채운다"였는데 전제가 틀렸다 — 인자는
    2개뿐이고 나머지는 이 경로로 다룬다.

    실제로 어떤 PC3 이름·용지 크기를 쓸지는 대상 AutoCAD 설치의 플로터 구성에
    달려 있어서 여기서 값을 지어내지 않는다 — 호출자가 명시적으로 넘길 때만 바꾼다.
    """
    if config_name is not None:
        doc.ActiveLayout.ConfigName = config_name


def _zoom_extents(port: AcadPort) -> None:
    """캡처 전에 뷰를 도면 전체가 보이게 정리한다.

    동결된 레이어나 화면 밖 엔티티 때문에 "비어 보이는" 캡처가 나오는 걸 막는다.
    `ZoomExtents`가 없는 버전·환경이어도 캡처 자체는 계속돼야 하므로 실패는 조용히
    넘긴다 — 뷰가 이미 맞춰져 있을 수도 있다.
    """
    try:
        port.application().ZoomExtents()
    except Exception:
        pass


def acad_capture(
    worker: ComWorker,
    output_path: str,
    *,
    plot_config: str | None = None,
) -> dict[str, Any]:
    """현재 뷰를 PNG로 저장하고 base64로 함께 돌려준다.

    `path`만 돌려주던 예전 구현은 에이전트가 결과를 "보지" 못했다 — 폰 화면에
    표시하거나 다음 단계(SketchUp 임포트 확인 등)에 쓰려면 실제 이미지 바이트가
    필요하다. `PlotToFile`의 Boolean 반환값도 예전엔 무시됐다 — 실패해도
    "성공"으로 보고됐다.

    캡처는 승인 게이트가 없다(도면을 바꾸지 않는 읽기 동작이라 항상 실행한다).
    그래서 출력 디렉터리도 바로 만든다 — `export.py`처럼 confirm 을 기다릴 이유가
    없다.
    """
    output = ensure_parent(output_path)

    def _run(port: AcadPort) -> bool:
        doc = port.active_document()
        if plot_config is not None:
            set_plot_config(doc, config_name=plot_config)
        _zoom_extents(port)
        return doc.Plot.PlotToFile(str(output), _PNG_PLOTTER)

    ok = worker.call(_run)
    if ok is False:
        raise CaptureError(
            f"PlotToFile 이 실패를 보고했습니다 (path={output}). "
            "PNG 플로터('PublishToWeb PNG.pc3')가 이 AutoCAD 에 설치돼 있는지, "
            "출력 경로에 쓰기 권한이 있는지 확인하세요."
        )

    try:
        data = output.read_bytes()
    except FileNotFoundError:
        raise CaptureError(
            f"PlotToFile 이 성공을 보고했지만 파일이 생기지 않았습니다: {output}"
        ) from None

    return {
        "path": str(output),
        "bytes": len(data),
        "image_base64": base64.b64encode(data).decode("ascii"),
    }


def capture_preview_image(worker: ComWorker) -> str | None:
    """현재 뷰를 캡처해 base64 PNG로 돌려준다. **절대 예외를 던지지 않는다.**

    `confirm.py`의 `ActionPreview.preview_image`를 채우는 용도다 — 승인 미리보기에
    이미지가 없어도 승인 자체는 계속 진행돼야 한다. 이미지는 편의 기능이지 없으면
    안 되는 필수 정보가 아니므로, AutoCAD가 응답하지 않거나 플로터가 없어도 실패를
    삼키고 `None`을 돌려준다 — 호출자는 `preview_image`가 `None`일 수 있다고 가정하면
    된다.

    임시 파일에 썼다가 바로 지운다. 승인이 거부되면 이 이미지는 다시 볼 일이 없다.
    """
    import tempfile
    from pathlib import Path

    try:
        with tempfile.TemporaryDirectory(prefix="acad-preview-") as tmp:
            path = Path(tmp) / "preview.png"
            result = acad_capture(worker, str(path))
            return result.get("image_base64")
    except Exception:
        return None
