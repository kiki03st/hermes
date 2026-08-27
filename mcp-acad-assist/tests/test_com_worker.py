"""COM은 STA다 — ComWorker가 모든 호출을 단일 스레드로 직렬화하는지,
그리고 죽은 연결을 만나면 재연결하는지 검증한다."""

from __future__ import annotations

import threading

import pytest

from acad_assist.com import AcadCallTimeout, ComWorker

from .fakes import FakeAcadPort


def test_all_calls_run_on_the_same_worker_thread():
    fake_port = FakeAcadPort()
    worker = ComWorker(lambda: fake_port)

    thread_ids = [worker.call(lambda port: threading.get_ident()) for _ in range(5)]

    assert len(set(thread_ids)) == 1
    assert thread_ids[0] != threading.get_ident()


def test_exception_inside_call_propagates_to_caller():
    fake_port = FakeAcadPort()
    worker = ComWorker(lambda: fake_port)

    def _boom(port):
        raise ValueError("도면을 찾을 수 없음")

    try:
        worker.call(_boom)
    except ValueError as exc:
        assert str(exc) == "도면을 찾을 수 없음"
    else:
        raise AssertionError("expected ValueError to propagate")


def test_active_document_raises_when_connection_dead_and_caller_can_reconnect():
    fake_port = FakeAcadPort()
    fake_port.alive = False
    worker = ComWorker(lambda: fake_port)

    def _access(port):
        if not port.is_alive():
            port.reconnect()
        return port.active_document()

    doc = worker.call(_access)

    assert doc is fake_port.document
    assert fake_port.reconnect_count == 1


def test_port_factory_failure_does_not_kill_the_worker():
    """예전 구현은 스레드 진입 시점에 팩토리를 부르고 예외를 잡지 않았다 —
    AutoCAD 가 없는 환경에서 워커가 즉사하고 이후 모든 call 이 영구 블록됐다.
    지금은 그 호출만 에러로 끝나고 다음 호출에서 다시 시도한다."""
    attempts: list[int] = []
    fake_port = FakeAcadPort()

    def _factory():
        attempts.append(1)
        if len(attempts) == 1:
            raise RuntimeError("AutoCAD가 설치되지 않았습니다")
        return fake_port

    worker = ComWorker(_factory, default_timeout=5.0)

    with pytest.raises(RuntimeError, match="AutoCAD가 설치되지 않았습니다"):
        worker.call(lambda port: port.active_document())

    # 두 번째 호출은 팩토리를 다시 부르고 성공한다 (그 사이 AutoCAD 를 띄운 상황).
    assert worker.call(lambda port: port.active_document()) is fake_port.document
    assert len(attempts) == 2


def test_port_is_created_once_when_the_factory_succeeds():
    calls: list[int] = []
    fake_port = FakeAcadPort()

    def _factory():
        calls.append(1)
        return fake_port

    worker = ComWorker(_factory, default_timeout=5.0)
    for _ in range(3):
        worker.call(lambda port: port.is_alive())

    assert len(calls) == 1


def test_call_timeout_raises_instead_of_blocking_forever():
    """모달 대화상자가 뜨면 COM 호출이 무한정 붙잡힌다. MCP 서버에서는 그게
    서버 전체 정지로 보이므로, 기다리는 것을 포기할 수 있어야 한다."""
    released = threading.Event()
    worker = ComWorker(lambda: FakeAcadPort(), default_timeout=0.1)

    def _hang(port):
        released.wait(timeout=10.0)
        return "done"

    with pytest.raises(AcadCallTimeout) as exc:
        worker.call(_hang)
    assert "대화상자" in str(exc.value)
    released.set()  # 워커 스레드를 풀어준다


def test_explicit_timeout_overrides_the_default():
    worker = ComWorker(lambda: FakeAcadPort(), default_timeout=0.01)
    # 기본값은 아주 짧지만, 호출별로 넉넉히 주면 통과한다.
    assert worker.call(lambda port: port.is_alive(), timeout=5.0) is True


def test_none_timeout_means_wait_indefinitely():
    worker = ComWorker(lambda: FakeAcadPort(), default_timeout=None)
    assert worker.call(lambda port: port.is_alive()) is True
