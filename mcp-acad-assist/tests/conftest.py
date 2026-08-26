from __future__ import annotations

import pytest

from acad_assist.com import ComWorker

from .fakes import FakeAcadPort


@pytest.fixture
def fake_port() -> FakeAcadPort:
    return FakeAcadPort()


@pytest.fixture
def worker(fake_port: FakeAcadPort) -> ComWorker:
    return ComWorker(lambda: fake_port)
