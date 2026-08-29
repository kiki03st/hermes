"""MCP 도구 등록 — 읽기/쓰기 분리, ToolAnnotations, Hermes readOnlyHint 버그 회귀 확인.

`server.py` 는 예전에 테스트가 0개였다 — pytest 18개가 통과해도 이 파일이 실제로
임포트되고, 도구가 등록되고, 어노테이션이 계획대로 붙었는지는 전혀 검증되지
않았다. `config.yaml.example` 의 acad-read/acad-write 이중 등록(계획 §C)이 왜
필요한지도 여기서 증명한다.
"""

from __future__ import annotations

from acad_assist import server
from acad_assist.com import ComWorker

from .fakes import FakeAcadPort, FakeEntity


def _tools_by_name() -> dict[str, object]:
    return {t.name: t for t in server.mcp._tool_manager.list_tools()}


def test_all_nineteen_tools_are_registered():
    tools = _tools_by_name()
    all_names = set(server.READ_ONLY_TOOLS) | set(server.WRITE_TOOLS) | set(server.PIPELINE_TOOLS)
    assert set(tools) == all_names
    assert len(tools) == 19


def test_tool_groups_do_not_overlap():
    groups = [set(server.READ_ONLY_TOOLS), set(server.WRITE_TOOLS), set(server.PIPELINE_TOOLS)]
    for i, a in enumerate(groups):
        for b in groups[i + 1 :]:
            assert a.isdisjoint(b)


def test_pipeline_tools_are_all_read_only():
    """생성기 도구는 부작용이 전혀 없다(문자열만 돌려준다) — 전부 읽기 전용이어야 한다."""
    tools = _tools_by_name()
    for name in server.PIPELINE_TOOLS:
        ann = tools[name].annotations
        assert ann is not None and ann.read_only_hint is True


def test_read_only_tools_carry_read_only_hint_true():
    tools = _tools_by_name()
    for name in server.READ_ONLY_TOOLS:
        ann = tools[name].annotations
        assert ann is not None, f"{name} 에 annotations 가 없습니다"
        assert ann.read_only_hint is True, f"{name}.annotations.read_only_hint 가 True 가 아닙니다"


def test_write_tools_carry_no_read_only_annotation():
    """모든 도구가 하나라도 read_only_hint 를 달면 config.yaml 의 acad-write 등록이
    필요 없어 보이겠지만, 아래 test_hermes_readonlyhint_bug_regression 이 보여주듯
    이 버전에서는 그 값이 live discovery 경로에서 안 읽힌다 — 그래서 쓰기 도구는
    아예 어노테이션을 안 달아 fail-closed(= 항상 write-capable 취급)를 명확히 한다."""
    tools = _tools_by_name()
    for name in server.WRITE_TOOLS:
        assert tools[name].annotations is None


def test_hermes_readonlyhint_bug_regression():
    """Hermes v0.20.6 의 `_annotation_read_only_hint`(tools/mcp_tool.py)는
    `getattr(annotations, "readOnlyHint", None)` 로 읽는데, mcp 2.1.1 의 파이썬
    필드명은 `read_only_hint` 다. 이 서버가 실제로 만드는 어노테이션 객체로
    재현한다 — 합성 객체가 아니라 우리 서버가 등록한 진짜 객체다.

    이 테스트가 언젠가 실패한다면(=Hermes 가 버그를 고쳤다면) 그건 축하할 일이지만,
    그 순간 config.yaml 의 이중 등록이 더는 필요 없어진다는 신호이기도 하다 —
    `windows-migration.md` §7 과 이 테스트를 함께 갱신할 것.
    """
    ann = _tools_by_name()["status"].annotations

    assert ann.read_only_hint is True
    assert getattr(ann, "readOnlyHint", None) is None  # Hermes 가 실제로 읽는 이름


def test_all_tools_have_docstrings():
    """MCP 도구 설명은 LLM 이 어떤 도구를 고를지 판단하는 유일한 근거다."""
    for tool in server.mcp._tool_manager.list_tools():
        assert tool.description and tool.description.strip()


def test_tool_functions_delegate_to_the_shared_worker(monkeypatch):
    """도구 함수가 실제로 acad_* 로직에 위임하는지 — 데코레이터가 원본 함수를
    바꿔치기하지 않았는지도 함께 확인한다."""
    fake_port = FakeAcadPort()
    fake_port.document.add_entity(FakeEntity("A1", "AcDbLine", "walls"))
    monkeypatch.setattr(server, "_worker", ComWorker(lambda: fake_port))

    result = server.status()
    assert result["connected"] is True
    assert result["document"] == fake_port.document.Name

    entities = server.query(layer="walls")
    assert [e["handle"] for e in entities] == ["A1"]

    detail = server.get(handle="A1")
    assert detail["type"] == "AcDbLine"

    check = server.purge_check()
    assert check["saved"] is True


def test_write_tools_still_respect_the_confirm_gate(monkeypatch):
    fake_port = FakeAcadPort()
    fake_port.document.add_entity(FakeEntity("A1", "AcDbLine", "walls"))
    monkeypatch.setattr(server, "_worker", ComWorker(lambda: fake_port))

    preview = server.modify(handles=["A1"], operation="erase", confirm=False)
    assert preview["confirm_required"] is True
    assert fake_port.document.ModelSpace[0].erased is False

    server.modify(handles=["A1"], operation="erase", confirm=True)
    assert fake_port.document.ModelSpace[0].erased is True


def test_export_tool_exposes_pipeline_kwargs(monkeypatch, tmp_path):
    """export 도구는 project 를 받아 meta.json 접합에 쓴다 — Stage 6 파이프라인의
    전제다."""
    from acad_assist import projects

    root = tmp_path / "projects"
    monkeypatch.setenv(projects.ROOT_ENV, str(root))
    projects.project_init("room01")

    fake_port = FakeAcadPort()
    monkeypatch.setattr(server, "_worker", ComWorker(lambda: fake_port))

    target = root / "room01" / "01-cad" / "plan.dwg"
    server.export(output_path=str(target), fmt="dwg", confirm=True, project="room01")

    meta = projects.meta_read("room01")
    assert meta["artifacts"][0]["kind"] == "dwg"


def test_capture_tool_returns_base64_image(monkeypatch, tmp_path):
    fake_port = FakeAcadPort()
    monkeypatch.setattr(server, "_worker", ComWorker(lambda: fake_port))

    result = server.capture(output_path=str(tmp_path / "view.png"))

    assert "image_base64" in result


def test_pipeline_tools_delegate_to_the_generator_modules_verbatim():
    """MCP 도구가 생성기 함수를 감싸기만 하고 텍스트를 바꾸지 않는지 확인한다 —
    바뀌면 골든 테스트가 고정한 계약과 실제 MCP 응답이 어긋난다."""
    from acad_assist import maxscripts, sketchup_scripts

    assert server.sketchup_import_dwg_script(r"C:\a.dwg") == sketchup_scripts.import_dwg_script(
        r"C:\a.dwg"
    )
    assert server.sketchup_extrude_walls_script(
        3000.0, layer="walls"
    ) == sketchup_scripts.extrude_walls_script(3000.0, layer="walls")
    assert server.sketchup_save_skp_script(r"C:\a.skp") == sketchup_scripts.save_skp_script(
        r"C:\a.skp"
    )
    assert server.sketchup_export_fbx_script(r"C:\a.fbx") == sketchup_scripts.export_fbx_script(
        r"C:\a.fbx"
    )
    assert server.sketchup_capture_iso_script(
        r"C:\a.png", width=800, height=600
    ) == sketchup_scripts.capture_iso_script(r"C:\a.png", width=800, height=600)
    assert server.sketchup_unit_check_script() == sketchup_scripts.unit_check_script()

    assert server.max_import_skp_script(r"C:\a.skp") == maxscripts.import_skp_script(r"C:\a.skp")
    assert server.max_setup_camera_script(
        [1.0, 2.0, 3.0], [0.0, 0.0, 0.0], fov=50.0, name="X"
    ) == maxscripts.setup_camera_script((1.0, 2.0, 3.0), (0.0, 0.0, 0.0), fov=50.0, name="X")
    assert server.max_setup_lighting_script(sky_multiplier=2.0) == maxscripts.setup_lighting_script(
        sky_multiplier=2.0
    )
    assert server.max_render_to_file_script(
        r"C:\a.png", preset="preview"
    ) == maxscripts.render_to_file_script(r"C:\a.png", "preview", width=None, height=None)


def test_pipeline_tools_accept_plain_list_coordinates():
    """MCP 는 좌표를 JSON 배열(파이썬 list)로 넘긴다 — setup_camera_script 가
    기대하는 tuple 이 아니다. server.py 가 변환을 책임진다."""
    script = server.max_setup_camera_script([100.0, 200.0, 300.0], [0.0, 0.0, 0.0])
    assert "pos:[100.0,200.0,300.0]" in script


def test_register_artifact_tool_records_sketchup_and_max_stage_output(monkeypatch, tmp_path):
    """SketchUp/3ds Max 산출물은 acad-assist 가 실행 결과를 직접 볼 방법이 없다
    (Ruby/MAXScript 는 Hermes 쪽 eval_ruby/execute_maxscript 가 실행한다) — 그래서
    export 처럼 acad-assist 자신이 자동으로 등록해줄 수 없고, 에이전트가 성공을
    확인한 뒤 이 도구로 직접 등록해야 한다(cad-pipeline.md "알려진 공백" 항목)."""
    from acad_assist import projects

    root = tmp_path / "projects"
    monkeypatch.setenv(projects.ROOT_ENV, str(root))
    projects.project_init("room01")

    result = server.register_artifact(
        project="room01", stage="model", kind="skp", path=str(root / "room01" / "02-model" / "model.skp")
    )

    assert result["stage"] == "model"
    meta = projects.meta_read("room01")
    assert meta["artifacts"][0]["kind"] == "skp"


def test_register_artifact_tool_is_read_only_annotated():
    """CAD 문서/COM 을 안 건드리고 meta.json 북키핑만 하므로 cad-pipeline 그룹의
    나머지 생성기들과 같은 신뢰 수준(trust: full)이 맞다."""
    ann = _tools_by_name()["register_artifact"].annotations
    assert ann is not None and ann.read_only_hint is True
