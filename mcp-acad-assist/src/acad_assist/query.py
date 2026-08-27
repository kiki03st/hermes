"""acad_status / acad_query / acad_get — 읽기 전용 조회 도구. 승인 불필요."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Iterable

from . import units
from .com import AcadPort, ComWorker

#: 엔티티 타입별로 읽어볼 지오메트리 속성.
#: AutoCAD ActiveX 는 타입마다 다른 속성을 노출하고, 없는 속성에 접근하면 COM 예외가
#: 난다. 그래서 "있으면 읽고 없으면 건너뛴다"는 방어적 방식을 쓴다 — 타입 문자열이
#: 예상과 달라도(커스텀 객체, 로컬라이즈된 이름) 조회 자체는 실패하지 않는다.
GEOMETRY_ATTRS: dict[str, tuple[str, ...]] = {
    "AcDbLine": ("StartPoint", "EndPoint", "Length", "Angle", "Delta"),
    "AcDbCircle": ("Center", "Radius", "Diameter", "Area", "Circumference"),
    "AcDbArc": ("Center", "Radius", "StartAngle", "EndAngle", "ArcLength", "TotalAngle"),
    "AcDbEllipse": ("Center", "MajorAxis", "MinorAxis", "RadiusRatio", "Area"),
    "AcDbPolyline": ("Coordinates", "Closed", "Area", "Length", "Elevation"),
    "AcDbLWPolyline": ("Coordinates", "Closed", "Area", "Length", "Elevation"),
    "AcDb2dPolyline": ("Coordinates", "Closed", "Area", "Length"),
    "AcDbText": ("TextString", "InsertionPoint", "Height", "Rotation", "StyleName"),
    "AcDbMText": ("TextString", "InsertionPoint", "Height", "Rotation", "Width"),
    "AcDbBlockReference": (
        "Name",
        "InsertionPoint",
        "Rotation",
        "XScaleFactor",
        "YScaleFactor",
        "ZScaleFactor",
    ),
    "AcDbHatch": ("PatternName", "PatternScale", "Area", "NumberOfLoops"),
    "AcDbRotatedDimension": ("Measurement", "TextOverride", "TextPosition"),
    "AcDbAlignedDimension": ("Measurement", "TextOverride", "TextPosition"),
}

#: 타입과 무관하게 시도해보는 공통 속성. 도면 정리·레이어 재배치 판단에 쓴다.
COMMON_ATTRS: tuple[str, ...] = ("Color", "Linetype", "Lineweight", "Visible")

#: 지오메트리를 모르는 타입에 대해서도 최소한 이건 시도한다.
FALLBACK_ATTRS: tuple[str, ...] = ("InsertionPoint", "Area", "Length")


@dataclass
class EntityInfo:
    handle: str
    type: str
    layer: str

    def to_dict(self) -> dict[str, Any]:
        return {"handle": self.handle, "type": self.type, "layer": self.layer}


def _jsonable(value: Any) -> Any:
    """COM 값을 JSON 으로 실을 수 있는 형태로.

    좌표는 COM 에서 tuple 로 오고 `Coordinates` 같은 평탄 배열도 tuple 이다.
    그대로 두면 직렬화 단계에서 형태가 제각각이 되므로 리스트로 통일한다.
    """
    if isinstance(value, (tuple, list)):
        return [_jsonable(v) for v in value]
    if isinstance(value, (str, bool, int, float)) or value is None:
        return value
    # 알 수 없는 COM 객체(TrueColor 등) — 문자열로 떨어뜨린다.
    return str(value)


def _read_attrs(ent: Any, names: Iterable[str]) -> dict[str, Any]:
    """존재하는 속성만 읽어 dict 로. 없는 속성은 조용히 건너뛴다."""
    out: dict[str, Any] = {}
    for name in names:
        try:
            value = getattr(ent, name)
        except Exception:
            continue
        if callable(value):
            continue
        out[name] = _jsonable(value)
    return out


def entity_geometry(ent: Any) -> dict[str, Any]:
    """엔티티의 지오메트리 속성을 타입에 맞춰 읽는다. 없으면 빈 dict."""
    obj_type = str(getattr(ent, "ObjectName", "") or "")
    names = GEOMETRY_ATTRS.get(obj_type, FALLBACK_ATTRS)
    return _read_attrs(ent, names)


def entity_bounding_box(ent: Any) -> dict[str, Any] | None:
    """`GetBoundingBox()` 결과. 지오메트리를 모르는 타입에도 크기 감각을 준다."""
    try:
        min_pt, max_pt = ent.GetBoundingBox()
    except Exception:
        return None
    return {"min": _jsonable(min_pt), "max": _jsonable(max_pt)}


def acad_status(worker: ComWorker) -> dict[str, Any]:
    def _run(port: AcadPort) -> dict[str, Any]:
        doc = port.active_document()
        layers = [layer.Name for layer in doc.Layers]
        insunits = doc.GetVariable("INSUNITS")
        return {
            "connected": True,
            "document": doc.Name,
            "layers": layers,
            "units": insunits,
            # 원시 INSUNITS 만으로는 에이전트가 mm 인지 판단하지 못한다.
            # SketchUp 단계에서 mm↔인치 변환이 필요하므로 해석까지 같이 준다.
            "units_detail": units.describe(insunits),
        }

    return worker.call(_run)


def acad_query(
    worker: ComWorker,
    layer: str | None = None,
    entity_type: str | None = None,
) -> list[dict[str, Any]]:
    def _run(port: AcadPort) -> list[dict[str, Any]]:
        doc = port.active_document()
        results: list[EntityInfo] = []
        for ent in doc.ModelSpace:
            if layer is not None and ent.Layer != layer:
                continue
            if entity_type is not None and ent.ObjectName != entity_type:
                continue
            results.append(EntityInfo(handle=ent.Handle, type=ent.ObjectName, layer=ent.Layer))
        return [e.to_dict() for e in results]

    return worker.call(_run)


def acad_get(worker: ComWorker, handle: str) -> dict[str, Any]:
    """핸들로 엔티티 상세 조회 — 타입·레이어에 지오메트리까지.

    기본 3개 키(`handle`/`type`/`layer`)는 항상 있다. `geometry`/`properties`/
    `bounding_box` 는 **읽을 수 있었을 때만** 키가 생긴다 — 타입마다 노출하는 속성이
    달라서 빈 값으로 채우면 "원점에 있는 선"처럼 오해를 부른다.
    """

    def _run(port: AcadPort) -> dict[str, Any]:
        doc = port.active_document()
        ent = doc.HandleToObject(handle)
        result: dict[str, Any] = {
            "handle": handle,
            "type": ent.ObjectName,
            "layer": ent.Layer,
        }
        geometry = entity_geometry(ent)
        if geometry:
            result["geometry"] = geometry
        properties = _read_attrs(ent, COMMON_ATTRS)
        if properties:
            result["properties"] = properties
        bbox = entity_bounding_box(ent)
        if bbox is not None:
            result["bounding_box"] = bbox
        return result

    return worker.call(_run)


def acad_purge_check(worker: ComWorker) -> dict[str, Any]:
    """저장 전 점검 — 미저장 변경, 잠긴/동결 레이어. 읽기 전용, 승인 불필요."""

    def _run(port: AcadPort) -> dict[str, Any]:
        doc = port.active_document()
        locked_layers = [layer.Name for layer in doc.Layers if getattr(layer, "Lock", False)]
        frozen_layers = [layer.Name for layer in doc.Layers if getattr(layer, "Freeze", False)]
        return {
            "saved": bool(getattr(doc, "Saved", True)),
            "locked_layers": locked_layers,
            # 동결된 레이어는 플롯·캡처 결과에서 빠진다 — 캡처가 "비어 보이는"
            # 가장 흔한 원인이라 저장·캡처 전에 같이 알려준다.
            "frozen_layers": frozen_layers,
        }

    return worker.call(_run)
