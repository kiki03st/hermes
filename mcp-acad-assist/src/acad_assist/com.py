"""AutoCAD COM 연결 계층.

COM은 STA(Single-Threaded Apartment)라서 모든 호출을 단일 워커 스레드에
직렬화해야 한다. `AcadPort`는 상위 계층(query/modify/capture/export)이
의존하는 최소 인터페이스이고, 실제 구현(`Win32AcadPort`)은 Windows에서만
동작한다. 테스트는 `tests/fakes.py`의 `FakeAcadPort`를 주입한다.
"""

from __future__ import annotations

import queue
import sys
import threading
from typing import Any, Callable, Protocol, TypeVar

T = TypeVar("T")


class AcadConnectionError(RuntimeError):
    """AutoCAD COM 연결이 죽었거나 애플리케이션을 찾을 수 없을 때"""


class AcadPort(Protocol):
    def is_alive(self) -> bool: ...
    def reconnect(self) -> None: ...
    def application(self) -> Any: ...
    def active_document(self) -> Any: ...


class Win32AcadPort:
    """실제 AutoCAD COM 연결. `win32com.client`는 지연 임포트한다 —
    비-Windows 환경에서도 이 모듈 자체의 import는 깨지지 않게 하기 위함."""

    def __init__(self) -> None:
        if sys.platform != "win32":
            raise RuntimeError("Win32AcadPort는 Windows에서만 사용할 수 있습니다")
        self._app: Any = None

    def _dispatch(self) -> Any:
        import win32com.client

        # AutoCAD가 안 떠 있으면 Dispatch가 자동으로 실행시킨다 (PLAN.md COM 취급 주의)
        return win32com.client.Dispatch("AutoCAD.Application")

    def is_alive(self) -> bool:
        if self._app is None:
            return False
        try:
            _ = self._app.Name
            return True
        except Exception:
            return False

    def reconnect(self) -> None:
        self._app = self._dispatch()

    def application(self) -> Any:
        if not self.is_alive():
            self.reconnect()
        return self._app

    def active_document(self) -> Any:
        app = self.application()
        try:
            return app.ActiveDocument
        except Exception as exc:
            raise AcadConnectionError("활성 도면을 가져올 수 없습니다") from exc


class ComWorker:
    """모든 AutoCAD COM 호출을 단일 워커 스레드에 직렬화한다.

    `port_factory`는 워커 스레드 안에서 정확히 한 번 호출된다 — COM 객체를
    생성한 스레드에서만 그 객체를 써야 하는 STA 제약을 지키기 위해서다.
    """

    def __init__(self, port_factory: Callable[[], AcadPort]) -> None:
        self._jobs: "queue.Queue[tuple[Callable[[AcadPort], Any], queue.Queue]]" = queue.Queue()
        self._port_factory = port_factory
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._started = False
        self._lock = threading.Lock()

    def start(self) -> None:
        with self._lock:
            if not self._started:
                self._thread.start()
                self._started = True

    def _run(self) -> None:
        port = self._port_factory()
        while True:
            fn, result_q = self._jobs.get()
            try:
                result_q.put(("ok", fn(port)))
            except Exception as exc:  # noqa: BLE001 - 워커 경계에서 예외를 결과로 변환해 호출 스레드로 전달
                result_q.put(("error", exc))

    def call(self, fn: Callable[[AcadPort], T]) -> T:
        self.start()
        result_q: "queue.Queue[tuple[str, Any]]" = queue.Queue()
        self._jobs.put((fn, result_q))
        status, value = result_q.get()
        if status == "error":
            raise value
        return value
