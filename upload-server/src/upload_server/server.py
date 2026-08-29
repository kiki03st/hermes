"""POST /upload — 멀티파트 파일을 받아 inbox에 저장하고 {path, note}를 돌려준다.
인증은 게이트웨이와 같은 Bearer 키를 그대로 재사용한다(별도 키 발급 없음)."""

from __future__ import annotations

import asyncio
import logging
import mimetypes
from pathlib import Path

from aiohttp import web

from .config import Config
from .storage import resolve_generated_path, save_upload
from .sweep import sweep_loop

logger = logging.getLogger(__name__)

_READ_CHUNK_BYTES = 1024 * 1024


def make_app(config: Config) -> web.Application:
    app = web.Application(client_max_size=config.max_upload_bytes + 1024 * 1024)
    app["config"] = config
    app.router.add_post("/upload", handle_upload)
    app.router.add_get("/generated/{tool}/{filename}", handle_download)
    app.on_startup.append(_start_sweep)
    app.on_cleanup.append(_stop_sweep)
    return app


def _retention_note(retention_days: int) -> str:
    return (
        f"(이 파일은 {retention_days}일 후 자동 삭제됩니다. "
        "계속 보관하려면 다른 위치로 옮겨두세요.)"
    )


async def handle_upload(request: web.Request) -> web.Response:
    config: Config = request.app["config"]

    if request.headers.get("Authorization") != f"Bearer {config.api_key}":
        return web.json_response({"error": "인증 실패"}, status=401)

    if not request.content_type.startswith("multipart/"):
        return web.json_response({"error": "multipart/form-data 요청이 아님"}, status=400)

    reader = await request.multipart()
    field = await reader.next()
    if field is None or field.name != "file":
        return web.json_response({"error": "file 파트를 찾을 수 없음"}, status=400)

    original_name = field.filename or "file"
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = await field.read_chunk(_READ_CHUNK_BYTES)
        if not chunk:
            break
        total += len(chunk)
        if total > config.max_upload_bytes:
            return web.json_response({"error": "파일 크기 초과"}, status=400)
        chunks.append(chunk)

    if total == 0:
        return web.json_response({"error": "빈 파일"}, status=400)

    target = save_upload(Path(config.inbox_dir), original_name, b"".join(chunks))
    logger.info("upload: saved %s (%d bytes)", target, total)

    return web.json_response({"path": str(target), "note": _retention_note(config.retention_days)})


async def handle_download(request: web.Request) -> web.Response:
    """GET /generated/{tool}/{filename} — 생성기(comfyui-bridge 등)가 만든 파일을 폰
    앱에 서빙한다. 인증은 업로드와 같은 Bearer 키(설계 문서 §다운로드 라우트 — 별도
    키 발급 없음). 경로 검증은 전부 `resolve_generated_path`에 위임한다."""
    config: Config = request.app["config"]

    if request.headers.get("Authorization") != f"Bearer {config.api_key}":
        return web.json_response({"error": "인증 실패"}, status=401)

    tool = request.match_info["tool"]
    filename = request.match_info["filename"]
    target = resolve_generated_path(Path(config.generated_dir), tool, filename)
    if target is None:
        return web.json_response({"error": "잘못된 경로"}, status=403)
    if not target.is_file():
        return web.json_response({"error": "파일을 찾을 수 없음"}, status=404)

    content_type, _ = mimetypes.guess_type(target.name)
    return web.Response(body=target.read_bytes(), content_type=content_type or "application/octet-stream")


async def _start_sweep(app: web.Application) -> None:
    config: Config = app["config"]
    app["sweep_task"] = asyncio.create_task(
        sweep_loop(Path(config.inbox_dir), config.retention_days, config.sweep_interval_seconds),
    )


async def _stop_sweep(app: web.Application) -> None:
    task = app.get("sweep_task")
    if task:
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass
