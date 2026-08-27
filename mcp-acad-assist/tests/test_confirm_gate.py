"""승인 게이트 1차: confirm=False는 절대 실행하지 않고 미리보기만 반환한다."""

from __future__ import annotations

from acad_assist.modify import acad_layer, acad_modify

from .fakes import FakeEntity
from .support import coords, is_variant_double_array


def test_modify_without_confirm_does_not_execute(worker, fake_port):
    ent = FakeEntity("A1", "AcDbLine", "walls")
    fake_port.document.add_entity(ent)

    result = acad_modify(worker, handles=["A1"], operation="erase", confirm=False)

    assert result["confirm_required"] is True
    assert result["affected_count"] == 1
    assert ent.erased is False


def test_modify_with_confirm_executes(worker, fake_port):
    ent = FakeEntity("A1", "AcDbLine", "walls")
    fake_port.document.add_entity(ent)

    result = acad_modify(worker, handles=["A1"], operation="erase", confirm=True)

    assert result["confirm_required"] is False
    assert ent.erased is True


def test_modify_move_passes_params_through(worker, fake_port):
    """좌표는 a_double()을 통과해 COM 이 받는 형태로 바뀐다 — 이 개발 PC 에는
    pywin32 가 있어서 실제 VARIANT 로 감싸진다. 튜플 그대로 비교하면 깨진다."""
    ent = FakeEntity("A1", "AcDbLine", "walls")
    fake_port.document.add_entity(ent)

    acad_modify(
        worker,
        handles=["A1"],
        operation="move",
        params={"from_point": (0, 0, 0), "to_point": (1, 1, 0)},
        confirm=True,
    )

    assert len(ent.calls) == 1
    name, args = ent.calls[0]
    assert name == "Move"
    from_arg, to_arg = args
    assert coords(from_arg) == (0.0, 0.0, 0.0)
    assert coords(to_arg) == (1.0, 1.0, 0.0)
    # pywin32 가 있는 환경이면 실제 VARIANT(VT_ARRAY|VT_R8) 로 감싸져야 한다 —
    # 튜플을 그대로 넘기면 실제 AutoCAD 에서 Type mismatch 가 난다.
    assert is_variant_double_array(from_arg)
    assert is_variant_double_array(to_arg)


def test_modify_move_promotes_2d_points_to_3d(worker, fake_port):
    ent = FakeEntity("A1", "AcDbLine", "walls")
    fake_port.document.add_entity(ent)

    acad_modify(
        worker,
        handles=["A1"],
        operation="move",
        params={"from_point": (0, 0), "to_point": (5, 5)},
        confirm=True,
    )

    _, (from_arg, to_arg) = ent.calls[0]
    assert coords(from_arg) == (0.0, 0.0, 0.0)
    assert coords(to_arg) == (5.0, 5.0, 0.0)


def test_modify_missing_required_param_raises_actionable_error(worker, fake_port):
    import pytest

    from acad_assist.modify import ModifyError

    ent = FakeEntity("A1", "AcDbLine", "walls")
    fake_port.document.add_entity(ent)

    with pytest.raises(ModifyError, match="from_point"):
        acad_modify(worker, handles=["A1"], operation="move", params={}, confirm=True)


def test_modify_unsupported_operation_fails_before_confirm(worker, fake_port):
    """예전 구현은 confirm=True 로 실행 단계까지 들어간 뒤에야 실패했다.
    미리보기 단계에서부터 막아야 승인 UI 에 이상한 요청이 뜨지 않는다."""
    import pytest

    from acad_assist.modify import ModifyError

    with pytest.raises(ModifyError, match="mirror"):
        acad_modify(worker, handles=["A1"], operation="mirror", confirm=False)


def test_modify_rotate_converts_degrees_to_radians_by_default(worker, fake_port):
    import math

    ent = FakeEntity("A1", "AcDbLine", "walls")
    fake_port.document.add_entity(ent)

    acad_modify(
        worker,
        handles=["A1"],
        operation="rotate",
        params={"base_point": (0, 0, 0), "angle": 90},
        confirm=True,
    )

    _, (base_arg, angle_arg) = ent.calls[0]
    assert angle_arg == math.radians(90)


def test_modify_rotate_accepts_explicit_radians(worker, fake_port):
    import math

    ent = FakeEntity("A1", "AcDbLine", "walls")
    fake_port.document.add_entity(ent)

    acad_modify(
        worker,
        handles=["A1"],
        operation="rotate",
        params={"base_point": (0, 0, 0), "angle": math.pi / 2, "angle_unit": "radians"},
        confirm=True,
    )

    _, (base_arg, angle_arg) = ent.calls[0]
    assert angle_arg == math.pi / 2


def test_modify_copy_moves_clone_to_destination_and_returns_new_handle(worker, fake_port):
    """예전 구현은 params 를 완전히 무시해 사본이 원본 위에 겹쳤고, 새 핸들도
    버려서 다음 도구가 사본을 참조할 방법이 없었다."""
    ent = FakeEntity("A1", "AcDbLine", "walls")
    fake_port.document.add_entity(ent)

    result = acad_modify(
        worker,
        handles=["A1"],
        operation="copy",
        params={"from_point": (0, 0, 0), "to_point": (10, 0, 0)},
        confirm=True,
    )

    assert result["created"] == ["A1C1"]
    clone = ent.copies[0]
    assert clone.calls == [("Move", clone.calls[0][1])]
    move_name, (from_arg, to_arg) = clone.calls[0]
    assert coords(from_arg) == (0.0, 0.0, 0.0)
    assert coords(to_arg) == (10.0, 0.0, 0.0)


def test_modify_copy_without_destination_does_not_move(worker, fake_port):
    ent = FakeEntity("A1", "AcDbLine", "walls")
    fake_port.document.add_entity(ent)

    result = acad_modify(worker, handles=["A1"], operation="copy", confirm=True)

    assert result["created"] == ["A1C1"]
    assert ent.copies[0].calls == []


def test_modify_batches_wrap_in_undo_mark(worker, fake_port):
    ent = FakeEntity("A1", "AcDbLine", "walls")
    fake_port.document.add_entity(ent)

    acad_modify(worker, handles=["A1"], operation="erase", confirm=True)

    assert fake_port.document.undo_marks == ["start", "end"]


def test_modify_undo_mark_still_closes_when_operation_fails(worker, fake_port):
    """중간에 실패해도 EndUndoMark 는 반드시 불러야 한다 — 아니면 다음 작업까지
    같은 undo 그룹에 잘못 묶인다."""
    import pytest

    from acad_assist.modify import ModifyError

    fake_port.document.add_entity(FakeEntity("A1", "AcDbLine", "walls"))

    with pytest.raises(ModifyError):
        acad_modify(
            worker, handles=["A1"], operation="move", params={}, confirm=True
        )

    assert fake_port.document.undo_marks == ["start", "end"]


def test_layer_create_without_confirm_does_not_create(worker, fake_port):
    result = acad_layer(worker, action="create", name="new-layer", confirm=False)

    assert result["confirm_required"] is True
    assert "new-layer" not in [layer.Name for layer in fake_port.document.Layers]


def test_layer_create_with_confirm_creates(worker, fake_port):
    result = acad_layer(worker, action="create", name="new-layer", confirm=True)

    assert result["confirm_required"] is False
    assert "new-layer" in [layer.Name for layer in fake_port.document.Layers]


def test_layer_freeze_with_confirm(worker, fake_port):
    fake_port.document.Layers.Add("temp")

    acad_layer(worker, action="freeze", name="temp", confirm=True)

    assert fake_port.document.Layers.Item("temp").Freeze is True


def test_layer_thaw_unfreezes(worker, fake_port):
    layer = fake_port.document.Layers.Add("temp")
    layer.Freeze = True

    acad_layer(worker, action="thaw", name="temp", confirm=True)

    assert fake_port.document.Layers.Item("temp").Freeze is False


def test_layer_lock_and_unlock(worker, fake_port):
    fake_port.document.Layers.Add("temp")

    acad_layer(worker, action="lock", name="temp", confirm=True)
    assert fake_port.document.Layers.Item("temp").Lock is True

    acad_layer(worker, action="unlock", name="temp", confirm=True)
    assert fake_port.document.Layers.Item("temp").Lock is False


def test_layer_delete_calls_delete_on_the_com_object(worker, fake_port):
    layer = fake_port.document.Layers.Add("temp")

    acad_layer(worker, action="delete", name="temp", confirm=True)

    assert layer.deleted is True


def test_layer_color_uses_uppercase_com_property(worker, fake_port):
    """실제 AutoCAD ActiveX 속성은 대문자 Color (ACI 인덱스)다. 예전 구현은
    소문자 layer.color 를 썼고 목도 소문자였기 때문에 테스트가 못 잡았다."""
    fake_port.document.Layers.Add("temp")

    acad_layer(worker, action="color", name="temp", params={"color": 3}, confirm=True)

    layer = fake_port.document.Layers.Item("temp")
    assert layer.Color == 3


def test_layer_color_without_params_fails_before_confirm(worker, fake_port):
    import pytest

    from acad_assist.modify import ModifyError

    with pytest.raises(ModifyError, match="color"):
        acad_layer(worker, action="color", name="temp", confirm=False)


def test_layer_unknown_action_fails_before_confirm(worker, fake_port):
    """예전 구현은 알 수 없는 action 이 if/elif 를 그냥 통과해 성공으로 보고했다."""
    import pytest

    from acad_assist.modify import ModifyError

    with pytest.raises(ModifyError, match="rename"):
        acad_layer(worker, action="rename", name="temp", confirm=False)


def test_layer_switch_missing_layer_raises_readable_error(worker, fake_port):
    import pytest

    from acad_assist.modify import ModifyError

    with pytest.raises(ModifyError, match="ghost-layer"):
        acad_layer(worker, action="switch", name="ghost-layer", confirm=True)
