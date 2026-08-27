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


def test_all_eight_tools_are_registered():
    tools = _tools_by_name()
    assert set(tools) == set(server.READ_ONLY_TOOLS) | set(server.WRITE_TOOLS)
    assert len(tools) == 8


def test_read_only_and_write_sets_do_not_overlap():
    assert set(server.READ_ONLY_TOOLS).isdisjoint(server.WRITE_TOOLS)


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
