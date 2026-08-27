"""SketchUp Ruby 생성기 — 골든 텍스트 테스트.

LLM이 매번 스크립트를 즉흥 생성하지 않게 하는 게 이 모듈의 존재 이유다
(PLAN.md Stage 4-3). 그래서 여기서는 "이 함수를 이 입력으로 부르면 항상 정확히
이 텍스트가 나온다"를 바이트 단위로 고정한다 — 우연히 포맷이 바뀌는 걸
회귀로 잡기 위해서다. SketchUp이 이 텍스트를 실제로 어떻게 실행하는지는
대상 환경 몫이다(이 PC에는 SketchUp이 없다).
"""

from __future__ import annotations

from acad_assist import sketchup_scripts as sk


def test_ruby_string_escapes_backslashes_quotes_and_newlines():
    assert sk.ruby_string(r"C:\a\b") == r'"C:\\a\\b"'
    assert sk.ruby_string('say "hi"') == r'"say \"hi\""'
    assert sk.ruby_string("line1\nline2") == r'"line1\nline2"'


def test_ruby_string_roundtrips_a_windows_path():
    path = r"C:\hermes-projects\room01\01-cad\plan.dwg"
    literal = sk.ruby_string(path)
    assert literal == r'"C:\\hermes-projects\\room01\\01-cad\\plan.dwg"'
    # 리터럴을 벗겨서 원래 경로가 나오는지도 확인 (수동 이스케이프 실수 방지)
    unescaped = literal[1:-1].replace('\\\\', '\\').replace('\\"', '"')
    assert unescaped == path


def test_import_dwg_script_is_byte_exact():
    script = sk.import_dwg_script(r"C:\hermes-projects\room01\01-cad\plan.dwg")

    assert script == (
        'model = Sketchup.active_model\n'
        'before = model.active_entities.to_a\n'
        'status = model.import("C:\\\\hermes-projects\\\\room01\\\\01-cad\\\\plan.dwg", false)\n'
        'after = model.active_entities.to_a\n'
        'imported = after - before\n'
        'group = imported.empty? ? nil : model.active_entities.add_group(imported)\n'
        'result = {\n'
        '  "imported" => status ? true : false,\n'
        '  "entity_count" => imported.length,\n'
        '  "group_id" => group ? group.entityID : nil\n'
        '}\n'
        'puts result.to_json\n'
        'result.to_json'
    )


def test_import_dwg_script_uses_show_summary_false():
    """SketchUp이 임포트 요약 대화상자를 띄우면 TTY 없는 게이트웨이에서 멈춘다."""
    script = sk.import_dwg_script(r"C:\plan.dwg")
    assert ", false)" in script


def test_extrude_walls_script_is_byte_exact():
    script = sk.extrude_walls_script(3000.0)

    assert script == (
        'model = Sketchup.active_model\n'
        'target_layer = "walls"\n'
        'entities = model.active_entities\n'
        'faces = entities.grep(Sketchup::Face).select { |f| f.layer.name == target_layer }\n'
        'extruded = 0\n'
        'faces.each do |f|\n'
        '  f.pushpull(3000.0.mm)\n'
        '  extruded += 1\n'
        'end\n'
        'result = {\n'
        '  "layer" => target_layer,\n'
        '  "height_mm" => 3000.0,\n'
        '  "faces_extruded" => extruded\n'
        '}\n'
        'puts result.to_json\n'
        'result.to_json'
    )


def test_extrude_walls_uses_mm_suffix_not_precomputed_inches():
    """mm 값을 Python 에서 인치로 미리 계산하지 않는다 — SketchUp 자신의
    Numeric#mm 변환 메서드를 쓴다 (모듈 docstring의 설계 근거)."""
    script = sk.extrude_walls_script(3000.0)
    assert "3000.0.mm" in script
    assert "118.11" not in script  # 미리 계산된 인치 값이 들어가면 안 된다


def test_extrude_walls_accepts_int_height():
    script = sk.extrude_walls_script(3000)
    assert "3000.0.mm" in script  # float() 로 정규화된다


def test_extrude_walls_custom_layer_is_escaped_and_used():
    script = sk.extrude_walls_script(2500.0, layer="exterior-walls")
    assert 'target_layer = "exterior-walls"' in script
    assert script.count('"exterior-walls"') == 1  # ruby_string 이 한 번만 만든다


def test_save_skp_script_is_byte_exact():
    script = sk.save_skp_script(r"C:\hermes-projects\room01\02-model\model.skp")

    assert script == (
        'model = Sketchup.active_model\n'
        'path = "C:\\\\hermes-projects\\\\room01\\\\02-model\\\\model.skp"\n'
        'saved = model.save(path)\n'
        'result = { "path" => path, "saved" => saved }\n'
        'puts result.to_json\n'
        'result.to_json'
    )


def test_export_fbx_script_is_byte_exact():
    script = sk.export_fbx_script(r"C:\hermes-projects\room01\02-model\model.fbx")

    assert script == (
        'model = Sketchup.active_model\n'
        'path = "C:\\\\hermes-projects\\\\room01\\\\02-model\\\\model.fbx"\n'
        'exported = model.export(path)\n'
        'result = { "path" => path, "exported" => exported }\n'
        'puts result.to_json\n'
        'result.to_json'
    )


def test_capture_iso_script_is_byte_exact_with_defaults():
    script = sk.capture_iso_script(r"C:\hermes-projects\room01\02-model\iso.png")

    assert script == (
        'model = Sketchup.active_model\n'
        'Sketchup.send_action("viewIso:")\n'
        'view = model.active_view\n'
        'path = "C:\\\\hermes-projects\\\\room01\\\\02-model\\\\iso.png"\n'
        'options = {\n'
        '  :filename => path,\n'
        '  :width => 1920,\n'
        '  :height => 1080,\n'
        '  :antialias => true\n'
        '}\n'
        'success = view.write_image(options)\n'
        'result = { "path" => path, "success" => success }\n'
        'puts result.to_json\n'
        'result.to_json'
    )


def test_capture_iso_script_accepts_custom_resolution():
    script = sk.capture_iso_script(r"C:\iso.png", width=800, height=600)
    assert ":width => 800" in script
    assert ":height => 600" in script


def test_unit_check_script_is_byte_exact():
    script = sk.unit_check_script()

    assert script == (
        'model = Sketchup.active_model\n'
        'units = model.options["UnitsOptions"]\n'
        'result = {\n'
        '  "length_unit" => units["LengthUnit"],\n'
        '  "length_format" => units["LengthFormat"]\n'
        '}\n'
        'puts result.to_json\n'
        'result.to_json'
    )


def test_every_script_ends_with_the_shared_json_footer():
    """eval_ruby 브리지가 stdout 을 캡처하는지 마지막 표현식을 돌려주는지
    이 PC 에서는 확인할 수 없어서 두 경로 다 대비한다."""
    scripts = [
        sk.import_dwg_script(r"C:\a.dwg"),
        sk.extrude_walls_script(1000.0),
        sk.save_skp_script(r"C:\a.skp"),
        sk.export_fbx_script(r"C:\a.fbx"),
        sk.capture_iso_script(r"C:\a.png"),
        sk.unit_check_script(),
    ]
    for script in scripts:
        assert script.endswith("puts result.to_json\nresult.to_json")


def test_no_script_contains_a_bare_windows_backslash():
    """이스케이프를 빠뜨리면 Ruby 가 백슬래시 뒤 문자를 이스케이프 시퀀스로
    오해석해 경로가 깨진다 — 모든 백슬래시는 반드시 짝(\\\\)이어야 한다."""
    script = sk.import_dwg_script(r"C:\hermes-projects\room01\01-cad\plan.dwg")
    path_line = [l for l in script.splitlines() if "model.import" in l][0]
    # 홀수 개의 연속 백슬래시가 없어야 한다(전부 짝수로 이스케이프됐다는 뜻).
    import re

    for run in re.findall(r"\\+", path_line):
        assert len(run) % 2 == 0
