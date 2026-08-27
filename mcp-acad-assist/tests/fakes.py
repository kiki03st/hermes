"""AcadPort 프로토콜을 만족하는 테스트용 가짜 COM 연결.

실제 pywin32/AutoCAD 없이 acad_assist의 비즈니스 로직(승인 게이트, 조회
필터, 좌표 변환 호출, 재연결)을 검증하기 위한 목(mock).

**기존 계약을 깨지 않는다.** `saveas_calls`(경로 문자열 리스트),
`FakePlot.calls`((경로, 플로터) 튜플 리스트), `FakeEntity.calls`,
`FakeEntity.erased`, `FakeLayer.color`는 이미 테스트가 pin 하고 있으므로 그대로 두고,
새로 필요한 것은 별도 이름으로 추가한다.
"""

from __future__ import annotations

from typing import Any

#: 실제 AutoCAD ActiveX 는 색을 `Color`(ACI 인덱스) / `TrueColor`(객체)로 노출한다.
#: 예전 fake 는 소문자 `color` 만 갖고 있었고 production 코드도 소문자를 써서,
#: 케이싱 오류를 테스트가 잡지 못했다. 이제 둘 다 노출한다.
DEFAULT_ACI_COLOR = 7


class FakeLayer:
    def __init__(self, name: str) -> None:
        self.Name = name
        self.color = DEFAULT_ACI_COLOR  # 레거시 (기존 테스트 호환)
        self.Color = DEFAULT_ACI_COLOR
        self.TrueColor: Any = None
        self.Freeze = False
        self.Lock = False
        self.LayerOn = True
        self.deleted = False

    def Delete(self) -> None:
        self.deleted = True


class FakeLayers:
    def __init__(self) -> None:
        self._layers: dict[str, FakeLayer] = {"0": FakeLayer("0")}

    def __iter__(self):
        return iter(self._layers.values())

    @property
    def Count(self) -> int:
        return len(self._layers)

    def Add(self, name: str) -> FakeLayer:
        layer = FakeLayer(name)
        self._layers[name] = layer
        return layer

    def Item(self, name: str) -> FakeLayer:
        return self._layers[name]

    def drop(self, name: str) -> None:
        """테스트 헬퍼 — `Delete()` 이후 컬렉션에서도 사라진 상태를 만든다."""
        self._layers.pop(name, None)


class FakeEntity:
    def __init__(
        self,
        handle: str,
        obj_type: str,
        layer: str,
        geometry: dict[str, Any] | None = None,
    ) -> None:
        self.Handle = handle
        self.ObjectName = obj_type
        self.Layer = layer
        self.Color = DEFAULT_ACI_COLOR
        self.Linetype = "Continuous"
        self.erased = False
        self.calls: list[tuple[str, tuple]] = []
        #: `Copy()` 가 만든 사본. 실제 COM 도 새 객체를 돌려준다.
        self.copies: list["FakeEntity"] = []
        self._bbox: tuple[Any, Any] | None = None
        for key, value in (geometry or {}).items():
            setattr(self, key, value)

    def Erase(self) -> None:
        self.erased = True

    def Move(self, *args: Any) -> None:
        self.calls.append(("Move", args))

    def Rotate(self, *args: Any) -> None:
        self.calls.append(("Rotate", args))

    def ScaleEntity(self, *args: Any) -> None:
        self.calls.append(("ScaleEntity", args))

    def Offset(self, *args: Any) -> Any:
        self.calls.append(("Offset", args))
        return ()

    def Copy(self) -> "FakeEntity":
        self.calls.append(("Copy", ()))
        clone = FakeEntity(
            handle=f"{self.Handle}C{len(self.copies) + 1}",
            obj_type=self.ObjectName,
            layer=self.Layer,
        )
        self.copies.append(clone)
        return clone

    def set_bounding_box(self, min_pt: Any, max_pt: Any) -> None:
        self._bbox = (min_pt, max_pt)

    def GetBoundingBox(self) -> tuple[Any, Any]:
        if self._bbox is None:
            raise AttributeError("bounding box not set on this fake entity")
        return self._bbox


class FakePlot:
    def __init__(self) -> None:
        #: 기존 테스트가 pin 하는 형태 — (경로, 플로터) 튜플.
        self.calls: list[tuple[str, str]] = []
        self.layouts_to_plot: list[Any] = []
        self.QuietErrorMode = False
        self.NumberOfCopies = 1
        #: `PlotToFile` 이 돌려줄 값. 실제 COM 은 성공 여부 Boolean 을 준다.
        self.result = True
        #: 성공 시 실제로 디스크에 쓸 바이트. `capture.py` 가 파일을 읽어 base64 로
        #: 돌려주는 경로를 진짜로 (가짜 파일이지만) 테스트하기 위함.
        self.file_bytes: bytes | None = b"\x89PNG\r\n\x1a\n" + b"\x00" * 16
        #: "성공을 보고했는데 파일이 안 생기는" COM 이상 동작을 시뮬레이션할 때 False로.
        self.write_file_on_success = True

    def PlotToFile(self, path: str, plotter: str) -> bool:
        self.calls.append((path, plotter))
        if self.result and self.write_file_on_success and self.file_bytes is not None:
            from pathlib import Path

            p = Path(path)
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(self.file_bytes)
        return self.result

    def SetLayoutsToPlot(self, layouts: Any) -> None:
        self.layouts_to_plot.append(layouts)


class FakeLayout:
    def __init__(self, name: str = "Model") -> None:
        self.Name = name
        self.ConfigName = "None"
        self.CanonicalMediaName = "ISO_A3_(420.00_x_297.00_MM)"
        self.StyleSheet = ""
        self.PlotType = 0
        self.StandardScale = 0
        self.CenterPlot = False
        self.PlotRotation = 0
        self.refresh_count = 0

    def RefreshPlotDeviceInfo(self) -> None:
        self.refresh_count += 1


class FakeDocument:
    def __init__(self, name: str = "Drawing1.dwg") -> None:
        self.Name = name
        self.FullName = f"C:\\drawings\\{name}"
        self.Layers = FakeLayers()
        self.ModelSpace: list[FakeEntity] = []
        self.ActiveLayer = self.Layers.Item("0")
        self.ActiveLayout = FakeLayout()
        self.Plot = FakePlot()
        self.Saved = True
        #: 기존 테스트가 pin 하는 형태 — 경로 문자열만.
        self.saveas_calls: list[str] = []
        #: 새로 추가: 인자 전체(파일 타입 상수 포함)를 보고 싶을 때.
        self.saveas_details: list[tuple[str, tuple]] = []
        self.undo_marks: list[str] = []
        self.regen_count = 0
        self._vars: dict[str, Any] = {"INSUNITS": 4}  # 4 == mm
        self._by_handle: dict[str, FakeEntity] = {}

    def add_entity(self, entity: FakeEntity) -> None:
        self.ModelSpace.append(entity)
        self._by_handle[entity.Handle] = entity

    def GetVariable(self, name: str) -> Any:
        return self._vars[name]

    def SetVariable(self, name: str, value: Any) -> None:
        self._vars[name] = value

    def HandleToObject(self, handle: str) -> FakeEntity:
        return self._by_handle[handle]

    def SaveAs(self, path: str, *rest: Any) -> None:
        self.saveas_calls.append(path)
        self.saveas_details.append((path, rest))

    def StartUndoMark(self) -> None:
        self.undo_marks.append("start")

    def EndUndoMark(self) -> None:
        self.undo_marks.append("end")

    def Regen(self, *_: Any) -> None:
        self.regen_count += 1


class FakeAcadPort:
    def __init__(self) -> None:
        self.alive = True
        self.document = FakeDocument()
        self.reconnect_count = 0
        #: `Application.ZoomExtents()` 호출 횟수 — 캡처 전 뷰 정리를 검증한다.
        self.zoom_extents_count = 0
        self.Name = "AutoCAD"
        self.Visible = True

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

    def ZoomExtents(self) -> None:
        self.zoom_extents_count += 1
