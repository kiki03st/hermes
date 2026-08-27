"""acad_modify / acad_layer — 쓰기 도구. 둘 다 승인 게이트(confirm)를 거친다."""

from __future__ import annotations

import math
from typing import Any, Literal, get_args

from .com import AcadPort, ComWorker
from .confirm import ActionPreview, with_confirmation
from .variants import a_double

ModifyOp = Literal["move", "copy", "rotate", "scale", "offset", "erase"]
LayerAction = Literal[
    "create", "switch", "color", "freeze", "thaw", "lock", "unlock", "delete"
]

MODIFY_OPS: tuple[str, ...] = get_args(ModifyOp)
LAYER_ACTIONS: tuple[str, ...] = get_args(LayerAction)

#: 각도 입력의 기본 단위. AutoCAD COM 은 **라디안**을 받지만 사람과 LLM 은 도로
#: 말한다("45도 돌려줘"). 기본을 도로 잡고 명시적으로 변환한다 — 예전 구현은
#: params["angle"] 을 그대로 넘겨서 45를 45라디안(≈2578도)으로 돌렸다.
DEFAULT_ANGLE_UNIT = "degrees"


class ModifyError(ValueError):
    """요청한 수정 작업이 성립하지 않을 때 (알 수 없는 연산, 필수 파라미터 누락 등)."""


def _require(params: dict[str, Any], key: str, operation: str) -> Any:
    try:
        return params[key]
    except KeyError:
        raise ModifyError(
            f"{operation} 에는 params[{key!r}] 가 필요합니다. "
            f"받은 키: {sorted(params) or '(없음)'}"
        ) from None


def _angle_to_radians(params: dict[str, Any], operation: str) -> float:
    raw = _require(params, "angle", operation)
    try:
        value = float(raw)
    except (TypeError, ValueError):
        raise ModifyError(f"{operation}: angle 이 숫자가 아닙니다 ({raw!r}).") from None
    unit = str(params.get("angle_unit", DEFAULT_ANGLE_UNIT)).lower()
    if unit in ("deg", "degree", "degrees"):
        return math.radians(value)
    if unit in ("rad", "radian", "radians"):
        return value
    raise ModifyError(
        f"{operation}: angle_unit 은 'degrees' 또는 'radians' 여야 합니다 (받은 것: {unit!r})."
    )


def acad_modify(
    worker: ComWorker,
    handles: list[str],
    operation: ModifyOp,
    params: dict[str, Any] | None = None,
    confirm: bool = False,
) -> dict[str, Any]:
    params = params or {}
    if operation not in MODIFY_OPS:
        # 미리보기 단계에서도 막는다 — 예전 구현은 confirm=True 로 실제 실행에
        # 들어간 뒤에야 ValueError 를 던졌다.
        raise ModifyError(
            f"지원하지 않는 연산: {operation!r}. 가능한 값: {', '.join(MODIFY_OPS)}"
        )

    def _preview() -> ActionPreview:
        return ActionPreview(
            summary=f"{operation} on {len(handles)} entities with {params}",
            affected_count=len(handles),
        )

    def _execute() -> dict[str, Any]:
        def _run(port: AcadPort) -> dict[str, Any]:
            doc = port.active_document()
            modified: list[str] = []
            created: list[str] = []
            _start_undo(doc)
            try:
                for handle in handles:
                    ent = doc.HandleToObject(handle)
                    outcome = _apply_modify(ent, operation, params)
                    # 요청 핸들을 그대로 echo 하지 않는다 — 중간에 실패하면
                    # 실제로 바뀐 것만 보고해야 되돌릴 범위를 알 수 있다.
                    modified.append(handle)
                    new_handle = outcome.get("new_handle")
                    if new_handle:
                        created.append(new_handle)
            finally:
                _end_undo(doc)
            result: dict[str, Any] = {
                "confirm_required": False,
                "operation": operation,
                "modified": modified,
            }
            if created:
                result["created"] = created
            return result

        return worker.call(_run)

    result = with_confirmation(confirm, _preview, _execute)
    return result.to_dict() if isinstance(result, ActionPreview) else result


def _start_undo(doc: Any) -> None:
    """수정 배치를 하나의 UNDO 그룹으로 묶는다.

    여러 엔티티를 도는 중간에 실패하면 부분 수정이 남는다. UNDO 마크가 있으면
    사용자가 AutoCAD 에서 한 번의 UNDO 로 배치 전체를 되돌릴 수 있다.
    `StartUndoMark` 가 없는 버전·목이어도 수정 자체는 진행돼야 하므로 조용히 넘긴다.
    """
    try:
        doc.StartUndoMark()
    except Exception:
        pass


def _end_undo(doc: Any) -> None:
    try:
        doc.EndUndoMark()
    except Exception:
        pass


def _apply_modify(ent: Any, operation: str, params: dict[str, Any]) -> dict[str, Any]:
    """엔티티 하나에 연산을 적용하고 결과 정보를 돌려준다.

    좌표는 전부 `a_double` 을 통과한다 — AutoCAD ActiveX 의 점 인자는
    `VARIANT(VT_ARRAY | VT_R8)` 이어야 하고, 파이썬 튜플을 그대로 넘기면
    `Type mismatch` 나 조용한 오동작이 된다 (PLAN.md COM 취급 주의).
    """
    if operation == "erase":
        ent.Erase()
        return {}

    if operation == "move":
        ent.Move(
            a_double(_require(params, "from_point", operation), name="from_point"),
            a_double(_require(params, "to_point", operation), name="to_point"),
        )
        return {}

    if operation == "copy":
        # ActiveX `Copy()` 는 사본 객체를 돌려준다. 예전 구현은 반환값을 버려서
        # 새 엔티티의 핸들을 알 수 없었고, 목적점도 무시해 원본 위에 겹쳐 놓았다.
        clone = ent.Copy()
        moved = False
        if "to_point" in params:
            base = params.get("from_point", (0.0, 0.0, 0.0))
            clone.Move(
                a_double(base, name="from_point"),
                a_double(params["to_point"], name="to_point"),
            )
            moved = True
        out: dict[str, Any] = {"moved": moved}
        new_handle = getattr(clone, "Handle", None)
        if new_handle:
            out["new_handle"] = new_handle
        return out

    if operation == "rotate":
        ent.Rotate(
            a_double(_require(params, "base_point", operation), name="base_point"),
            _angle_to_radians(params, operation),
        )
        return {}

    if operation == "scale":
        ent.ScaleEntity(
            a_double(_require(params, "base_point", operation), name="base_point"),
            float(_require(params, "scale_factor", operation)),
        )
        return {}

    if operation == "offset":
        ent.Offset(float(_require(params, "distance", operation)))
        return {}

    raise ModifyError(f"지원하지 않는 연산: {operation!r}")


def acad_layer(
    worker: ComWorker,
    action: LayerAction,
    name: str,
    params: dict[str, Any] | None = None,
    confirm: bool = False,
) -> dict[str, Any]:
    params = params or {}
    if action not in LAYER_ACTIONS:
        raise ModifyError(
            f"지원하지 않는 레이어 작업: {action!r}. 가능한 값: {', '.join(LAYER_ACTIONS)}"
        )
    if action == "color" and "color" not in params:
        raise ModifyError("layer color 에는 params['color'] (ACI 인덱스 1~255) 가 필요합니다.")

    def _preview() -> ActionPreview:
        return ActionPreview(summary=f"layer {action}: {name} {params}", affected_count=1)

    def _execute() -> dict[str, Any]:
        def _run(port: AcadPort) -> dict[str, Any]:
            doc = port.active_document()
            layers = doc.Layers
            _start_undo(doc)
            try:
                if action == "create":
                    layers.Add(name)
                else:
                    layer = _layer_item(layers, name)
                    _apply_layer_action(doc, layer, action, params)
            finally:
                _end_undo(doc)
            return {"confirm_required": False, "layer": name, "action": action}

        return worker.call(_run)

    result = with_confirmation(confirm, _preview, _execute)
    return result.to_dict() if isinstance(result, ActionPreview) else result


def _layer_item(layers: Any, name: str) -> Any:
    """레이어를 이름으로 가져온다. 없으면 알아볼 수 있는 에러로.

    COM 은 없는 레이어에 대해 불친절한 에러 코드를 던지고 테스트 목은 `KeyError` 를
    던진다. 둘 다 사용자에게 그대로 보여줄 수 없다.
    """
    try:
        return layers.Item(name)
    except Exception as exc:
        raise ModifyError(f"레이어 {name!r} 를 찾을 수 없습니다.") from exc


def _apply_layer_action(doc: Any, layer: Any, action: str, params: dict[str, Any]) -> None:
    if action == "switch":
        doc.ActiveLayer = layer
    elif action == "color":
        # 실제 AutoCAD ActiveX 속성은 대문자 `Color` (ACI 인덱스)다. 예전 구현은
        # 소문자 `layer.color` 를 썼고 목도 소문자였기 때문에 테스트가 못 잡았다.
        # RGB TrueColor 는 `AcCmColor` 객체가 필요해 대상 환경 작업으로 남긴다.
        layer.Color = int(params["color"])
    elif action == "freeze":
        layer.Freeze = True
    elif action == "thaw":
        layer.Freeze = False
    elif action == "lock":
        layer.Lock = True
    elif action == "unlock":
        layer.Lock = False
    elif action == "delete":
        layer.Delete()
    else:  # pragma: no cover - acad_layer 가 앞에서 검증한다
        raise ModifyError(f"지원하지 않는 레이어 작업: {action!r}")
