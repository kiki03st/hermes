"""acad_capture / acad_export — 캡처·내보내기, meta.json 접합."""

from __future__ import annotations

import base64

import pytest

from acad_assist import projects
from acad_assist.acad_constants import ConstantUnavailable
from acad_assist.capture import (
    CaptureError,
    acad_capture,
    capture_preview_image,
    set_plot_config,
)
from acad_assist.export import EXPORT_FORMATS, ExportError, acad_export

# ------------------------------------------------------------------ capture


def test_capture_calls_plot_to_file_with_png_plotter(worker, fake_port, tmp_path):
    target = tmp_path / "view.png"

    result = acad_capture(worker, output_path=str(target))

    assert result["path"] == str(target)
    assert fake_port.document.Plot.calls == [(str(target), "PublishToWeb PNG.pc3")]


def test_capture_returns_base64_image_bytes(worker, fake_port, tmp_path):
    """예전 구현은 path 만 돌려줘서 에이전트가 결과를 볼 수 없었다."""
    target = tmp_path / "view.png"
    fake_port.document.Plot.file_bytes = b"fake-png-bytes"

    result = acad_capture(worker, output_path=str(target))

    assert result["bytes"] == len(b"fake-png-bytes")
    assert base64.b64decode(result["image_base64"]) == b"fake-png-bytes"


def test_capture_creates_missing_parent_directory(worker, fake_port, tmp_path):
    target = tmp_path / "nested" / "deep" / "view.png"

    acad_capture(worker, output_path=str(target))

    assert target.parent.is_dir()
    assert target.is_file()


def test_capture_zooms_extents_before_plotting(worker, fake_port, tmp_path):
    """동결 레이어·화면 밖 엔티티 때문에 '비어 보이는' 캡처가 되는 걸 막는다."""
    target = tmp_path / "view.png"

    acad_capture(worker, output_path=str(target))

    assert fake_port.zoom_extents_count == 1


def test_capture_raises_when_plot_reports_failure(worker, fake_port, tmp_path):
    """PlotToFile 의 Boolean 반환값이 예전엔 완전히 무시됐다."""
    fake_port.document.Plot.result = False
    target = tmp_path / "view.png"

    with pytest.raises(CaptureError, match="PlotToFile"):
        acad_capture(worker, output_path=str(target))


def test_capture_raises_when_success_reported_but_file_missing(worker, fake_port, tmp_path):
    fake_port.document.Plot.write_file_on_success = False
    target = tmp_path / "view.png"

    with pytest.raises(CaptureError, match="생기지 않았습니다"):
        acad_capture(worker, output_path=str(target))


def test_capture_applies_optional_plot_config(worker, fake_port, tmp_path):
    target = tmp_path / "view.png"

    acad_capture(worker, output_path=str(target), plot_config="MyPlotter.pc3")

    assert fake_port.document.ActiveLayout.ConfigName == "MyPlotter.pc3"


def test_set_plot_config_leaves_layout_untouched_when_not_given(worker, fake_port):
    layout = fake_port.document.ActiveLayout
    original = layout.ConfigName

    set_plot_config(fake_port.document, config_name=None)

    assert layout.ConfigName == original


# ------------------------------------------------------------ preview image


def test_capture_preview_image_returns_base64_png(worker, fake_port):
    fake_port.document.Plot.file_bytes = b"preview-bytes"

    image = capture_preview_image(worker)

    assert image is not None
    assert base64.b64decode(image) == b"preview-bytes"


def test_capture_preview_image_never_raises_on_failure(worker, fake_port):
    """미리보기 이미지는 편의 기능이다 — 실패해도 승인 자체를 막으면 안 된다."""
    fake_port.document.Plot.result = False

    assert capture_preview_image(worker) is None


def test_capture_preview_image_cleans_up_its_temp_file(worker, fake_port):
    import glob
    import tempfile

    before = set(glob.glob(f"{tempfile.gettempdir()}/acad-preview-*"))
    capture_preview_image(worker)
    after = set(glob.glob(f"{tempfile.gettempdir()}/acad-preview-*"))

    assert after == before  # TemporaryDirectory 가 with 블록을 벗어나며 스스로 지운다


# ------------------------------------------------------------------- export


def test_export_dwg_without_confirm_previews_only(worker, fake_port, tmp_path):
    target = tmp_path / "plan.dwg"

    result = acad_export(worker, output_path=str(target), fmt="dwg", confirm=False)

    assert result["confirm_required"] is True
    assert fake_port.document.saveas_calls == []


def test_export_preview_does_not_touch_the_filesystem(worker, fake_port, tmp_path):
    """confirm=False 는 '실행하지 않는다'는 계약이 파일시스템에도 적용돼야 한다."""
    target = tmp_path / "nested" / "plan.dwg"

    acad_export(worker, output_path=str(target), fmt="dwg", confirm=False)

    assert not target.parent.exists()


def test_export_dwg_with_confirm_saves_with_version_constant(worker, fake_port, tmp_path):
    target = tmp_path / "plan.dwg"

    result = acad_export(worker, output_path=str(target), fmt="dwg", confirm=True)

    assert result["confirm_required"] is False
    assert fake_port.document.saveas_calls == [str(target)]
    _, rest = fake_port.document.saveas_details[0]
    assert rest == (64,)  # ac2018_dwg, 검증된 폴백 (계획 §G)


def test_export_dwg_creates_missing_parent_directory(worker, fake_port, tmp_path):
    target = tmp_path / "nested" / "deep" / "plan.dwg"

    acad_export(worker, output_path=str(target), fmt="dwg", confirm=True)

    assert target.parent.is_dir()


def test_export_dxf_now_implemented_with_verified_constant(worker, fake_port, tmp_path):
    """예전엔 NotImplementedError 였다 — 런타임 조회 + 검증된 폴백으로 실제 동작한다."""
    target = tmp_path / "plan.dxf"

    result = acad_export(worker, output_path=str(target), fmt="dxf", confirm=True)

    assert result["format"] == "dxf"
    _, rest = fake_port.document.saveas_details[0]
    assert rest == (65,)  # ac2018_dxf


def test_export_pdf_with_confirm_plots(worker, fake_port, tmp_path):
    target = tmp_path / "plan.pdf"

    acad_export(worker, output_path=str(target), fmt="pdf", confirm=True)

    assert fake_port.document.Plot.calls == [(str(target), "DWG To PDF.pc3")]


def test_export_pdf_raises_when_plot_reports_failure(worker, fake_port, tmp_path):
    fake_port.document.Plot.result = False
    target = tmp_path / "plan.pdf"

    with pytest.raises(ExportError, match="PDF"):
        acad_export(worker, output_path=str(target), fmt="pdf", confirm=True)


def test_export_rejects_unknown_format_before_confirm(worker, fake_port, tmp_path):
    """예전 구현은 알 수 없는 fmt 가 if/elif 를 그냥 통과해 성공으로 보고했다."""
    with pytest.raises(ExportError, match="svg"):
        acad_export(worker, output_path=str(tmp_path / "x.svg"), fmt="svg", confirm=False)


def test_export_formats_are_dwg_dxf_pdf():
    assert set(EXPORT_FORMATS) == {"dwg", "dxf", "pdf"}


def test_export_unverified_version_refuses_to_guess(worker, fake_port, tmp_path):
    """검증 안 된 세대의 상수를 지어내지 않는다 (계획 §G)."""
    target = tmp_path / "plan.dwg"

    with pytest.raises(ConstantUnavailable):
        acad_export(worker, output_path=str(target), fmt="dwg", confirm=True, version="2013")


def test_export_registers_artifact_in_meta_json_when_project_given(
    worker, fake_port, tmp_path, monkeypatch
):
    """루트는 환경변수(HERMES_CAD_ROOT)가 유일한 설정 지점이다 — acad_export 에
    별도 root 인자를 두면 값의 출처가 두 개로 갈린다. 테스트도 실제 배포와 같은
    방식(환경변수)으로 루트를 주입한다."""
    root = tmp_path / "projects"
    monkeypatch.setenv(projects.ROOT_ENV, str(root))
    projects.project_init("room01")
    target = root / "room01" / "01-cad" / "plan.dwg"

    acad_export(worker, output_path=str(target), fmt="dwg", confirm=True, project="room01")

    meta = projects.meta_read("room01")
    (entry,) = meta["artifacts"]
    assert entry["stage"] == "cad"
    assert entry["kind"] == "dwg"
    assert entry["path"] == str(target)
    assert meta["stage"] == "cad"


def test_export_does_not_register_artifact_on_preview(
    worker, fake_port, tmp_path, monkeypatch
):
    root = tmp_path / "projects"
    monkeypatch.setenv(projects.ROOT_ENV, str(root))
    projects.project_init("room01")
    target = root / "room01" / "01-cad" / "plan.dwg"

    acad_export(worker, output_path=str(target), fmt="dwg", confirm=False, project="room01")

    meta = projects.meta_read("room01")
    assert meta["artifacts"] == []


def test_export_without_project_does_not_touch_meta_json(worker, fake_port, tmp_path):
    """project 를 안 주면 파이프라인과 무관하게 그냥 저장만 한다 — 하위 호환."""
    target = tmp_path / "plan.dwg"

    result = acad_export(worker, output_path=str(target), fmt="dwg", confirm=True)

    assert "project" not in result


def test_export_preview_has_no_image_by_default(worker, fake_port, tmp_path):
    """미리보기마다 AutoCAD 캡처를 추가로 시키는 비용을 기본으로는 안 문다."""
    target = tmp_path / "plan.dwg"

    result = acad_export(worker, output_path=str(target), fmt="dwg", confirm=False)

    assert result["preview_image"] is None
    assert fake_port.document.Plot.calls == []  # 캡처 자체가 안 불렸다


def test_export_preview_includes_image_when_opted_in(worker, fake_port, tmp_path):
    fake_port.document.Plot.file_bytes = b"preview-of-current-drawing"
    target = tmp_path / "plan.dwg"

    result = acad_export(
        worker, output_path=str(target), fmt="dwg", confirm=False, include_preview_image=True
    )

    assert base64.b64decode(result["preview_image"]) == b"preview-of-current-drawing"
    # 미리보기 캡처가 실제 export 대상 파일을 건드리지 않았는지 확인.
    assert fake_port.document.saveas_calls == []


def test_export_preview_image_failure_does_not_break_the_preview(worker, fake_port, tmp_path):
    """캡처가 실패해도 승인 자체를 막으면 안 된다 — preview_image 만 None."""
    fake_port.document.Plot.result = False
    target = tmp_path / "plan.dwg"

    result = acad_export(
        worker, output_path=str(target), fmt="dwg", confirm=False, include_preview_image=True
    )

    assert result["confirm_required"] is True
    assert result["preview_image"] is None
