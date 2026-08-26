"""AcadPort 프로토콜을 만족하는 테스트용 가짜 COM 연결.

실제 pywin32/AutoCAD 없이 acad_assist의 비즈니스 로직(승인 게이트, 조회
필터, 좌표 변환 호출, 재연결)을 검증하기 위한 목(mock)."""

from __future__ import annotations

from typing import Any


class FakeLayer:
    def __init__(self, name: str) -> None:
        self.Name = name
        self.color = 7
        self.Freeze = False
        self.Lock = False


class FakeLayers:
    def __init__(self) -> None:
        self._layers: dict[str, FakeLayer] = {"0": FakeLayer("0")}

    def __iter__(self):
        return iter(self._layers.values())

    def Add(self, name: str) -> FakeLayer:
        layer = FakeLayer(name)
        self._layers[name] = layer
        return layer

    def Item(self, name: str) -> FakeLayer:
        return self._layers[name]


class FakeEntity:
    def __init__(self, handle: str, obj_type: str, layer: str) -> None:
        self.Handle = handle
        self.ObjectName = obj_type
        self.Layer = layer
        self.erased = False
        self.calls: list[tuple[str, tuple]] = []

    def Erase(self) -> None:
        self.erased = True

    def Move(self, *args: Any) -> None:
        self.calls.append(("Move", args))

    def Rotate(self, *args: Any) -> None:
        self.calls.append(("Rotate", args))

    def ScaleEntity(self, *args: Any) -> None:
        self.calls.append(("ScaleEntity", args))

    def Offset(self, *args: Any) -> None:
        self.calls.append(("Offset", args))

    def Copy(self) -> None:
        self.calls.append(("Copy", ()))


class FakePlot:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []

    def PlotToFile(self, path: str, plotter: str) -> None:
        self.calls.append((path, plotter))


class FakeDocument:
    def __init__(self, name: str = "Drawing1.dwg") -> None:
        self.Name = name
        self.Layers = FakeLayers()
        self.ModelSpace: list[FakeEntity] = []
        self.ActiveLayer = self.Layers.Item("0")
        self.Plot = FakePlot()
        self.Saved = True
        self.saveas_calls: list[str] = []
        self._vars = {"INSUNITS": 4}  # 4 == mm
        self._by_handle: dict[str, FakeEntity] = {}

    def add_entity(self, entity: FakeEntity) -> None:
        self.ModelSpace.append(entity)
        self._by_handle[entity.Handle] = entity

    def GetVariable(self, name: str) -> Any:
        return self._vars[name]

    def HandleToObject(self, handle: str) -> FakeEntity:
        return self._by_handle[handle]

    def SaveAs(self, path: str, *_: Any) -> None:
        self.saveas_calls.append(path)


class FakeAcadPort:
    def __init__(self) -> None:
        self.alive = True
        self.document = FakeDocument()
        self.reconnect_count = 0

    def is_alive(self) -> bool:
        return self.alive

    def reconnect(self) -> None:
        self.reconnect_count += 1
        self.alive = True

    def application(self) -> Any:
        return self

    def active_document(self) -> FakeDocument:
        if not self.alive:
            raise RuntimeError("연결이 끊어졌습니다")
        return self.document
