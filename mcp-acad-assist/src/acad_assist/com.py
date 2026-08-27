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

#: `ComWorker.call` 의 기본 타임아웃(초). COM 호출이 응답 없이 멈추면 호출 스레드가
#: 영구 블록되는데, MCP 서버에서는 그게 서버 전체 정지로 보인다. 넉넉하게 주되
#: 무한 대기는 하지 않는다 — 대형 도면의 PlotToFile 이 분 단위로 걸릴 수 있어서
#: 짧게 잡을 수도 없다. 개별 호출은 `call(..., timeout=)` 으로 덮어쓴다.
DEFAULT_CALL_TIMEOUT = 600.0


class AcadConnectionError(RuntimeError):
    """AutoCAD COM 연결이 죽었거나 애플리케이션을 찾을 수 없을 때"""


class AcadCallTimeout(RuntimeError):
    """워커 스레드가 제한 시간 안에 결과를 돌려주지 않았을 때.

    COM 호출은 취소할 수 없으므로 워커 스레드는 계속 그 호출에 매달려 있다.
    이 예외는 "결과를 기다리는 것을 포기했다"는 뜻이지 "작업이 취소됐다"는 뜻이
    아니다 — AutoCAD 안에서는 여전히 진행 중일 수 있다.
    """


class AcadPort(Protocol):
    def is_alive(self) -> bool: ...
    def reconnect(self) -> None: ...
    def application(self) -> Any: ...
    def active_document(self) -> Any: ...


class Win32AcadPort:
    """실제 AutoCAD COM 연결. `win32com.client`는 지연 임포트한다 —
    비-Windows 환경에서도 이 모듈 자체의 import는 깨지지 않게 하기 위함."""

    #: COM ProgID. AutoCAD 호환 제품을 쓸 경우 생성 시 바꿀 수 있다
    #: (예: GStarCAD `GCAD.Application`, ZWCAD `ZWCAD.Application` — 업스트림
    #: CAD-MCP 가 지원하는 목록과 같다).
    DEFAULT_PROG_ID = "AutoCAD.Application"

    def __init__(self, prog_id: str | None = None) -> None:
        if sys.platform != "win32":
            raise RuntimeError("Win32AcadPort는 Windows에서만 사용할 수 있습니다")
        self._app: Any = None
        self._prog_id = prog_id or self.DEFAULT_PROG_ID
        self._typelib_loaded = False

    def _dispatch(self) -> Any:
        """이미 떠 있는 AutoCAD 에 붙는다. 없으면 새로 띄운다.

        `GetActiveObject` 를 먼저 시도하는 게 중요하다 — `Dispatch` 만 쓰면
        AutoCAD 가 안 떠 있을 때 **조용히 새 인스턴스를 띄운다**. 사용자가 도면을
        열어둔 채 작업하는 흐름에서 빈 도면이 하나 더 열리면 이후 모든 조회·수정이
        엉뚱한 문서를 향한다. 업스트림 CAD-MCP 도 같은 순서를 쓴다.
        """
        import win32com.client

        try:
            return win32com.client.GetActiveObject(self._prog_id)
        except Exception:
            # 떠 있는 인스턴스가 없다 — 이제는 띄우는 게 맞다.
            return win32com.client.Dispatch(self._prog_id)

    def _load_typelib(self) -> None:
        """타입 라이브러리 캐시를 만들어 `win32com.client.constants` 를 채운다.

        `AcSaveAsType` 같은 열거형의 **숫자값을 코드에 하드코딩하지 않기 위해서**다
        (릴리스마다 값이 바뀌고 Autodesk 문서도 숫자를 일부만 공개한다).
        실패해도 치명적이지 않다 — `acad_constants` 모듈이 검증된 폴백을 쓴다.
        """
        if self._typelib_loaded:
            return
        try:
            from win32com.client import gencache

            gencache.EnsureDispatch(self._prog_id)
        except Exception:
            pass
        finally:
            self._typelib_loaded = True

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
        self._load_typelib()

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

    `port_factory`는 워커 스레드 안에서 호출된다 — COM 객체를 생성한 스레드에서만
    그 객체를 써야 하는 STA 제약을 지키기 위해서다.

    포트 생성은 **첫 작업이 들어올 때 지연 실행**되고, 실패하면 그 작업만 에러로
    끝난다. 예전 구현은 스레드 진입 시점에 팩토리를 부르고 예외를 잡지 않아서,
    AutoCAD 가 없는 환경에서 워커가 즉사하고 이후 모든 `call` 이 영구 블록됐다.
    """

    def __init__(
        self,
        port_factory: Callable[[], AcadPort],
        *,
        default_timeout: float | None = DEFAULT_CALL_TIMEOUT,
    ) -> None:
        self._jobs: "queue.Queue[tuple[Callable[[AcadPort], Any], queue.Queue]]" = queue.Queue()
        self._port_factory = port_factory
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._started = False
        self._lock = threading.Lock()
        self._default_timeout = default_timeout

    def start(self) -> None:
        with self._lock:
            if not self._started:
                self._thread.start()
                self._started = True

    def _run(self) -> None:
        self._co_initialize()
        port: AcadPort | None = None
        while True:
            fn, result_q = self._jobs.get()
            try:
                if port is None:
                    port = self._port_factory()
                result_q.put(("ok", fn(port)))
            except Exception as exc:  # noqa: BLE001 - 워커 경계에서 예외를 결과로 변환해 호출 스레드로 전달
                # 포트 생성 자체가 실패했으면 `port` 는 여전히 None 이므로 다음
                # 호출에서 다시 시도한다 (그 사이 AutoCAD 를 띄웠을 수 있다).
                result_q.put(("error", exc))

    @staticmethod
    def _co_initialize() -> None:
        """워커 스레드를 COM 아파트먼트에 등록한다.

        메인 스레드가 아닌 곳에서 COM 을 쓰려면 그 스레드에서 `CoInitialize` 를
        호출해야 한다. pywin32 가 암묵적으로 해주는 경우도 있지만 보장되지 않고,
        빠뜨리면 `CoInitialize has not been called` 로 실패한다.
        비-Windows 나 pythoncom 부재 시에는 조용히 넘어간다.
        """
        if sys.platform != "win32":
            return
        try:
            import pythoncom

            pythoncom.CoInitialize()
        except Exception:
            pass

    def call(self, fn: Callable[[AcadPort], T], *, timeout: float | None = -1.0) -> T:
        """워커 스레드에서 `fn(port)` 를 실행하고 결과를 돌려준다.

        `timeout` 을 생략하면 생성 시 지정한 기본값을 쓴다. `None` 은 무한 대기.
        """
        self.start()
        effective = self._default_timeout if timeout == -1.0 else timeout
        result_q: "queue.Queue[tuple[str, Any]]" = queue.Queue()
        self._jobs.put((fn, result_q))
        try:
            status, value = result_q.get(timeout=effective)
        except queue.Empty:
            raise AcadCallTimeout(
                f"AutoCAD 호출이 {effective}초 안에 끝나지 않았습니다. "
                "AutoCAD 가 대화상자를 띄워 입력을 기다리고 있는지 확인하세요 "
                "(모달 대화상자는 COM 호출을 무한정 붙잡습니다)."
            ) from None
        if status == "error":
            raise value
        return value
