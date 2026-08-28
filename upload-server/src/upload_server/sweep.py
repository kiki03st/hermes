"""오래된 업로드 자동 정리. inbox_dir 밖으로 옮겨진(=에이전트가 장기보관하기로
판단한) 파일은 여기서 절대 건드리지 않는다 — 그게 이 기능의 "장기 보관 여부" 판단
메커니즘의 전부다."""

from __future__ import annotations

import asyncio
import logging
import time
from pathlib import Path

logger = logging.getLogger(__name__)


def sweep_once(inbox_dir: Path, retention_days: int) -> list[Path]:
    if not inbox_dir.exists():
        return []
    cutoff = time.time() - retention_days * 86400
    removed: list[Path] = []
    for path in inbox_dir.iterdir():
        if not path.is_file():
            continue
        if path.stat().st_mtime < cutoff:
            path.unlink()
            removed.append(path)
    return removed


async def sweep_loop(inbox_dir: Path, retention_days: int, interval_seconds: int) -> None:
    while True:
        try:
            removed = sweep_once(inbox_dir, retention_days)
            if removed:
                logger.info("sweep: removed %d expired file(s)", len(removed))
        except Exception:
            logger.exception("sweep: unexpected error")
        await asyncio.sleep(interval_seconds)
