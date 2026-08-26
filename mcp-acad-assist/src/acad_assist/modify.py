"""acad_modify / acad_layer — 쓰기 도구. 둘 다 승인 게이트(confirm)를 거친다."""

from __future__ import annotations

from typing import Any, Literal

from .com import AcadPort, ComWorker
from .confirm import ActionPreview, with_confirmation

ModifyOp = Literal["move", "copy", "rotate", "scale", "offset", "erase"]
LayerAction = Literal["create", "switch", "color", "freeze"]


def acad_modify(
    worker: ComWorker,
    handles: list[str],
    operation: ModifyOp,
    params: dict[str, Any] | None = None,
    confirm: bool = False,
) -> dict[str, Any]:
    params = params or {}

    def _preview() -> ActionPreview:
        return ActionPreview(
            summary=f"{operation} on {len(handles)} entities with {params}",
            affected_count=len(handles),
        )

    def _execute() -> dict[str, Any]:
        def _run(port: AcadPort) -> dict[str, Any]:
            doc = port.active_document()
            for handle in handles:
                ent = doc.HandleToObject(handle)
                _apply_modify(ent, operation, params)
            return {"confirm_required": False, "modified": handles}

        return worker.call(_run)

    result = with_confirmation(confirm, _preview, _execute)
    return result.to_dict() if isinstance(result, ActionPreview) else result


def _apply_modify(ent: Any, operation: ModifyOp, params: dict[str, Any]) -> None:
    if operation == "erase":
        ent.Erase()
    elif operation == "move":
        ent.Move(params["from_point"], params["to_point"])
    elif operation == "copy":
        ent.Copy()
    elif operation == "rotate":
        ent.Rotate(params["base_point"], params["angle"])
    elif operation == "scale":
        ent.ScaleEntity(params["base_point"], params["scale_factor"])
    elif operation == "offset":
        ent.Offset(params["distance"])
    else:
        raise ValueError(f"unsupported operation: {operation}")


def acad_layer(
    worker: ComWorker,
    action: LayerAction,
    name: str,
    params: dict[str, Any] | None = None,
    confirm: bool = False,
) -> dict[str, Any]:
    params = params or {}

    def _preview() -> ActionPreview:
        return ActionPreview(summary=f"layer {action}: {name} {params}", affected_count=1)

    def _execute() -> dict[str, Any]:
        def _run(port: AcadPort) -> dict[str, Any]:
            doc = port.active_document()
            layers = doc.Layers
            if action == "create":
                layers.Add(name)
            else:
                layer = layers.Item(name)
                if action == "switch":
                    doc.ActiveLayer = layer
                elif action == "color":
                    layer.color = params["color"]
                elif action == "freeze":
                    layer.Freeze = True
            return {"confirm_required": False, "layer": name, "action": action}

        return worker.call(_run)

    result = with_confirmation(confirm, _preview, _execute)
    return result.to_dict() if isinstance(result, ActionPreview) else result
