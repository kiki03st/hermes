"""3ds Max MAXScript 생성기 — 골든 텍스트 + JSON 왕복 검증.

가장 중요한 테스트는 골든 바이트 매치가 아니라 `_maxscript_print_roundtrip`
이다 — `print "..."` 한 줄이 실제로 유효한 JSON을 만드는지, MAXScript의
문자열 이스케이프 규칙을 그대로 시뮬레이션해서 확인한다. 이중 이스케이프는
손으로 세면 틀리기 쉬운 산수라서, 골든 텍스트가 우연히 맞아도 실제로
JSON으로 안 읽힐 수 있다 — 이 테스트가 그 가능성을 차단한다.
"""

from __future__ import annotations

import json
import re

import pytest

from acad_assist import maxscripts as m


def _maxscript_unescape(literal: str) -> str:
    """MAXScript 이중따옴표 문자열 리터럴 '내용'(따옴표 제외)을 런타임 문자열로.

    실제 MAXScript 파서를 흉내낸다: `\\\\` -> `\\`, `\\"` -> `"`. 이 테스트
    파일 밖에서는 쓰지 않는다 — 프로덕션 코드가 이걸 흉내낼 필요는 없다
    (프로덕션은 반대 방향, 즉 이스케이프하는 쪽만 한다).
    """
    out: list[str] = []
    i = 0
    while i < len(literal):
        c = literal[i]
        if c == "\\" and i + 1 < len(literal):
            nxt = literal[i + 1]
            if nxt == "\\":
                out.append("\\")
                i += 2
                continue
            if nxt == '"':
                out.append('"')
                i += 2
                continue
        out.append(c)
        i += 1
    return "".join(out)


def _extract_print_json(script: str) -> dict:
    """스크립트의 `print "..."` 줄을 찾아 MAXScript 이스케이프를 풀고 JSON으로 읽는다."""
    match = re.search(r'print "((?:[^"\\]|\\.)*)"', script)
    assert match is not None, f"print 문을 못 찾음:\n{script}"
    runtime_string = _maxscript_unescape(match.group(1))
    return json.loads(runtime_string)


# --------------------------------------------------------------- roundtrip


@pytest.mark.parametrize(
    "path",
    [
        r"C:\hermes-projects\room01\02-model\model.skp",
        r"C:\hermes-projects\room01\03-render\persp_4k.png",
        r"C:\a\b\c.skp",
        r"D:\weird path with spaces\file.skp",
    ],
)
def test_import_skp_print_line_is_valid_json_with_correct_path(path):
    """이중 이스케이프 산수가 실제로 맞는지 — 골든 바이트 매치보다 이게 더 중요하다."""
    script = m.import_skp_script(path)
    data = _extract_print_json(script)
    assert data["path"] == path


@pytest.mark.parametrize(
    "path",
    [
        r"C:\hermes-projects\room01\03-render\persp_4k.png",
        r"C:\out\preview.png",
    ],
)
def test_render_to_file_print_line_is_valid_json_with_correct_path(path):
    script = m.render_to_file_script(path)
    data = _extract_print_json(script)
    assert data["path"] == path


def test_maxscript_unescape_helper_matches_the_forward_escaper():
    """테스트 헬퍼 자체가 맞는지 — _print_json_line 이 만든 걸 다시 풀면
    원래 dict 로 돌아와야 한다."""
    original = {"path": r"C:\a\b\c.skp", "width": 100, "nested": ["x", "y"]}
    line = m._print_json_line(original)
    match = re.match(r'print "((?:[^"\\]|\\.)*)"$', line)
    assert match is not None
    recovered = json.loads(_maxscript_unescape(match.group(1)))
    assert recovered == original


# ----------------------------------------------------------- path literal


def test_maxscript_path_literal_uses_at_quote_syntax():
    assert m.maxscript_path_literal(r"C:\a\b.skp") == r'@"C:\a\b.skp"'


def test_maxscript_path_literal_preserves_single_backslashes():
    """@'...' 안에서는 백슬래시가 이스케이프 문자가 아니다 — 그대로 들어가야 한다."""
    literal = m.maxscript_path_literal(r"C:\hermes-projects\room01\model.skp")
    assert literal == r'@"C:\hermes-projects\room01\model.skp"'
    assert "\\\\" not in literal  # 두 배로 이스케이프되면 안 된다 (@ 리터럴은 안 함)


def test_maxscript_path_literal_rejects_embedded_quote():
    """@'...' 안에는 이스케이프 메커니즘이 없어 큰따옴표를 표현할 방법이 없다."""
    with pytest.raises(m.MaxScriptError, match='큰따옴표'):
        m.maxscript_path_literal('C:\\weird"name.skp')


# -------------------------------------------------------------- vray pick


def test_select_vray_renderer_uses_pattern_match_not_hardcoded_class():
    """버전 고정 클래스명(V_Ray_Adv_1_50_SP6 등)을 쓰면 SDK 버전이 바뀔 때마다
    깨진다 — matchPattern 으로 훑는 버전 무관 방식이어야 한다."""
    snippet = m.select_vray_renderer_snippet()
    assert 'matchPattern (c as string) pattern:"V_Ray*"' in snippet
    assert "V_Ray_Adv" not in snippet
    assert "throw" in snippet  # 못 찾으면 조용히 넘어가지 않는다


def test_render_to_file_includes_vray_selection():
    script = m.render_to_file_script(r"C:\out\a.png")
    assert "renderers.production = vray_class()" in script


# ---------------------------------------------------------- render setup


def test_render_to_file_sets_render_setup_globals_not_just_render_call():
    """render_scene 도 MAXScript render() 도 Render Setup 설정을 무시한다
    (계획 §F-3 실측) — rendOutputFilename/rendSaveFile 글로벌을 직접 설정하고
    max quick render 로 트리거하는 경로여야 한다."""
    script = m.render_to_file_script(r"C:\out\a.png")
    assert "rendSaveFile = true" in script
    assert "rendOutputFilename = " in script
    assert "renderWidth = " in script
    assert "renderHeight = " in script
    assert "max quick render" in script
    # PLAN.md 원안의 자기모순 스니펫(경로 1)을 쓰면 안 된다.
    assert "render vfb:" not in script
    assert "render outputSize:" not in script


def test_render_to_file_verifies_output_file_exists():
    """성공을 자기 보고하지 않는다 — 실제로 파일이 생겼는지 확인해서 없으면 throw."""
    script = m.render_to_file_script(r"C:\out\a.png")
    assert "doesFileExist rendOutputFilename" in script
    assert "throw" in script


@pytest.mark.parametrize("preset,expected", [("preview", (960, 540)), ("final", (3840, 2160))])
def test_render_presets_resolve_to_documented_resolutions(preset, expected):
    script = m.render_to_file_script(r"C:\out\a.png", preset=preset)
    w, h = expected
    assert f"renderWidth = {w}" in script
    assert f"renderHeight = {h}" in script


def test_render_to_file_rejects_unknown_preset():
    with pytest.raises(m.MaxScriptError, match="alien-quality"):
        m.render_to_file_script(r"C:\out\a.png", preset="alien-quality")


def test_render_to_file_explicit_resolution_overrides_preset():
    script = m.render_to_file_script(r"C:\out\a.png", preset="final", width=800, height=600)
    assert "renderWidth = 800" in script
    assert "renderHeight = 600" in script


# --------------------------------------------------------------- import


def test_import_skp_uses_skp_directly_not_fbx():
    """.skp 를 3ds Max 가 직접 임포트하는 게 확정안이다 — FBX 아님 (PLAN.md)."""
    script = m.import_skp_script(r"C:\hermes-projects\room01\02-model\model.skp")
    assert "importFile" in script
    assert "#noPrompt" in script
    assert ".fbx" not in script.lower()


def test_import_skp_verifies_object_count_increased():
    """importFile 은 실패해도 예외를 안 던질 수 있다 — 오브젝트 수 증가로 검증."""
    script = m.import_skp_script(r"C:\a.skp")
    assert "objects.count" in script
    assert "throw" in script


def test_import_skp_does_not_prompt_for_a_summary_dialog():
    """TTY 없는 게이트웨이에서 대화상자가 뜨면 멈춘다."""
    script = m.import_skp_script(r"C:\a.skp")
    assert "#noPrompt" in script


# ------------------------------------------------------------ camera/light


def test_setup_camera_creates_target_camera_and_activates_it():
    script = m.setup_camera_script((5000.0, -5000.0, 4000.0), (0.0, 0.0, 1500.0))
    assert "Targetcamera" in script
    assert "viewport.setCamera cam" in script
    assert "fov:45.0" in script


def test_setup_camera_custom_fov_and_name():
    script = m.setup_camera_script((0, 0, 0), (1, 1, 1), fov=60.0, name="HeroCam")
    assert "fov:60.0" in script
    assert 'cam.name = "HeroCam"' in script


def test_setup_lighting_uses_skylight_and_omni_not_daylight_system():
    """Daylight System 생성자 시그니처를 이 개발 PC 에서 확인할 방법이 없어서
    더 단순하지만 확실한 Skylight+Omni 조합을 기본으로 삼는다 (모듈 docstring)."""
    script = m.setup_lighting_script()
    assert "Skylight" in script
    assert "Omnilight" in script
    assert "Daylight" not in script


# ---------------------------------------------------------------- footer


def test_every_script_prints_exactly_one_json_line():
    scripts = [
        m.import_skp_script(r"C:\a.skp"),
        m.render_to_file_script(r"C:\a.png"),
        m.setup_camera_script((0, 0, 0), (1, 1, 1)),
        m.setup_lighting_script(),
    ]
    for script in scripts:
        assert script.count('print "') == 1
        # print 가 마지막 줄이어야 한다 -- 이후에 죽은 코드가 남아있지 않다.
        assert script.rstrip().splitlines()[-1].startswith('print "')
