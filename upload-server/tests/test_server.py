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
        generated_dir=str(tmp_path / "generated"),
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


async def test_download_returns_bytes_with_content_type(client):
    test_client, config = client
    tool_dir = Path(config.generated_dir) / "comfyui"
    tool_dir.mkdir(parents=True)
    (tool_dir / "a.png").write_bytes(b"png-bytes")

    resp = await test_client.get("/generated/comfyui/a.png", headers={"Authorization": "Bearer test-key"})

    assert resp.status == 200
    assert await resp.read() == b"png-bytes"
    assert resp.content_type == "image/png"


async def test_download_rejects_wrong_auth(client):
    test_client, config = client
    tool_dir = Path(config.generated_dir) / "comfyui"
    tool_dir.mkdir(parents=True)
    (tool_dir / "a.png").write_bytes(b"png-bytes")

    resp = await test_client.get("/generated/comfyui/a.png", headers={"Authorization": "Bearer wrong"})

    assert resp.status == 401


async def test_download_missing_file_returns_404(client):
    test_client, _ = client

    resp = await test_client.get("/generated/comfyui/nope.png", headers={"Authorization": "Bearer test-key"})

    assert resp.status == 404


async def test_download_blocks_traversal_via_tool(client):
    test_client, _ = client

    resp = await test_client.get("/generated/../secret.txt", headers={"Authorization": "Bearer test-key"})

    # aiohttp가 라우팅 전에 "/generated/../secret.txt"를 "/secret.txt"로 정규화해서
    # 이 라우트 자체에 안 걸릴 수도 있다(그럼 404) — 어느 쪽이든 실제 생성 파일이 아닌
    # 걸 200으로 돌려주지만 않으면 된다.
    assert resp.status in (403, 404)
