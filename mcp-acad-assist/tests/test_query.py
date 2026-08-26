from __future__ import annotations

from acad_assist.query import acad_get, acad_purge_check, acad_query, acad_status

from .fakes import FakeEntity


def test_acad_status_reports_document_layers_and_units(worker, fake_port):
    fake_port.document.Layers.Add("walls")

    result = acad_status(worker)

    assert result["connected"] is True
    assert result["document"] == "Drawing1.dwg"
    assert set(result["layers"]) == {"0", "walls"}
    assert result["units"] == 4


def test_acad_query_filters_by_layer_and_type(worker, fake_port):
    fake_port.document.add_entity(FakeEntity("A1", "AcDbLine", "walls"))
    fake_port.document.add_entity(FakeEntity("A2", "AcDbCircle", "walls"))
    fake_port.document.add_entity(FakeEntity("A3", "AcDbLine", "dims"))

    all_walls = acad_query(worker, layer="walls")
    assert {e["handle"] for e in all_walls} == {"A1", "A2"}

    lines_only = acad_query(worker, entity_type="AcDbLine")
    assert {e["handle"] for e in lines_only} == {"A1", "A3"}

    both = acad_query(worker, layer="walls", entity_type="AcDbLine")
    assert [e["handle"] for e in both] == ["A1"]


def test_acad_get_returns_entity_by_handle(worker, fake_port):
    fake_port.document.add_entity(FakeEntity("A1", "AcDbLine", "walls"))

    result = acad_get(worker, handle="A1")

    assert result == {"handle": "A1", "type": "AcDbLine", "layer": "walls"}


def test_acad_purge_check_reports_unsaved_and_locked_layers(worker, fake_port):
    fake_port.document.Saved = False
    locked = fake_port.document.Layers.Add("locked-layer")
    locked.Lock = True

    result = acad_purge_check(worker)

    assert result["saved"] is False
    assert "locked-layer" in result["locked_layers"]
    assert "0" not in result["locked_layers"]
