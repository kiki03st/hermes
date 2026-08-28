from pathlib import Path

import pytest
from aiohttp import FormData
from aiohttp.test_utils import TestClient, TestServer

from upload_server.config import Config
from upload_server.server import make_app


def _make_config(tmp_path: Path, **overrides) -> Config:
    base = dict(
        bind_host="127.0.0.1",
        bind_port=0,
        api_key="test-key",
        inbox_dir=str(tmp_path / "inbox"),
        retention_days=14,
        max_upload_bytes=1024,
        sweep_interval_seconds=3600,
    )
    base.update(overrides)
    return Config(**base)


@pytest.fixture
async def client(tmp_path):
    config = _make_config(tmp_path)
    app = make_app(config)
    server = TestServer(app)
    test_client = TestClient(server)
    await test_client.start_server()
    yield test_client, config
    await test_client.close()


async def test_upload_success_saves_file_and_returns_path_and_note(client):
    test_client, config = client
    form = FormData()
    form.add_field("file", b"hello", filename="a.txt", content_type="text/plain")

    resp = await test_client.post("/upload", data=form, headers={"Authorization": "Bearer test-key"})

    assert resp.status == 200
    body = await resp.json()
    assert body["path"].endswith("_a.txt")
    assert Path(body["path"]).read_bytes() == b"hello"
    assert "14" in body["note"]


async def test_upload_rejects_wrong_auth(client):
    test_client, _ = client
    form = FormData()
    form.add_field("file", b"hello", filename="a.txt", content_type="text/plain")

    resp = await test_client.post("/upload", data=form, headers={"Authorization": "Bearer wrong"})

    assert resp.status == 401


async def test_upload_rejects_oversized_file(client):
    test_client, config = client
    form = FormData()
    oversized = b"x" * (config.max_upload_bytes + 1)
    form.add_field("file", oversized, filename="big.bin", content_type="application/octet-stream")

    resp = await test_client.post("/upload", data=form, headers={"Authorization": "Bearer test-key"})

    assert resp.status == 400


async def test_upload_rejects_missing_file_field(client):
    test_client, _ = client
    form = FormData()
    form.add_field("not_file", "irrelevant")

    resp = await test_client.post("/upload", data=form, headers={"Authorization": "Bearer test-key"})

    assert resp.status == 400
