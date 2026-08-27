"""SketchUp Ruby 스니펫 생성기.

Hermes가 `eval_ruby`(sketchup-mcp)에 그대로 넣을 Ruby 텍스트를 만든다. LLM이
매번 스크립트를 즉흥 생성하지 않게 하려는 것이 목적이다(PLAN.md Stage 4-3) —
그래서 여기서 만드는 텍스트는 입력이 같으면 항상 바이트 단위로 같아야 한다
(골든 테스트가 그걸 고정한다). 스킬(`cad-pipeline.md`)은 "이 함수가 준 문자열을
한 글자도 고치지 말고 그대로 `eval_ruby`에 넣어라"라고 못박는다.

이 개발 PC에는 SketchUp이 없어서 실행 검증은 불가능하다. 대신 SketchUp Ruby
API는 오래되고 안정적인 표준 인터페이스라 문서화된 계약(`Model#import`,
`Model#save`, `Model#export`, `View#write_image`, `Numeric#mm` 등)만 쓴다 —
추측한 메서드명이나 시그니처는 없다.

**단위: mm 값은 여기서 인치로 변환하지 않는다.** SketchUp Ruby는
`Numeric#mm`/`#cm`/`#m`/`#feet` 같은 길이 변환 메서드를 내장하고 있어서, 생성한
Ruby 텍스트 안에 `3000.0.mm`처럼 그대로 심으면 SketchUp이 직접 정확하게
변환한다. Python 쪽에서 25.4로 나눠 인치 실수를 미리 계산해 심는 것보다 이게
더 정확하고(부동소수점 서식 오차가 생성 텍스트에 안 남는다) 스크립트를 사람이
읽을 때도 더 명확하다 — `units.mm_to_inch`는 SketchUp이 돌려준 값을 다시 mm로
해석해 보고할 때(반대 방향) 쓴다. 이게 F-2가 발견한 "SketchUp 내부 단위는
인치"라는 사실에 대한 실제 대응이다 — sketchup-mcp의 파이썬 도구(`create_component`
등)로 raw float를 넘기면 mm가 인치로 오해석되지만, 우리는 그 경로를 안 쓰고
`eval_ruby`로 직접 Ruby를 실행하므로 SketchUp 자신의 변환 메서드를 그대로 쓸 수
있다.
"""

from __future__ import annotations


def ruby_string(value: str) -> str:
    """Ruby 큰따옴표 문자열 리터럴로 이스케이프한다.

    Windows 경로의 백슬래시를 그대로 두면 Ruby가 `\\n` 같은 걸 이스케이프
    시퀀스로 해석해 경로가 깨진다. 개행도 함께 막는다 — 경로에 개행이 있을 리
    없지만, 만에 하나 있으면 생성된 스크립트 자체가 깨진 여러 줄이 된다.
    """
    out = value.replace("\\", "\\\\").replace('"', '\\"')
    out = out.replace("\n", "\\n").replace("\r", "\\r")
    return f'"{out}"'


def _footer(var: str = "result") -> str:
    """모든 스니펫이 공유하는 마무리.

    `eval_ruby` 브리지가 stdout 을 캡처하는지 마지막 표현식 값을 돌려주는지
    이 개발 PC에서는 확인할 수 없어서(SketchUp이 없다) 둘 다 대비한다 — JSON
    한 줄을 표준출력에 쓰고, 같은 값을 마지막 표현식으로도 남긴다.
    """
    return f"puts {var}.to_json\n{var}.to_json"


def import_dwg_script(dwg_path: str) -> str:
    """DWG를 임포트하고, 새로 들어온 엔티티를 그룹으로 묶어 핸들을 돌려준다.

    `Model#import`는 성공 여부만 Boolean으로 돌려주고 임포트된 엔티티는 알려주지
    않는다(SketchUp Ruby API 문서 계약) — 그래서 임포트 전후의 `active_entities`를
    diff 해서 무엇이 새로 들어왔는지 직접 찾는다. `.skp` 를 3ds Max가 직접
    임포트하는 게 확정안이라(PLAN.md) FBX 저장은 별도 함수(`export_fbx_script`)로
    둔다 — 이 함수는 DWG → SketchUp 한 방향만 책임진다.
    """
    path = ruby_string(dwg_path)
    return f'''model = Sketchup.active_model
before = model.active_entities.to_a
status = model.import({path}, false)
after = model.active_entities.to_a
imported = after - before
group = imported.empty? ? nil : model.active_entities.add_group(imported)
result = {{
  "imported" => status ? true : false,
  "entity_count" => imported.length,
  "group_id" => group ? group.entityID : nil
}}
{_footer()}'''


def extrude_walls_script(height_mm: float, *, layer: str = "walls") -> str:
    """지정 레이어의 면(face)을 전부 height_mm만큼 밀어올린다(push/pull).

    가정(대상 환경에서 실 DWG로 확인 전까지는 가정이다): 벽 레이어의 닫힌
    폴리라인/해치가 DWG 임포트 시 SketchUp 면으로 들어온다. AutoCAD 도면이
    벽을 열린 선(폭 없는 중심선)으로만 그린 경우 이 스니펫은 면을 하나도 못
    찾는다 — `faces_extruded: 0`으로 보고되니 그 경우 벽 표현 방식부터 다시
    봐야 한다. 벽끼리 모서리를 공유하는 경우 순서대로 밀어올리며 위상이 바뀔
    수 있다는 것도 알려진 제약이다.
    """
    height_mm = float(height_mm)
    target_layer = ruby_string(layer)
    return f'''model = Sketchup.active_model
target_layer = {target_layer}
entities = model.active_entities
faces = entities.grep(Sketchup::Face).select {{ |f| f.layer.name == target_layer }}
extruded = 0
faces.each do |f|
  f.pushpull({height_mm!r}.mm)
  extruded += 1
end
result = {{
  "layer" => target_layer,
  "height_mm" => {height_mm!r},
  "faces_extruded" => extruded
}}
{_footer()}'''


def save_skp_script(output_path: str) -> str:
    """모델을 `.skp`로 저장한다. `Model#save(path)`는 성공 여부를 Boolean으로 돌려준다."""
    path = ruby_string(output_path)
    return f'''model = Sketchup.active_model
path = {path}
saved = model.save(path)
result = {{ "path" => path, "saved" => saved }}
{_footer()}'''


def export_fbx_script(output_path: str) -> str:
    """FBX로 내보낸다 — SketchUp **Pro** 전용 기능이다(무료판엔 이 포맷이 없다).

    파이프라인 규약(PLAN.md)상 3ds Max로 넘길 때를 위한 **선택** 산출물이다.
    Max는 `.skp`를 직접 임포트하는 게 확정안이라(`maxscripts.import_skp_script`)
    FBX가 없어도 파이프라인 자체는 끊기지 않는다.
    """
    path = ruby_string(output_path)
    return f'''model = Sketchup.active_model
path = {path}
exported = model.export(path)
result = {{ "path" => path, "exported" => exported }}
{_footer()}'''


def capture_iso_script(output_path: str, *, width: int = 1920, height: int = 1080) -> str:
    """아이소메트릭 뷰로 전환해 PNG로 저장한다.

    `Sketchup.send_action("viewIso:")`는 SketchUp UI의 "Iso" 뷰 버튼과 같은
    동작을 스크립트에서 트리거하는 표준 관용구다 — 공식 API가 카메라 각도를
    "isometric"이라는 이름으로 직접 노출하지 않아서, 커뮤니티가 오래 써 온
    이 방식을 그대로 따른다. `View#write_image`의 옵션 해시 키(`:filename`,
    `:width`, `:height`, `:antialias`)는 SketchUp Ruby API 문서에 있는 것만 썼다.
    """
    path = ruby_string(output_path)
    return f'''model = Sketchup.active_model
Sketchup.send_action("viewIso:")
view = model.active_view
path = {path}
options = {{
  :filename => path,
  :width => {int(width)},
  :height => {int(height)},
  :antialias => true
}}
success = view.write_image(options)
result = {{ "path" => path, "success" => success }}
{_footer()}'''


def unit_check_script() -> str:
    """모델의 길이 단위 옵션을 있는 그대로 보고한다 — 진단·기록용.

    `LengthUnit`/`LengthFormat`의 정확한 열거값 매핑은 SketchUp 버전마다 문서
    표기가 갈려 여기서 해석하지 않는다(값을 지어내지 않는다는 원칙) — 원시값을
    그대로 돌려주고 판단은 호출자에게 맡긴다. 실제 좌표·치수 변환은 이 모듈이
    전부 `.mm` 스코프로 SketchUp에 직접 맡기므로(모듈 docstring 참고), 이 도구
    자체가 파이프라인 동작을 좌우하지는 않는다.
    """
    return f'''model = Sketchup.active_model
units = model.options["UnitsOptions"]
result = {{
  "length_unit" => units["LengthUnit"],
  "length_format" => units["LengthFormat"]
}}
{_footer()}'''
