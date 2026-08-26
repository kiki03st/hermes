"""승인 게이트 1차: confirm=False는 절대 실행하지 않고 미리보기만 반환한다."""

from __future__ import annotations

from acad_assist.modify import acad_layer, acad_modify

from .fakes import FakeEntity


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
    ent = FakeEntity("A1", "AcDbLine", "walls")
    fake_port.document.add_entity(ent)

    acad_modify(
        worker,
        handles=["A1"],
        operation="move",
        params={"from_point": (0, 0, 0), "to_point": (1, 1, 0)},
        confirm=True,
    )

    assert ent.calls == [("Move", ((0, 0, 0), (1, 1, 0)))]


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
