from __future__ import annotations

import pytest

from acad_assist.capture import acad_capture
from acad_assist.export import acad_export


def test_capture_calls_plot_to_file_with_png_plotter(worker, fake_port):
    result = acad_capture(worker, output_path="/tmp/view.png")

    assert result == {"path": "/tmp/view.png"}
    assert fake_port.document.Plot.calls == [("/tmp/view.png", "PublishToWeb PNG.pc3")]


def test_export_dwg_without_confirm_previews_only(worker, fake_port):
    result = acad_export(worker, output_path="/tmp/plan.dwg", fmt="dwg", confirm=False)

    assert result["confirm_required"] is True
    assert fake_port.document.saveas_calls == []


def test_export_dwg_with_confirm_saves(worker, fake_port):
    result = acad_export(worker, output_path="/tmp/plan.dwg", fmt="dwg", confirm=True)

    assert result["confirm_required"] is False
    assert fake_port.document.saveas_calls == ["/tmp/plan.dwg"]


def test_export_pdf_with_confirm_plots(worker, fake_port):
    acad_export(worker, output_path="/tmp/plan.pdf", fmt="pdf", confirm=True)

    assert fake_port.document.Plot.calls == [("/tmp/plan.pdf", "DWG To PDF.pc3")]


def test_export_dxf_not_yet_implemented(worker, fake_port):
    with pytest.raises(NotImplementedError):
        acad_export(worker, output_path="/tmp/plan.dxf", fmt="dxf", confirm=True)
