"""COM은 STA다 — ComWorker가 모든 호출을 단일 스레드로 직렬화하는지,
그리고 죽은 연결을 만나면 재연결하는지 검증한다."""

from __future__ import annotations

import threading

from acad_assist.com import ComWorker

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
