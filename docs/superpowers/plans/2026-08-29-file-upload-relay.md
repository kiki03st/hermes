# 폰 → Hermes 파일/이미지 업로드 릴레이 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 폰에서 이미지/파일을 선택해 채팅에 첨부하듯 보내면, 별도 업로드 서버가 그
파일을 서버 컴퓨터 디스크에 저장하고, 그 경로를 채팅 텍스트에 실어 Hermes
에이전트에게 전달한다 — 에이전트는 이미 켜져있는 `file`/`vision_analyze` 툴로
그 경로를 읽는다.

**Architecture:** 새 독립 파이썬 서비스 `upload-server/`(aiohttp, 게이트웨이와 완전히
분리된 프로세스, 게이트웨이 코드 무수정)가 `POST /upload`(멀티파트, Bearer 인증)를
받아 `uploads/inbox/<uuid8>_<파일명>`에 저장하고 `{path, note}`를 응답한다. 백그라운드
`asyncio` 태스크가 주기적으로 `inbox/`에서 오래된 파일을 지운다(에이전트가 다른
곳으로 옮긴 파일은 자동으로 보존됨). 안드로이드 앱은 새 `FileUploadClient`로 이
서버에 업로드한 뒤, 응답의 `path`/`note`를 기존 `ChatConversationState.submit()`
텍스트에 실어 그대로 `/v1/runs`로 보낸다 — `/v1/runs` 프로토콜이나 게이트웨이 쪽은
전혀 안 바뀐다.

**Tech Stack:** upload-server = Python 3.10+ / aiohttp / pytest. Android = 기존
Kotlin/Compose/`java.net.HttpURLConnection`(신규 의존성 없음), kotlinx.serialization
(`HermesJson`, 기존 것 재사용).

**Spec:** `docs/superpowers/specs/2026-08-29-file-upload-relay-design.md`

## Global Constraints

- 게이트웨이(`hermes-agent`) 소스/설정은 이 작업으로 **한 줄도 수정하지 않는다**
  (spec 배경 — `hermes update`가 git pull 기반이라 로컬 수정이 stash되어 날아갈
  위험).
- 업로드 서버는 LAN 전용 — 외부 노출/터널링 로직을 만들지 않는다(spec 범위 밖).
- 파일 타입별 저장 경로 분리 없음 — `uploads/inbox/` 플랫 구조만 쓴다(spec에서
  기각된 안).
- Android 쪽에 OkHttp 등 새 HTTP 라이브러리를 추가하지 않는다 — 기존
  `UrlConnectionHttpTransport`와 같은 방식으로 순수 `java.net.HttpURLConnection`만
  쓴다.
- 인증은 게이트웨이와 동일한 Bearer API 키를 재사용한다 — 별도 키 발급 로직을
  만들지 않는다.
- 기본값(모두 환경변수/설정으로 바꿀 수 있게 하되 하드코딩된 기본값은 이 값으로):
  보관기간 14일, 파일당 최대 100MB, 스윕 주기 3600초(1시간), 포트 8643.

---

## Task 1: `upload-server` 스캐폴드 + 파일명 새니타이즈/저장

**Files:**
- Create: `upload-server/pyproject.toml`
- Create: `upload-server/src/upload_server/__init__.py`
- Create: `upload-server/src/upload_server/storage.py`
- Test: `upload-server/tests/test_storage.py`

**Interfaces:**
- Produces: `sanitize_filename(name: str) -> str`, `save_upload(inbox_dir: Path,
  original_name: str, data: bytes) -> Path` — Task 3(`server.py`)이 그대로 씀.

- [ ] **Step 1: `pyproject.toml` 작성**

```toml
[project]
name = "hermes-upload-server"
version = "0.1.0"
description = "폰에서 Hermes 에이전트로 파일/이미지를 릴레이하는 독립 업로드 서버"
requires-python = ">=3.10"
dependencies = ["aiohttp>=3.9,<4"]

[project.optional-dependencies]
dev = ["pytest>=8", "pytest-asyncio>=0.23"]

[project.scripts]
hermes-upload-server = "upload_server.__main__:main"

[build-system]
requires = ["setuptools>=68"]
build-backend = "setuptools.build_meta"

[tool.setuptools.packages.find]
where = ["src"]

[tool.pytest.ini_options]
asyncio_mode = "auto"
```

- [ ] **Step 2: `src/upload_server/__init__.py` 작성**

```python
"""폰 -> Hermes 에이전트 파일/이미지 업로드 릴레이. 게이트웨이(hermes-agent)와는
완전히 독립된 프로세스 — 저장만 담당하고, 읽는 건 에이전트의 기존 file/vision_analyze
툴이 한다."""

__version__ = "0.1.0"
```

- [ ] **Step 3: 실패하는 테스트 작성 (`tests/test_storage.py`)**

```python
from pathlib import Path

from upload_server.storage import sanitize_filename, save_upload


def test_sanitize_filename_strips_directory_components():
    assert sanitize_filename("../../etc/passwd") == "passwd"
    assert sanitize_filename("C:\\Users\\x\\photo.jpg") == "photo.jpg"


def test_sanitize_filename_replaces_unsafe_characters():
    assert sanitize_filename('weird name?*.png') == "weird_name__.png"


def test_sanitize_filename_falls_back_when_empty():
    assert sanitize_filename("") == "file"
    assert sanitize_filename("   ") == "file"


def test_save_upload_writes_bytes_with_uuid_prefix(tmp_path: Path):
    target = save_upload(tmp_path, "photo.jpg", b"hello-bytes")

    assert target.exists()
    assert target.read_bytes() == b"hello-bytes"
    assert target.name.endswith("_photo.jpg")
    assert target.parent == tmp_path


def test_save_upload_distinct_calls_produce_distinct_files(tmp_path: Path):
    first = save_upload(tmp_path, "a.txt", b"1")
    second = save_upload(tmp_path, "a.txt", b"2")

    assert first != second
    assert first.exists() and second.exists()
```

- [ ] **Step 4: 테스트 실행해서 실패 확인**

Run (upload-server 디렉터리에서): `python -m pip install -e ".[dev]" && python -m pytest tests/test_storage.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'upload_server.storage'`

- [ ] **Step 5: `src/upload_server/storage.py` 최소 구현**

```python
"""업로드 파일 저장 — 경로 traversal 방지용 이름 새니타이즈 + uuid 접두어로 충돌 방지."""

from __future__ import annotations

import re
import uuid
from pathlib import Path

_UNSAFE_CHARS = re.compile(r"[^A-Za-z0-9._-]")


def sanitize_filename(name: str) -> str:
    """디렉터리 구성요소를 전부 떼어내고(경로 traversal 방지), 영숫자/`.`/`_`/`-`
    외 문자는 `_`로 바꾼다. 빈 이름은 `file`로 대체한다."""
    base = Path(name).name.strip()
    if not base:
        return "file"
    return _UNSAFE_CHARS.sub("_", base)[:200]


def save_upload(inbox_dir: Path, original_name: str, data: bytes) -> Path:
    """[inbox_dir]에 `<uuid8자리>_<새니타이즈된 원본이름>`으로 저장하고 그 경로를 돌려준다."""
    inbox_dir.mkdir(parents=True, exist_ok=True)
    safe_name = sanitize_filename(original_name)
    stored_name = f"{uuid.uuid4().hex[:8]}_{safe_name}"
    target = inbox_dir / stored_name
    target.write_bytes(data)
    return target
```

- [ ] **Step 6: 테스트 실행해서 통과 확인**

Run: `python -m pytest tests/test_storage.py -v`
Expected: PASS (5 tests)

- [ ] **Step 7: 커밋**

```bash
git add upload-server/pyproject.toml upload-server/src/upload_server/__init__.py upload-server/src/upload_server/storage.py upload-server/tests/test_storage.py
git commit -m "feat(upload-server): scaffold project, add filename sanitize/save"
```

---

## Task 2: 보관기간 스윕(정리) 로직

**Files:**
- Create: `upload-server/src/upload_server/sweep.py`
- Test: `upload-server/tests/test_sweep.py`

**Interfaces:**
- Consumes: 없음(순수 `pathlib`/`time` 기반, Task 1과 무관).
- Produces: `sweep_once(inbox_dir: Path, retention_days: int) -> list[Path]`,
  `async def sweep_loop(inbox_dir: Path, retention_days: int, interval_seconds: int)
  -> None` — Task 3(`server.py`)의 `on_startup` 훅이 `sweep_loop`을 백그라운드
  태스크로 돌린다.

- [ ] **Step 1: 실패하는 테스트 작성 (`tests/test_sweep.py`)**

```python
import os
import time
from pathlib import Path

from upload_server.sweep import sweep_once


def _touch_with_age(path: Path, days_old: float) -> None:
    path.write_bytes(b"x")
    old_time = time.time() - days_old * 86400
    os.utime(path, (old_time, old_time))


def test_sweep_removes_files_older_than_retention(tmp_path: Path):
    old_file = tmp_path / "old.jpg"
    _touch_with_age(old_file, days_old=15)

    removed = sweep_once(tmp_path, retention_days=14)

    assert old_file in removed
    assert not old_file.exists()


def test_sweep_keeps_recent_files(tmp_path: Path):
    recent_file = tmp_path / "recent.jpg"
    _touch_with_age(recent_file, days_old=1)

    removed = sweep_once(tmp_path, retention_days=14)

    assert removed == []
    assert recent_file.exists()


def test_sweep_ignores_files_outside_inbox_dir(tmp_path: Path):
    inbox = tmp_path / "inbox"
    inbox.mkdir()
    outside = tmp_path / "kept" / "important.jpg"
    outside.parent.mkdir()
    _touch_with_age(outside, days_old=30)

    removed = sweep_once(inbox, retention_days=14)

    assert removed == []
    assert outside.exists()


def test_sweep_on_missing_dir_returns_empty(tmp_path: Path):
    missing = tmp_path / "does-not-exist"

    assert sweep_once(missing, retention_days=14) == []
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `python -m pytest tests/test_sweep.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'upload_server.sweep'`

- [ ] **Step 3: `src/upload_server/sweep.py` 구현**

```python
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
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `python -m pytest tests/test_sweep.py -v`
Expected: PASS (4 tests)

- [ ] **Step 5: 커밋**

```bash
git add upload-server/src/upload_server/sweep.py upload-server/tests/test_sweep.py
git commit -m "feat(upload-server): add retention sweep for the inbox directory"
```

---

## Task 3: 설정 + `/upload` aiohttp 핸들러 + 엔트리포인트

**Files:**
- Create: `upload-server/src/upload_server/config.py`
- Create: `upload-server/src/upload_server/server.py`
- Create: `upload-server/src/upload_server/__main__.py`
- Test: `upload-server/tests/test_server.py`

**Interfaces:**
- Consumes: `sanitize_filename`/`save_upload` (Task 1), `sweep_loop` (Task 2).
- Produces: `Config` dataclass, `make_app(config: Config) -> aiohttp.web.Application`
  — Task 4의 README가 실행법을 문서화할 때 이 진입점(`__main__.main`)을 참조한다.

- [ ] **Step 1: `src/upload_server/config.py` 작성**

```python
"""환경변수 기반 설정. 게이트웨이의 API_SERVER_HOST/PORT 패턴(docs/setup-windows.md)과
같은 방식 — LAN NIC 주소를 명시적으로 바인딩하고, 기본값은 이 파일에 정의된 것만
쓴다(하드코딩된 특정 IP 없음)."""

from __future__ import annotations

import os
from dataclasses import dataclass

DEFAULT_PORT = 8643
DEFAULT_RETENTION_DAYS = 14
DEFAULT_MAX_UPLOAD_BYTES = 100 * 1024 * 1024
DEFAULT_SWEEP_INTERVAL_SECONDS = 3600


@dataclass(frozen=True)
class Config:
    bind_host: str
    bind_port: int
    api_key: str
    inbox_dir: str
    retention_days: int
    max_upload_bytes: int
    sweep_interval_seconds: int

    @staticmethod
    def from_env() -> "Config":
        return Config(
            bind_host=os.environ.get("UPLOAD_SERVER_HOST", "0.0.0.0"),
            bind_port=int(os.environ.get("UPLOAD_SERVER_PORT", str(DEFAULT_PORT))),
            api_key=os.environ["UPLOAD_SERVER_API_KEY"],
            inbox_dir=os.environ.get("UPLOAD_SERVER_INBOX_DIR", "./uploads/inbox"),
            retention_days=int(
                os.environ.get("UPLOAD_SERVER_RETENTION_DAYS", str(DEFAULT_RETENTION_DAYS)),
            ),
            max_upload_bytes=int(
                os.environ.get("UPLOAD_SERVER_MAX_BYTES", str(DEFAULT_MAX_UPLOAD_BYTES)),
            ),
            sweep_interval_seconds=int(
                os.environ.get(
                    "UPLOAD_SERVER_SWEEP_INTERVAL_SECONDS", str(DEFAULT_SWEEP_INTERVAL_SECONDS),
                ),
            ),
        )
```

- [ ] **Step 2: 실패하는 테스트 작성 (`tests/test_server.py`)**

```python
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
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `python -m pytest tests/test_server.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'upload_server.server'`

- [ ] **Step 4: `src/upload_server/server.py` 구현**

```python
"""POST /upload — 멀티파트 파일을 받아 inbox에 저장하고 {path, note}를 돌려준다.
인증은 게이트웨이와 같은 Bearer 키를 그대로 재사용한다(별도 키 발급 없음)."""

from __future__ import annotations

import asyncio
import logging
from pathlib import Path

from aiohttp import web

from .config import Config
from .storage import save_upload
from .sweep import sweep_loop

logger = logging.getLogger(__name__)

_READ_CHUNK_BYTES = 1024 * 1024


def make_app(config: Config) -> web.Application:
    app = web.Application(client_max_size=config.max_upload_bytes + 1024 * 1024)
    app["config"] = config
    app.router.add_post("/upload", handle_upload)
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
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `python -m pytest tests/test_server.py -v`
Expected: PASS (4 tests)

- [ ] **Step 6: `src/upload_server/__main__.py` 작성**

```python
"""`python -m upload_server` 또는 `hermes-upload-server`(pyproject의 entry point)로
기동."""

from __future__ import annotations

import logging

from aiohttp import web

from .config import Config
from .server import make_app


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    config = Config.from_env()
    app = make_app(config)
    logging.getLogger(__name__).info(
        "upload-server listening on %s:%d (inbox=%s)", config.bind_host, config.bind_port, config.inbox_dir,
    )
    web.run_app(app, host=config.bind_host, port=config.bind_port)


if __name__ == "__main__":
    main()
```

- [ ] **Step 7: 전체 테스트 실행**

Run: `python -m pytest -v`
Expected: PASS (모든 테스트, Task 1+2+3 합쳐 13개)

- [ ] **Step 8: 커밋**

```bash
git add upload-server/src/upload_server/config.py upload-server/src/upload_server/server.py upload-server/src/upload_server/__main__.py upload-server/tests/test_server.py
git commit -m "feat(upload-server): add /upload aiohttp handler and entrypoint"
```

---

## Task 4: `upload-server` README

**Files:**
- Create: `upload-server/README.md`

**Interfaces:** 없음(문서만).

- [ ] **Step 1: README 작성**

```markdown
# hermes-upload-server

폰 Hermes 앱에서 이미지/파일을 받아 이 컴퓨터 디스크에 저장하는 독립 서버.
`hermes-agent` 게이트웨이와 완전히 별개 프로세스 — 게이트웨이 코드/설정을 전혀
건드리지 않는다. 저장된 파일은 게이트웨이 쪽 에이전트가 이미 가진 `file`/
`vision_analyze` 툴로 읽는다(경로는 채팅 텍스트로 전달됨, 앱 쪽 구현 참고).

## 설치 및 실행

```bash
cd upload-server
python -m pip install -e ".[dev]"

# 필수: 폰 앱이 쓰는 것과 같은 API_SERVER_KEY
set UPLOAD_SERVER_API_KEY=<게이트웨이 API_SERVER_KEY와 동일한 값>
# 선택 (기본값은 config.py 참고: host=0.0.0.0, port=8643, retention=14일, max=100MB)
set UPLOAD_SERVER_HOST=172.30.1.101
set UPLOAD_SERVER_INBOX_DIR=C:\hermes\uploads\inbox

hermes-upload-server
```

`docs/setup-windows.md`의 게이트웨이 방화벽 설정과 동일한 패턴으로, 이 서버의
포트(기본 8643)도 폰이 붙는 Wi-Fi NIC 주소로만 인바운드를 열어야 한다 — 공인 IP
NIC는 열지 않는다.

## 테스트

```bash
python -m pytest -v
```
```

- [ ] **Step 2: 커밋**

```bash
git add upload-server/README.md
git commit -m "docs(upload-server): add run/test instructions"
```

---

## Task 5: Android — 설정에 업로드 서버 URL 추가

**Files:**
- Modify: `android/app/src/main/kotlin/com/hermes/app/SettingsStore.kt`
- Modify: `android/app/build.gradle.kts:28-35` (기존 `buildConfigField` 블록 옆)
- Modify: `android/app/src/main/kotlin/com/hermes/app/ui/settings/SettingsScreen.kt`
- Modify: `android/local.properties` (커밋 대상 아님 — `.gitignore` 확인 후 로컬만)

**Interfaces:**
- Produces: `HermesSettings.uploadServerUrl: String`, `BuildConfig.
  DEFAULT_UPLOAD_SERVER_URL: String` — Task 6/8이 이 값을 읽는다.

- [ ] **Step 1: `build.gradle.kts`에 buildConfigField 추가**

`app/build.gradle.kts`의 기존 블록(현재 27-35줄 부근, `DEFAULT_SERVER_URL`/
`DEFAULT_API_KEY` 정의부) 바로 아래에 추가:

```kotlin
        buildConfigField(
            "String", "DEFAULT_UPLOAD_SERVER_URL",
            "\"${localProperties.getProperty("hermes.uploadServerUrl", "")}\"",
        )
```

- [ ] **Step 2: `SettingsStore.kt` 수정**

`HermesSettings`에 필드 추가:

```kotlin
data class HermesSettings(
    val serverUrl: String = "",
    val apiKey: String = "",
    val uploadServerUrl: String = "",
    val longTermMemoryKey: String = "",
    val wakeWordEnabled: Boolean = false,
)
```

`SettingsStore` 클래스 안, 기존 키 옆에 추가:

```kotlin
    private val uploadServerUrlKey = stringPreferencesKey("upload_server_url")
```

`settingsFlow` 안에 필드 추가:

```kotlin
            uploadServerUrl = prefs[uploadServerUrlKey] ?: BuildConfig.DEFAULT_UPLOAD_SERVER_URL,
```

`update()` 시그니처를 확장(기존 유일한 호출부는 `SettingsScreen.kt`, Step 3에서 같이
고침):

```kotlin
    suspend fun update(serverUrl: String, apiKey: String, uploadServerUrl: String) {
        context.dataStore.edit { prefs ->
            prefs[serverUrlKey] = serverUrl
            prefs[apiKeyKey] = apiKey
            prefs[uploadServerUrlKey] = uploadServerUrl
        }
    }
```

- [ ] **Step 3: `SettingsScreen.kt`에 입력란 추가**

`serverUrlInput`/`apiKeyInput` 선언 옆에 추가:

```kotlin
    var uploadServerUrlInput by remember { mutableStateOf("") }
```

`LaunchedEffect(settings)` 블록 안에 추가:

```kotlin
            uploadServerUrlInput = it.uploadServerUrl
```

API 키 `OutlinedTextField` 바로 아래에 새 필드 추가:

```kotlin
            OutlinedTextField(
                value = uploadServerUrlInput,
                onValueChange = { uploadServerUrlInput = it },
                label = { Text("업로드 서버 URL (예: http://192.168.0.10:8643)") },
                modifier = Modifier.fillMaxWidth(),
            )
```

저장 버튼의 `onClick`을 새 시그니처에 맞게 수정:

```kotlin
                Button(onClick = {
                    scope.launch { settingsStore.update(serverUrlInput, apiKeyInput, uploadServerUrlInput) }
                }) {
```

- [ ] **Step 4: `local.properties`에 개발용 기본값 추가 (커밋 안 됨)**

```
hermes.uploadServerUrl=http://172.30.1.101:8643
```

- [ ] **Step 5: 빌드 확인**

Run: `cd android && .\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (이 태스크엔 새 로직 없이 배선만 있어 별도 유닛테스트
없음 — `SettingsStore`는 기존에도 테스트가 없는 클래스, 컴파일 통과 + Task 9의
실기기 검증에서 설정화면에 새 필드가 뜨고 저장되는지 확인).

- [ ] **Step 6: 커밋**

```bash
git add android/app/build.gradle.kts android/app/src/main/kotlin/com/hermes/app/SettingsStore.kt android/app/src/main/kotlin/com/hermes/app/ui/settings/SettingsScreen.kt
git commit -m "feat(android): add upload server URL setting"
```

---

## Task 6: Android — `FileUploadClient`

**Files:**
- Create: `android/app/src/main/kotlin/com/hermes/app/FileUploadClient.kt`
- Test: `android/app/src/test/kotlin/com/hermes/app/FileUploadClientTest.kt`

**Interfaces:**
- Consumes: `com.hermes.shared.HermesJson`(기존, `RunsClient.kt`가 쓰는 것과 동일).
- Produces: `sealed interface UploadOutcome { Success(path, note); Failure(statusCode,
  message) }`, `class FileUploadClient(uploadServerUrl: () -> String, apiKey: () ->
  String).upload(fileName: String, mimeType: String, bytes: ByteArray): UploadOutcome`
  — Task 8(`ChatScreen.kt`)이 그대로 씀.

- [ ] **Step 1: 실패하는 테스트 작성**

JDK 내장 `com.sun.net.httpserver.HttpServer`로 로컬 서버를 띄워 실제 HTTP 왕복을
검증한다(새 테스트 의존성 없음 — `UrlConnectionHttpTransportTest.kt`와 같은 철학).

```kotlin
package com.hermes.app

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileUploadClientTest {
    private lateinit var server: HttpServer
    private var lastAuthHeader: String? = null
    private var lastBody: ByteArray = ByteArray(0)
    private var responseStatus = 200
    private var responseBody = """{"path":"/tmp/x_a.txt","note":"note-text"}"""

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/upload") { exchange: HttpExchange ->
            lastAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            lastBody = exchange.requestBody.readBytes()
            val bytes = responseBody.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(responseStatus, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun client() = FileUploadClient(
        uploadServerUrl = { "http://127.0.0.1:${server.address.port}" },
        apiKey = { "test-key" },
    )

    @Test
    fun `upload sends bearer auth and multipart body containing the file bytes`() {
        val outcome = client().upload("a.txt", "text/plain", "hello".toByteArray())

        check(outcome is UploadOutcome.Success)
        assertEquals("/tmp/x_a.txt", outcome.path)
        assertEquals("note-text", outcome.note)
        assertEquals("Bearer test-key", lastAuthHeader)
        val bodyText = String(lastBody, Charsets.UTF_8)
        assertTrue(bodyText.contains("filename=\"a.txt\""))
        assertTrue(bodyText.contains("hello"))
    }

    @Test
    fun `upload surfaces failure on non-2xx status`() {
        responseStatus = 401
        responseBody = """{"error":"인증 실패"}"""

        val outcome = client().upload("a.txt", "text/plain", "hello".toByteArray())

        check(outcome is UploadOutcome.Failure)
        assertEquals(401, outcome.statusCode)
        assertEquals("인증 실패", outcome.message)
    }

    @Test
    fun `upload on unreachable host returns failure instead of throwing`() {
        val client = FileUploadClient(
            uploadServerUrl = { "http://127.0.0.1:1" },
            apiKey = { "k" },
        )

        val outcome = client.upload("a.txt", "text/plain", "hi".toByteArray())

        check(outcome is UploadOutcome.Failure)
        assertEquals(0, outcome.statusCode)
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `cd android && .\gradlew.bat :app:testDebugUnitTest --tests "com.hermes.app.FileUploadClientTest"`
Expected: FAIL — `Unresolved reference 'FileUploadClient'`

- [ ] **Step 3: `FileUploadClient.kt` 구현**

```kotlin
package com.hermes.app

import com.hermes.shared.HermesJson
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.Serializable

sealed interface UploadOutcome {
    data class Success(val path: String, val note: String) : UploadOutcome
    data class Failure(val statusCode: Int, val message: String) : UploadOutcome
}

@Serializable
private data class UploadResponseBody(
    val path: String? = null,
    val note: String? = null,
    val error: String? = null,
)

/**
 * 별도 업로드 서버(`upload-server/`, 게이트웨이와 무관한 독립 프로세스)에 파일을
 * multipart/form-data로 올린다. 인증은 게이트웨이가 쓰는 것과 동일한 Bearer API
 * 키를 재사용한다(설계 문서 §보안 고려 — 같은 신뢰 경계, LAN 전용, 1인 사용).
 */
class FileUploadClient(
    private val uploadServerUrl: () -> String,
    private val apiKey: () -> String,
    private val connectTimeoutMillis: Int = 10_000,
    private val readTimeoutMillis: Int = 120_000,
) {
    fun upload(fileName: String, mimeType: String, bytes: ByteArray): UploadOutcome {
        val boundary = "HermesUpload-${UUID.randomUUID()}"
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(uploadServerUrl().trimEnd('/') + "/upload")
                .openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${apiKey()}")
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")

            connection.outputStream.use { out -> writeMultipartBody(out, boundary, fileName, mimeType, bytes) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            parseResponse(status, text)
        } catch (e: Exception) {
            UploadOutcome.Failure(0, "네트워크 오류: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun parseResponse(status: Int, text: String): UploadOutcome {
        val parsed = runCatching {
            HermesJson.decodeFromString(UploadResponseBody.serializer(), text)
        }.getOrNull()

        if (status !in 200..299) {
            return UploadOutcome.Failure(status, parsed?.error ?: text.ifBlank { "업로드 실패" })
        }
        val path = parsed?.path
            ?: return UploadOutcome.Failure(status, "응답에서 path를 찾을 수 없음: $text")
        return UploadOutcome.Success(path, parsed.note ?: "")
    }

    private fun writeMultipartBody(
        out: OutputStream,
        boundary: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
    ) {
        val crlf = "\r\n"
        val safeName = fileName.substringAfterLast('/').substringAfterLast('\\').replace("\"", "")
        val header = "--$boundary$crlf" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$safeName\"$crlf" +
            "Content-Type: ${mimeType.ifBlank { "application/octet-stream" }}$crlf$crlf"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(bytes)
        out.write("$crlf--$boundary--$crlf".toByteArray(StandardCharsets.UTF_8))
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `cd android && .\gradlew.bat :app:testDebugUnitTest --tests "com.hermes.app.FileUploadClientTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋**

```bash
git add android/app/src/main/kotlin/com/hermes/app/FileUploadClient.kt android/app/src/test/kotlin/com/hermes/app/FileUploadClientTest.kt
git commit -m "feat(android): add FileUploadClient for the upload-server relay"
```

---

## Task 7: Android — 채팅에 시스템 알림(업로드 실패 등) 표시 지원

**Files:**
- Modify: `android/app/src/main/kotlin/com/hermes/app/ui/chat/ChatReducer.kt`
- Modify: `android/app/src/main/kotlin/com/hermes/app/ui/chat/ChatConversationState.kt`
- Test: `android/app/src/test/kotlin/com/hermes/app/ui/chat/ChatReducerTest.kt`

**Interfaces:**
- Produces: `ChatReducer.appendSystemNotice(messages: List<ChatMessage>, text: String):
  List<ChatMessage>`, `ChatConversationState.reportSystemNotice(text: String): Unit`
  — Task 8(`ChatScreen.kt`)이 업로드 실패 시 이걸 호출한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`ChatReducerTest.kt`에 추가(파일 끝 클래스 닫는 중괄호 바로 앞):

```kotlin
    @Test
    fun `appendSystemNotice adds a SystemNotice with the given text`() {
        val result = ChatReducer.appendSystemNotice(emptyList(), "업로드 실패: 네트워크 오류")

        val notice = result.single() as ChatMessage.SystemNotice
        assertEquals("업로드 실패: 네트워크 오류", notice.text)
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `cd android && .\gradlew.bat :app:testDebugUnitTest --tests "com.hermes.app.ui.chat.ChatReducerTest"`
Expected: FAIL — `Unresolved reference 'appendSystemNotice'`

- [ ] **Step 3: `ChatReducer.kt`에 함수 추가**

`appendUserMessage` 바로 아래에 추가:

```kotlin
    fun appendSystemNotice(messages: List<ChatMessage>, text: String): List<ChatMessage> =
        messages + ChatMessage.SystemNotice(id = newId(), text = text)
```

- [ ] **Step 4: `ChatConversationState.kt`에 메서드 추가**

`stop()` 함수 바로 아래에 추가:

```kotlin
    /** 업로드 실패 등 run 파이프라인을 안 타는 에러를 채팅에 표시한다. */
    fun reportSystemNotice(text: String) {
        messages = ChatReducer.appendSystemNotice(messages, text)
        revision++
    }
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `cd android && .\gradlew.bat :app:testDebugUnitTest --tests "com.hermes.app.ui.chat.ChatReducerTest"`
Expected: PASS (기존 테스트 전부 + 신규 1개)

- [ ] **Step 6: 커밋**

```bash
git add android/app/src/main/kotlin/com/hermes/app/ui/chat/ChatReducer.kt android/app/src/main/kotlin/com/hermes/app/ui/chat/ChatConversationState.kt android/app/src/test/kotlin/com/hermes/app/ui/chat/ChatReducerTest.kt
git commit -m "feat(android): add reportSystemNotice for out-of-band chat errors"
```

---

## Task 8: Android — `ChatScreen`에 첨부 버튼 배선

**Files:**
- Modify: `android/app/src/main/kotlin/com/hermes/app/ui/chat/ChatScreen.kt`

**Interfaces:**
- Consumes: `FileUploadClient`/`UploadOutcome`(Task 6), `ChatConversationState.
  reportSystemNotice`(Task 7), `HermesRuntime.currentSettings.{uploadServerUrl,
  apiKey}`(Task 5).
- Produces: 없음(최종 사용자 기능 — 이 태스크가 마지막 배선).

이 태스크는 Compose UI라 이 리포 기존 관례상(`ChatScreen.kt` 자체가 지금까지 무테스트)
유닛테스트를 새로 만들지 않는다 — Task 9의 실기기 검증으로 확인한다.

- [ ] **Step 1: import 추가**

파일 상단 import 블록에 추가:

```kotlin
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
```

- [ ] **Step 2: 파일 읽기 헬퍼 추가**

`ChatScreen` 함수 밖, 파일 하단(`InputBar` 뒤)에 추가:

```kotlin
private data class SelectedFile(val name: String, val mimeType: String, val bytes: ByteArray)

/** [android.provider.OpenableColumns.DISPLAY_NAME]으로 원본 파일명을 얻는다 —
 * `content://` URI엔 실제 경로가 없어 이 방법이 표준이다. */
private fun readSelectedFile(context: Context, uri: Uri): SelectedFile? {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri) ?: "application/octet-stream"
    var name = "file"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            cursor.getString(nameIndex)?.let { name = it }
        }
    }
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return SelectedFile(name, mimeType, bytes)
}
```

- [ ] **Step 3: `ChatScreen` 함수 안에 첨부 상태/런처 추가**

`val context = LocalContext.current` 바로 아래에 추가:

```kotlin
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }

    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    val selected = readSelectedFile(context, uri)
                    if (selected == null) {
                        UploadOutcome.Failure(0, "파일을 읽을 수 없습니다")
                    } else {
                        FileUploadClient(
                            uploadServerUrl = { HermesRuntime.currentSettings.uploadServerUrl },
                            apiKey = { HermesRuntime.currentSettings.apiKey },
                        ).upload(selected.name, selected.mimeType, selected.bytes)
                    }
                } catch (e: Exception) {
                    // ContentResolver 쪽(SecurityException/IOException 등)은 FileUploadClient의
                    // 내부 try-catch 범위 밖이라 여기서 따로 잡는다 — 안 잡으면 scope.launch가
                    // 예외를 삼키지 않고 그대로 앱을 죽인다.
                    UploadOutcome.Failure(0, "파일 읽기 오류: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
            uploading = false
            when (outcome) {
                is UploadOutcome.Success -> {
                    val caption = input
                    input = ""
                    state.submit(
                        "$caption\n\n첨부 파일 경로: ${outcome.path}\n${outcome.note}".trim(),
                    )
                }
                is UploadOutcome.Failure ->
                    state.reportSystemNotice("파일 업로드 실패 (${outcome.statusCode}): ${outcome.message}")
            }
        }
    }
```

`import com.hermes.app.FileUploadClient` / `import com.hermes.app.UploadOutcome`는
같은 패키지(`com.hermes.app`)라 `WakeWordService`/`HermesRuntime`처럼 추가 import
없이 그대로 참조 가능.

- [ ] **Step 4: `InputBar` 호출부/시그니처에 첨부 버튼 추가**

`InputBar(...)` 호출부(`ChatScreen` 안)에 인자 추가:

```kotlin
            InputBar(
                value = input,
                onValueChange = { input = it },
                enabled = pendingApproval == null,
                sending = state.isRunning,
                uploading = uploading,
                onMicClick = ::onMicClick,
                onAttachClick = { attachLauncher.launch("*/*") },
                onSend = {
                    val text = input
                    input = ""
                    state.submit(text)
                },
            )
```

`InputBar` 함수 시그니처와 본문 수정:

```kotlin
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    sending: Boolean,
    uploading: Boolean,
    onMicClick: () -> Unit,
    onAttachClick: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onAttachClick, enabled = enabled && !sending && !uploading) {
                // 클립 이모지 — 마이크 버튼(🎙)과 같은 이유로 material-icons-extended 없이 표시
                Text(if (uploading) "…" else "📎")
            }
            IconButton(onClick = onMicClick, enabled = enabled && !sending) {
                Text("🎙")
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                placeholder = { Text(if (enabled) "메시지 보내기" else "승인 대기 중") },
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = enabled && !sending && value.isNotBlank(),
                onClick = onSend,
            ) {
                Text("전송")
            }
        }
    }
}
```

- [ ] **Step 5: 빌드 확인**

Run: `cd android && .\gradlew.bat :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
git add android/app/src/main/kotlin/com/hermes/app/ui/chat/ChatScreen.kt
git commit -m "feat(android): wire attach button to upload files into chat"
```

---

## Task 9: 전체 검증 + 실기기 확인 + 최종 보고

**Files:** 없음(검증만).

- [ ] **Step 1: upload-server 전체 테스트**

Run: `cd upload-server && python -m pytest -v`
Expected: PASS (13개 전부)

- [ ] **Step 2: Android 전체 빌드+유닛테스트**

Run: `cd android && .\gradlew.bat :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, 전체 테스트 그린(신규 4개 + 기존 전부)

- [ ] **Step 3: upload-server를 개발 PC에서 실제로 기동**

```bash
cd upload-server
set UPLOAD_SERVER_API_KEY=<local.properties의 hermes.apiKey와 동일한 값>
set UPLOAD_SERVER_HOST=127.0.0.1
set UPLOAD_SERVER_INBOX_DIR=./uploads/inbox
hermes-upload-server
```

`upload-server listening on 127.0.0.1:8643` 로그 확인.

- [ ] **Step 4: curl로 실제 업로드 왕복 확인**

```bash
curl -X POST http://127.0.0.1:8643/upload \
  -H "Authorization: Bearer <같은 키>" \
  -F "file=@<로컬 아무 이미지 파일 경로>"
```

응답 JSON의 `path`가 실제로 `upload-server/uploads/inbox/`에 생겼는지, 그 파일을
직접 열어 원본과 동일한지 확인.

- [ ] **Step 5: 스윕 동작 실측**

`uploads/inbox/`에 있는 방금 그 파일의 mtime을 15일 전으로 조작:

```bash
python -c "import os, time, pathlib; p = pathlib.Path('uploads/inbox').iterdir().__next__(); os.utime(p, (time.time() - 15*86400,)*2)"
```

서버를 재시작(또는 스윕 주기를 짧게 설정해 재기동)하고 몇 초 뒤 그 파일이 실제로
지워졌는지 확인. `UPLOAD_SERVER_SWEEP_INTERVAL_SECONDS=5`로 설정하면 재시작 없이도
5초 안에 확인 가능.

- [ ] **Step 6: 실기기(에뮬레이터 또는 실제 폰)로 end-to-end 확인**

1. 앱 설치, 설정 화면에서 "업로드 서버 URL"에 PC의 LAN IP:포트(`http://172.30.1.101:8643`)
   입력 후 저장.
2. 게이트웨이 쪽에서 `hermes tools enable vision --platform api_server` 실행 확인
   (이미 사용자가 별도로 진행하기로 한 항목 — 안 되어 있으면 이 스텝 전에 요청).
3. 채팅 화면에서 📎 버튼 눌러 이미지 하나 선택 → 전송.
4. 채팅에 "첨부 파일 경로: ..." 텍스트가 사용자 메시지로 뜨는지, 에이전트가
   `vision_analyze`(또는 `file`) 툴을 호출하는 tool-activity 표시가 뜨는지, 최종
   답변이 그 이미지 내용을 실제로 반영하는지 확인.
5. 같은 방식으로 일반 텍스트 파일(.txt) 하나도 첨부해 에이전트가 `file` 툴로 내용을
   읽어 답하는지 확인.
6. 업로드 서버 URL을 일부러 틀린 값으로 바꾼 뒤 첨부 시도 → 채팅에 `SystemNotice`
   에러가 뜨고 앱이 죽지 않는지 확인.

- [ ] **Step 7: 최종 보고**

Task 1~9의 실행 결과(유닛테스트 통과 수, 실기기 확인 결과, 발견된 이슈와 조치)를
정리해 사용자에게 보고한다. 이슈가 있었다면 원인과 수정 내용을 함께 보고한다.
