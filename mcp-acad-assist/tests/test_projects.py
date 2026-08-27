"""프로젝트 폴더 규약과 meta.json — 단계 간 접합부의 계약."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from acad_assist import projects


def test_resolve_root_prefers_env_var(monkeypatch, tmp_path):
    monkeypatch.setenv(projects.ROOT_ENV, str(tmp_path / "elsewhere"))
    assert projects.resolve_root() == tmp_path / "elsewhere"


def test_resolve_root_falls_back_when_env_missing_or_blank(monkeypatch):
    monkeypatch.delenv(projects.ROOT_ENV, raising=False)
    assert projects.resolve_root() == projects.default_root()
    monkeypatch.setenv(projects.ROOT_ENV, "   ")
    assert projects.resolve_root() == projects.default_root()


def test_default_root_is_not_a_hardcoded_drive():
    """옮긴 환경에 그 드라이브가 없을 수 있다 — 이 개발 PC 에는 D: 가 없었다."""
    root = str(projects.default_root())
    assert "hermes-projects" in root
    assert not root.startswith(("C:\\hermes-projects", "D:\\"))


@pytest.mark.parametrize(
    "bad",
    ["", ".", "..", "../escape", "a/b", "a\\b", "C:\\abs", "-leading", "x" * 65],
)
def test_validate_name_rejects_path_traversal(bad):
    with pytest.raises(projects.ProjectError):
        projects.validate_name(bad)


@pytest.mark.parametrize("good", ["room01", "A", "my-proj_2.v3", "0start"])
def test_validate_name_accepts_safe_names(good):
    assert projects.validate_name(good) == good


def test_project_init_creates_three_stage_dirs_and_meta(tmp_path):
    meta = projects.project_init("room01", root=tmp_path)
    base = tmp_path / "room01"
    for sub in ("01-cad", "02-model", "03-render"):
        assert (base / sub).is_dir()
    assert (base / "meta.json").is_file()
    assert meta["project"] == "room01"
    assert meta["stage"] == "init"
    assert meta["artifacts"] == []
    assert meta["meta_version"] == projects.META_VERSION
    assert meta["updated_at"].endswith("Z")


def test_project_init_is_idempotent_and_keeps_artifacts(tmp_path):
    projects.project_init("room01", root=tmp_path)
    projects.register_artifact("room01", "cad", "dwg", "p.dwg", root=tmp_path)
    again = projects.project_init("room01", root=tmp_path)
    assert len(again["artifacts"]) == 1
    assert again["stage"] == "cad"


def test_project_init_updates_units_when_they_change(tmp_path):
    projects.project_init("room01", root=tmp_path, units={"name": "inches"})
    meta = projects.project_init("room01", root=tmp_path, units={"name": "millimeters"})
    assert meta["units"] == {"name": "millimeters"}


def test_stage_dir_maps_to_numbered_folders(tmp_path):
    assert projects.stage_dir("p", "cad", root=tmp_path).name == "01-cad"
    assert projects.stage_dir("p", "model", root=tmp_path).name == "02-model"
    assert projects.stage_dir("p", "render", root=tmp_path).name == "03-render"


def test_stage_dir_rejects_unknown_stage(tmp_path):
    with pytest.raises(projects.ProjectError):
        projects.stage_dir("p", "nope", root=tmp_path)


def test_meta_read_missing_gives_actionable_error(tmp_path):
    with pytest.raises(projects.ProjectError) as exc:
        projects.meta_read("ghost", root=tmp_path)
    assert "project_init" in str(exc.value)


def test_meta_read_rejects_corrupt_json(tmp_path):
    projects.project_init("room01", root=tmp_path)
    (tmp_path / "room01" / "meta.json").write_text("{not json", encoding="utf-8")
    with pytest.raises(projects.ProjectError):
        projects.meta_read("room01", root=tmp_path)


def test_register_artifact_records_and_advances_stage(tmp_path):
    projects.project_init("room01", root=tmp_path)
    meta = projects.register_artifact(
        "room01", "cad", "dwg", tmp_path / "room01" / "01-cad" / "plan.dwg", root=tmp_path
    )
    assert meta["stage"] == "cad"
    (entry,) = meta["artifacts"]
    assert entry["stage"] == "cad"
    assert entry["kind"] == "dwg"
    assert entry["path"].endswith("plan.dwg")
    assert entry["created_at"].endswith("Z")


def test_register_artifact_replaces_same_stage_and_kind(tmp_path):
    projects.project_init("room01", root=tmp_path)
    projects.register_artifact("room01", "cad", "dwg", "old.dwg", root=tmp_path)
    meta = projects.register_artifact("room01", "cad", "dwg", "new.dwg", root=tmp_path)
    assert len(meta["artifacts"]) == 1
    assert meta["artifacts"][0]["path"] == "new.dwg"


def test_register_artifact_keeps_other_kinds(tmp_path):
    projects.project_init("room01", root=tmp_path)
    projects.register_artifact("room01", "model", "skp", "m.skp", root=tmp_path)
    meta = projects.register_artifact("room01", "model", "fbx", "m.fbx", root=tmp_path)
    kinds = {a["kind"] for a in meta["artifacts"]}
    assert kinds == {"skp", "fbx"}


def test_register_artifact_rejects_unknown_stage(tmp_path):
    projects.project_init("room01", root=tmp_path)
    with pytest.raises(projects.ProjectError):
        projects.register_artifact("room01", "nope", "x", "y", root=tmp_path)


def test_latest_artifact_finds_input_for_next_stage(tmp_path):
    projects.project_init("room01", root=tmp_path)
    projects.register_artifact("room01", "cad", "dwg", "plan.dwg", root=tmp_path)
    found = projects.latest_artifact("room01", "cad", "dwg", root=tmp_path)
    assert found is not None and found["path"] == "plan.dwg"
    assert projects.latest_artifact("room01", "render", root=tmp_path) is None


def test_meta_update_sets_fields_and_refreshes_timestamp(tmp_path):
    projects.project_init("room01", root=tmp_path)
    meta = projects.meta_update("room01", root=tmp_path, units={"name": "millimeters"})
    assert meta["units"] == {"name": "millimeters"}
    on_disk = json.loads((tmp_path / "room01" / "meta.json").read_text(encoding="utf-8"))
    assert on_disk["units"] == {"name": "millimeters"}


def test_meta_write_is_atomic_and_leaves_no_temp_files(tmp_path):
    projects.project_init("room01", root=tmp_path)
    projects.register_artifact("room01", "cad", "dwg", "plan.dwg", root=tmp_path)
    leftovers = [p.name for p in (tmp_path / "room01").iterdir() if p.name.startswith(".meta-")]
    assert leftovers == []


def test_meta_json_is_utf8_and_human_readable(tmp_path):
    projects.project_init("한글프로젝트".encode("ascii", "ignore").decode() or "proj", root=tmp_path)
    projects.register_artifact("proj", "cad", "dwg", "평면도.dwg", root=tmp_path)
    text = (tmp_path / "proj" / "meta.json").read_text(encoding="utf-8")
    assert "평면도.dwg" in text  # ensure_ascii=False
    assert text.endswith("\n")


def test_ensure_parent_creates_directories(tmp_path):
    target = tmp_path / "a" / "b" / "out.png"
    returned = projects.ensure_parent(target)
    assert returned == Path(target)
    assert target.parent.is_dir()
