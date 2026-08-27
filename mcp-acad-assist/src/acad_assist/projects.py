"""프로젝트 폴더 규약과 `meta.json` — 단계 간 접합부.

PLAN.md의 설계: MCP끼리 직접 대화하지 않는다. 약속된 폴더 구조로 파일을 넘긴다.

```
<root>/<project>/
├─ 01-cad/      plan.dwg          AutoCAD 산출물
├─ 02-model/    model.skp
│               model.fbx         (선택 — Max 는 .skp 를 직접 임포트한다)
├─ 03-render/   persp_4k.png
└─ meta.json    { project, stage, units, artifacts[], updated_at }
```

각 단계 도구는 자기 산출물 경로를 `meta.json`에 기록하고 다음 단계는 거기서 읽는다.
사람이 중간에 파일을 바꿔치기해도 흐름이 이어진다.

**루트 경로를 하드코딩하지 않는다.** 이 리포는 CAD 앱이 없는 개발 PC에서 작성되고
4종이 다 설치된 별 환경으로 옮겨 실행된다. 루트는 `HERMES_CAD_ROOT` 환경변수로 주입하며
(`config.yaml` 의 `mcp_servers.*.env`), 없으면 플랫폼 기본값을 쓴다.
"""

from __future__ import annotations

import json
import os
import re
import tempfile
import time
from pathlib import Path
from typing import Any

#: 루트를 지정하는 환경변수 이름.
ROOT_ENV = "HERMES_CAD_ROOT"

#: 단계별 하위 폴더. 이름의 숫자 접두사는 정렬용이고 규약의 일부다.
STAGE_DIRS: dict[str, str] = {
    "cad": "01-cad",
    "model": "02-model",
    "render": "03-render",
}

META_NAME = "meta.json"

#: `meta.json` 스키마 버전. 필드를 바꾸면 올린다.
META_VERSION = 1

#: 프로젝트 이름에 허용하는 문자. 경로 조작(`..`, 절대경로, 구분자)을 원천 차단한다.
_SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")


class ProjectError(RuntimeError):
    """프로젝트 이름·경로·`meta.json` 이 규약에 맞지 않을 때."""


def default_root() -> Path:
    """`HERMES_CAD_ROOT` 가 없을 때 쓰는 기본 루트.

    Windows 는 `%USERPROFILE%\\hermes-projects`, 그 외는 `~/hermes-projects`.
    `C:\\hermes-projects` 같은 절대경로를 코드에 박지 않는다 — 옮긴 환경에서
    그 드라이브가 없을 수 있다 (실제로 이 개발 PC 에는 D: 가 없었다).
    """
    return Path.home() / "hermes-projects"


def resolve_root() -> Path:
    """프로젝트 루트를 결정한다. 환경변수 우선, 없으면 `default_root()`."""
    raw = os.environ.get(ROOT_ENV)
    if raw and raw.strip():
        return Path(raw.strip()).expanduser()
    return default_root()


def validate_name(project: str) -> str:
    """프로젝트 이름을 검사해 그대로 돌려준다. 경로 이탈을 막는 유일한 지점."""
    if not _SAFE_NAME.match(project or ""):
        raise ProjectError(
            f"프로젝트 이름이 규약에 맞지 않습니다: {project!r}. "
            "영숫자로 시작하고 영숫자·점·밑줄·하이픈만, 최대 64자."
        )
    return project


def project_dir(project: str, *, root: Path | None = None) -> Path:
    """`<root>/<project>` 경로. 생성하지 않는다."""
    return (root or resolve_root()) / validate_name(project)


def stage_dir(project: str, stage: str, *, root: Path | None = None) -> Path:
    """`<root>/<project>/<NN-stage>` 경로. 생성하지 않는다."""
    try:
        sub = STAGE_DIRS[stage]
    except KeyError:
        raise ProjectError(
            f"알 수 없는 단계: {stage!r}. 가능한 값: {', '.join(sorted(STAGE_DIRS))}"
        ) from None
    return project_dir(project, root=root) / sub


def meta_path(project: str, *, root: Path | None = None) -> Path:
    return project_dir(project, root=root) / META_NAME


def project_init(
    project: str,
    *,
    root: Path | None = None,
    units: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """프로젝트 폴더 3개와 `meta.json` 을 만든다. 이미 있으면 그대로 두고 메타만 읽는다.

    멱등하다 — 같은 프로젝트로 다시 불러도 기존 산출물 기록을 지우지 않는다.
    """
    base = project_dir(project, root=root)
    for sub in STAGE_DIRS.values():
        (base / sub).mkdir(parents=True, exist_ok=True)

    path = meta_path(project, root=root)
    if path.exists():
        meta = meta_read(project, root=root)
        if units is not None and meta.get("units") != units:
            meta = meta_update(project, root=root, units=units)
        return meta

    meta = {
        "meta_version": META_VERSION,
        "project": project,
        "stage": "init",
        "units": units,
        "artifacts": [],
        "updated_at": _now(),
    }
    _write_atomic(path, meta)
    return meta


def meta_read(project: str, *, root: Path | None = None) -> dict[str, Any]:
    """`meta.json` 을 읽는다. 없거나 깨졌으면 `ProjectError`."""
    path = meta_path(project, root=root)
    try:
        raw = path.read_text(encoding="utf-8")
    except FileNotFoundError:
        raise ProjectError(
            f"{path} 가 없습니다. project_init({project!r}) 을 먼저 호출하세요."
        ) from None
    try:
        meta = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise ProjectError(f"{path} 를 JSON 으로 읽을 수 없습니다: {exc}") from exc
    if not isinstance(meta, dict):
        raise ProjectError(f"{path} 의 최상위가 객체가 아닙니다")
    return meta


def meta_update(project: str, *, root: Path | None = None, **fields: Any) -> dict[str, Any]:
    """`meta.json` 의 일부 필드를 갱신한다. `updated_at` 은 자동으로 붙는다.

    `artifacts` 는 이 함수로 직접 덮어쓰지 말고 `register_artifact` 를 쓴다.
    """
    meta = meta_read(project, root=root)
    meta.update(fields)
    meta["updated_at"] = _now()
    _write_atomic(meta_path(project, root=root), meta)
    return meta


def register_artifact(
    project: str,
    stage: str,
    kind: str,
    path: str | Path,
    *,
    root: Path | None = None,
    extra: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """산출물 하나를 `meta.json` 에 기록하고 `stage` 를 그 단계로 올린다.

    같은 (stage, kind) 가 이미 있으면 **덮어쓴다** — 같은 단계를 다시 돌리면 최신
    산출물 하나만 남는 게 맞다. 이력이 필요하면 `extra` 에 담는다.
    """
    if stage not in STAGE_DIRS:
        raise ProjectError(
            f"알 수 없는 단계: {stage!r}. 가능한 값: {', '.join(sorted(STAGE_DIRS))}"
        )
    meta = meta_read(project, root=root)
    entry: dict[str, Any] = {
        "stage": stage,
        "kind": kind,
        "path": str(path),
        "created_at": _now(),
    }
    if extra:
        entry.update(extra)

    artifacts = [
        a
        for a in meta.get("artifacts", [])
        if not (isinstance(a, dict) and a.get("stage") == stage and a.get("kind") == kind)
    ]
    artifacts.append(entry)
    meta["artifacts"] = artifacts
    meta["stage"] = stage
    meta["updated_at"] = _now()
    _write_atomic(meta_path(project, root=root), meta)
    return meta


def latest_artifact(
    project: str, stage: str, kind: str | None = None, *, root: Path | None = None
) -> dict[str, Any] | None:
    """해당 단계(+종류)의 가장 최근 산출물 기록. 다음 단계가 입력을 찾을 때 쓴다."""
    meta = meta_read(project, root=root)
    matches = [
        a
        for a in meta.get("artifacts", [])
        if isinstance(a, dict)
        and a.get("stage") == stage
        and (kind is None or a.get("kind") == kind)
    ]
    if not matches:
        return None
    return max(matches, key=lambda a: str(a.get("created_at", "")))


def ensure_parent(path: str | Path) -> Path:
    """출력 파일의 부모 디렉터리를 만들어 두고 경로를 돌려준다.

    COM 의 `SaveAs`/`PlotToFile` 은 상위 폴더가 없으면 실패하는데, 에러 메시지가
    불친절해서 원인 찾기 어렵다. 쓰기 도구는 전부 이걸 먼저 통과한다.
    """
    p = Path(path)
    p.parent.mkdir(parents=True, exist_ok=True)
    return p


def _now() -> str:
    """ISO-8601 UTC 타임스탬프. `meta.json` 의 모든 시각 필드가 이걸 쓴다."""
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def _write_atomic(path: Path, payload: dict[str, Any]) -> None:
    """같은 디렉터리에 임시 파일로 쓰고 교체한다.

    렌더가 몇 분 걸리는 동안 다른 단계가 `meta.json` 을 읽을 수 있으므로, 반쯤 쓰인
    JSON 이 보이면 안 된다. `os.replace` 는 같은 볼륨에서 원자적이다.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    fd, tmp = tempfile.mkstemp(dir=str(path.parent), prefix=".meta-", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(text)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
