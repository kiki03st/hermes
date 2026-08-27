"""3ds Max + V-Ray MAXScript 스니펫 생성기.

Hermes가 `execute_maxscript`(3dsmax-mcp)에 그대로 넣을 MAXScript 텍스트를
만든다. LLM이 매번 스크립트를 즉흥 생성하지 않게 하려는 것이 목적이다
(PLAN.md Stage 5-2). 입력이 같으면 항상 바이트 단위로 같은 텍스트가 나온다
(골든 테스트가 그걸 고정한다).

이 개발 PC에는 3ds Max가 없어서 실행 검증은 불가능하다. 그래서 여기서 쓰는
API는 다음 둘 중 하나로 한정한다:
1. Autodesk MAXScript 공식 문서에 있는, 오래되고 안정적인 계약
   (`importFile`, `Model#save` 대응인 `saveMaxFile`류, `renderers` 인터페이스,
   `rendererClass.classes`, `max quick render`, `doesFileExist`, `Targetcamera`,
   `Omnilight`, `Skylight` 등).
2. 계획 조사(§F-3)로 실측한 3dsmax-mcp 자체의 계약 — 특히 **`render()`와
   `render_scene`이 Render Setup 대화상자 설정을 무시한다**는 사실. 이 때문에
   프로덕션 렌더는 `rendSaveFile`/`rendOutputFilename`/`renderWidth`/`renderHeight`
   글로벌을 직접 설정한 뒤 `max quick render`로 트리거하는 경로를 쓴다 — PLAN.md
   원안의 `render vfb:false outputSize:[...]` 스니펫은 이 글로벌들을 무시하는
   경로라 자기모순이었다(정정 완료).

**성공 확인은 자기 보고가 아니라 검증이다.** 각 스니펫은 "임포트로 오브젝트
수가 늘었는가", "렌더 출력 파일이 실제로 생겼는가"를 MAXScript 자체가 확인해
`throw`로 실패를 알린다 — `execute_maxscript`가 문서화한 에러 센티널
(`__MCP_MS_ERR__:`) 경로로 올라간다. 성공 여부를 MAXScript가 만든 JSON
불리언으로 자기 보고하게 하면 검증되지 않은 낙관적 보고가 될 수 있어 쓰지
않았다.
"""

from __future__ import annotations

import json

#: 프리셋 이름 → (width, height). PLAN.md Stage 5-4: 프리뷰(저해상도)/파이널(4K) 2개.
RENDER_PRESETS: dict[str, tuple[int, int]] = {
    "preview": (960, 540),
    "final": (3840, 2160),
}


class MaxScriptError(ValueError):
    """스니펫 생성 자체가 성립하지 않을 때(알 수 없는 프리셋, 빈 경로 등)."""


def maxscript_path_literal(path: str) -> str:
    """MAXScript `@"..."` 리터럴로 경로를 감싼다.

    `@"..."` 안에서는 백슬래시가 이스케이프 문자가 아니다 — Windows 경로를
    그대로 넣을 수 있는 이유이고, PLAN.md의 기존 스니펫도 이 문법을 썼다.
    대신 이 리터럴에는 이스케이프 메커니즘이 없어서 경로에 큰따옴표(`"`)가
    있으면 표현할 방법이 없다 — Windows가 애초에 파일명에 `"`를 금지하므로
    실무에서 마주칠 일은 없지만, 방어적으로 거부한다.
    """
    if '"' in path:
        raise MaxScriptError(
            f"경로에 큰따옴표를 쓸 수 없습니다 (MAXScript @'...' 리터럴 제약): {path!r}"
        )
    return f'@"{path}"'


def _print_json_line(data: dict) -> str:
    """MAXScript `print "..."` 한 줄로 JSON 텍스트를 그대로 출력하게 만든다.

    `data`는 전부 Python 쪽에서 이미 알고 있는 정적 값만 담는다 — 이 헬퍼는
    런타임에 MAXScript가 계산하는 값을 끼워 넣지 않는다. 이유는 모듈
    docstring의 "성공 확인은 자기 보고가 아니라 검증" 참고.

    **이중 이스케이프가 필요한 이유**: MAXScript 문자열 리터럴은 소스 코드에서
    런타임 문자열로 바뀌며 자신의 `\\`/`\"` 이스케이프를 한 번 해석한다. 최종
    stdout이 유효한 JSON(백슬래시가 2개씩)이 되려면 그 해석 *이후*의 런타임
    문자열이 이미 JSON 이스케이프가 끝난 상태여야 하고, 그 런타임 문자열을
    MAXScript 소스로 표현하려면 이스케이프를 한 번 더 해야 한다. 손으로 세면
    틀리기 쉬워서 여기 한 곳에서만 계산하고, `json.loads`로 실제 왕복
    검증하는 테스트를 둔다(`tests/test_maxscripts.py`).
    """
    json_text = json.dumps(data, ensure_ascii=False)
    escaped = json_text.replace("\\", "\\\\").replace('"', '\\"')
    return f'print "{escaped}"'


def select_vray_renderer_snippet() -> str:
    """V-Ray 렌더러를 버전 무관하게 찾아 production 렌더러로 지정한다.

    `V_Ray_Adv_1_50_SP6()`처럼 버전이 박힌 클래스명을 직접 쓰면 V-Ray SDK가
    올라갈 때마다 깨진다(계획 §F-3). `rendererClass.classes`를 이름 패턴으로
    훑어 인스턴스화하는 쪽이 버전에 안 흔들린다.
    """
    return '''vray_class = undefined
for c in rendererClass.classes do (
  if (matchPattern (c as string) pattern:"V_Ray*") then (
    vray_class = c
    exit
  )
)
if vray_class == undefined then (
  throw "no V-Ray renderer class found -- is V-Ray installed?"
)
renderers.production = vray_class()'''


def import_skp_script(skp_path: str) -> str:
    """SketchUp `.skp`를 직접 임포트한다 — FBX 아님, `.skp` 직접 임포트가
    확정안이다(PLAN.md: `smart_import`는 메시 폴더 일괄용이라 안 맞는다).

    `importFile`은 실패해도 예외를 안 던질 수 있어서(문서가 반환값을 명시하지
    않음), 임포트 전후 오브젝트 수를 비교해 늘지 않았으면 직접 `throw`한다.
    """
    path = maxscript_path_literal(skp_path)
    return f'''before_count = objects.count
importFile {path} #noPrompt
after_count = objects.count
if after_count <= before_count then (
  throw "importFile reported no new objects -- import likely failed"
)
{_print_json_line({"path": skp_path})}'''


def setup_camera_script(
    position: tuple[float, float, float],
    target: tuple[float, float, float],
    *,
    fov: float = 45.0,
    name: str = "Cam01",
) -> str:
    """타겟 카메라를 만들고 활성 뷰포트 카메라로 지정한다.

    `Targetcamera`/`Targetobject`/`viewport.setCamera`는 3ds Max MAXScript의
    안정된 표준 오브젝트 생성 API다. 카메라 위치·타겟·화각 값 자체는 이
    모듈이 판단하지 않는다 — 호출자(스킬/파이프라인)가 도면 크기에 맞춰
    계산해 넘긴다. **대상 환경에서 확인 필요**: 실제 장면에서 이 기본값이
    보기 좋은 구도를 주는지는 실물로 검증 전까지는 가정이다.
    """
    px, py, pz = (float(v) for v in position)
    tx, ty, tz = (float(v) for v in target)
    return f'''cam_target = Targetobject pos:[{tx!r},{ty!r},{tz!r}]
cam = Targetcamera pos:[{px!r},{py!r},{pz!r}] target:cam_target fov:{float(fov)!r}
cam.name = "{name}"
viewport.setCamera cam
{_print_json_line({"camera": name, "fov": float(fov)})}'''


def setup_lighting_script(*, sky_multiplier: float = 1.0) -> str:
    """기본 조명 — Skylight 하나 + Omni 필 라이트 둘.

    `Skylight`/`Omnilight`는 3ds Max의 가장 기본적인, 오래 안정된 라이트
    클래스다. Daylight System(태양+하늘 결합) 쪽이 더 사실적이지만 그
    생성자 시그니처를 이 개발 PC에서 확인할 방법이 없어서, 값을 지어내지
    않는다는 원칙에 따라 더 단순하지만 확실한 조합을 기본으로 삼는다.
    **대상 환경에서 확인 필요**: 실사 조명 품질이 목표라면 Daylight System
    으로 교체를 검토할 것.
    """
    return f'''sky = Skylight pos:[0,0,0] multiplier:{float(sky_multiplier)!r}
fill1 = Omnilight pos:[3000,-3000,3000] multiplier:0.6
fill2 = Omnilight pos:[-3000,3000,2000] multiplier:0.4
{_print_json_line({"lights": ["Skylight", "Omnilight", "Omnilight"]})}'''


def render_to_file_script(
    output_path: str,
    preset: str = "final",
    *,
    width: int | None = None,
    height: int | None = None,
) -> str:
    """V-Ray 프로덕션 렌더 → 파일 저장.

    `render_scene`도 MAXScript `render()`도 Render Setup 대화상자 설정을
    무시한다(계획 §F-3 실측) — 그래서 `rendSaveFile`/`rendOutputFilename`/
    `renderWidth`/`renderHeight` 글로벌을 직접 설정하고 `max quick render`로
    UI 렌더 액션을 트리거하는 경로를 쓴다. 이 경로만 V-Ray의 출력 설정을
    존중한다.

    Args:
        preset: `"preview"`(960×540) 또는 `"final"`(3840×2160). `width`/`height`
            를 명시하면 프리셋 해상도를 덮어쓴다.
    """
    if width is not None and height is not None:
        w, h = int(width), int(height)
    else:
        try:
            w, h = RENDER_PRESETS[preset]
        except KeyError:
            raise MaxScriptError(
                f"알 수 없는 렌더 프리셋: {preset!r}. "
                f"가능한 값: {', '.join(RENDER_PRESETS)} (또는 width/height 직접 지정)"
            ) from None

    path = maxscript_path_literal(output_path)
    vray_snippet = select_vray_renderer_snippet()
    return f'''{vray_snippet}
rendSaveFile = true
rendOutputFilename = {path}
renderWidth = {w}
renderHeight = {h}
max quick render
if not (doesFileExist rendOutputFilename) then (
  throw "render did not produce the expected output file"
)
{_print_json_line({"path": output_path, "width": w, "height": h, "preset": preset})}'''
