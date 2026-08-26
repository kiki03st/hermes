"""acad_status / acad_query / acad_get — 읽기 전용 조회 도구. 승인 불필요."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .com import AcadPort, ComWorker


@dataclass
class EntityInfo:
    handle: str
    type: str
    layer: str

    def to_dict(self) -> dict[str, Any]:
        return {"handle": self.handle, "type": self.type, "layer": self.layer}


def acad_status(worker: ComWorker) -> dict[str, Any]:
    def _run(port: AcadPort) -> dict[str, Any]:
        doc = port.active_document()
        layers = [layer.Name for layer in doc.Layers]
        return {
            "connected": True,
            "document": doc.Name,
            "layers": layers,
            "units": doc.GetVariable("INSUNITS"),
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
    def _run(port: AcadPort) -> dict[str, Any]:
        doc = port.active_document()
        ent = doc.HandleToObject(handle)
        return {"handle": handle, "type": ent.ObjectName, "layer": ent.Layer}

    return worker.call(_run)


def acad_purge_check(worker: ComWorker) -> dict[str, Any]:
    """저장 전 점검 — 미저장 변경, 잠긴 레이어. 읽기 전용, 승인 불필요."""

    def _run(port: AcadPort) -> dict[str, Any]:
        doc = port.active_document()
        locked_layers = [layer.Name for layer in doc.Layers if getattr(layer, "Lock", False)]
        return {
            "saved": bool(getattr(doc, "Saved", True)),
            "locked_layers": locked_layers,
        }

    return worker.call(_run)
