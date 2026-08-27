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
    """기본 3개 키는 계약이다 — 지오메트리·속성은 읽을 수 있었을 때만 추가된다."""
    fake_port.document.add_entity(FakeEntity("A1", "AcDbLine", "walls"))

    result = acad_get(worker, handle="A1")

    assert result["handle"] == "A1"
    assert result["type"] == "AcDbLine"
    assert result["layer"] == "walls"


def test_acad_get_returns_geometry_for_a_line(worker, fake_port):
    fake_port.document.add_entity(
        FakeEntity(
            "L1",
            "AcDbLine",
            "walls",
            geometry={
                "StartPoint": (0.0, 0.0, 0.0),
                "EndPoint": (3000.0, 0.0, 0.0),
                "Length": 3000.0,
                "Angle": 0.0,
            },
        )
    )

    result = acad_get(worker, handle="L1")

    assert result["geometry"]["StartPoint"] == [0.0, 0.0, 0.0]
    assert result["geometry"]["EndPoint"] == [3000.0, 0.0, 0.0]
    assert result["geometry"]["Length"] == 3000.0


def test_acad_get_returns_geometry_for_a_circle(worker, fake_port):
    fake_port.document.add_entity(
        FakeEntity(
            "C1",
            "AcDbCircle",
            "0",
            geometry={"Center": (100.0, 200.0, 0.0), "Radius": 50.0, "Area": 7853.98},
        )
    )

    geometry = acad_get(worker, handle="C1")["geometry"]

    assert geometry["Center"] == [100.0, 200.0, 0.0]
    assert geometry["Radius"] == 50.0


def test_acad_get_flattens_polyline_coordinates_to_a_list(worker, fake_port):
    fake_port.document.add_entity(
        FakeEntity(
            "P1",
            "AcDbPolyline",
            "walls",
            geometry={"Coordinates": (0.0, 0.0, 3000.0, 0.0, 3000.0, 4000.0), "Closed": True},
        )
    )

    geometry = acad_get(worker, handle="P1")["geometry"]

    assert geometry["Coordinates"] == [0.0, 0.0, 3000.0, 0.0, 3000.0, 4000.0]
    assert geometry["Closed"] is True


def test_acad_get_omits_geometry_when_nothing_is_readable(worker, fake_port):
    """빈 값으로 채우면 '원점에 있는 선'처럼 오해를 부른다 — 키 자체를 만들지 않는다."""
    fake_port.document.add_entity(FakeEntity("X1", "AcDbWeirdCustomThing", "0"))

    result = acad_get(worker, handle="X1")

    assert "geometry" not in result


def test_acad_get_survives_attributes_that_raise(worker, fake_port):
    """실제 COM 은 타입에 없는 속성에 접근하면 예외를 던진다. 조회 전체가 실패하면 안 된다."""

    class Exploding(FakeEntity):
        @property
        def StartPoint(self):  # noqa: N802 - COM 이름 그대로
            raise RuntimeError("이 타입에는 StartPoint 가 없습니다")

    fake_port.document.add_entity(
        Exploding("E1", "AcDbLine", "walls", geometry={"Length": 12.0})
    )

    result = acad_get(worker, handle="E1")

    assert result["geometry"] == {"Length": 12.0}


def test_acad_get_includes_bounding_box_when_available(worker, fake_port):
    ent = FakeEntity("B1", "AcDbLine", "walls")
    ent.set_bounding_box((0.0, 0.0, 0.0), (3000.0, 4000.0, 0.0))
    fake_port.document.add_entity(ent)

    result = acad_get(worker, handle="B1")

    assert result["bounding_box"] == {
        "min": [0.0, 0.0, 0.0],
        "max": [3000.0, 4000.0, 0.0],
    }


def test_acad_status_reports_unit_detail_for_the_sketchup_handoff(worker, fake_port):
    """SketchUp 은 내부 단위가 인치라 mm 변환이 필요하다 — 원시 INSUNITS 만으로는
    에이전트가 판단할 수 없다."""
    result = acad_status(worker)

    assert result["units_detail"]["name"] == "millimeters"
    assert result["units_detail"]["canonical"] is True
    assert result["units_detail"]["to_mm"] == 1.0


def test_acad_purge_check_reports_unsaved_and_locked_layers(worker, fake_port):
    fake_port.document.Saved = False
    locked = fake_port.document.Layers.Add("locked-layer")
    locked.Lock = True

    result = acad_purge_check(worker)

    assert result["saved"] is False
    assert "locked-layer" in result["locked_layers"]
    assert "0" not in result["locked_layers"]


def test_acad_purge_check_reports_frozen_layers(worker, fake_port):
    """동결 레이어는 플롯·캡처에서 빠진다 — 캡처가 비어 보이는 흔한 원인."""
    frozen = fake_port.document.Layers.Add("frozen-layer")
    frozen.Freeze = True

    result = acad_purge_check(worker)

    assert result["frozen_layers"] == ["frozen-layer"]
