"""단위계 — mm ↔ 인치 ↔ Max 시스템 단위.

왜 이 모듈이 필요한가 (조사 결과, 2026-08-28):

PLAN.md는 "AutoCAD mm ↔ SketchUp mm ↔ Max System Unit"을 맞추는 게 Stage 4 첫 작업이라고
적었지만, 실제로 확인해보니 **SketchUp 쪽은 "맞추는" 문제가 아니라 "변환하는" 문제**다.
`mhyrr/sketchup-mcp`은 단위계를 전혀 다루지 않고 raw float를 Ruby API에 그대로 넘긴다.
그런데 SketchUp Ruby API의 내부 길이 단위는 **인치**다. 즉 mm 값을 그대로 주면
SketchUp은 그것을 인치로 해석한다 (3000mm → 3000인치 = 76.2m).

그래서 mm↔인치 변환을 우리가 명시적으로 해야 한다. 이 모듈이 그 단일 지점이다.

AutoCAD 쪽은 `INSUNITS` 시스템 변수로 도면 단위를 읽는다. 값의 의미는 AutoCAD가 정한
열거형이고 여기서 쓰는 것만 정의해둔다 (mm=4가 기존 테스트 계약: tests/fakes.py).
"""

from __future__ import annotations

from typing import Any

#: AutoCAD `INSUNITS` 시스템 변수 값 → 사람이 읽는 이름.
#: AutoCAD가 정한 열거형의 부분집합 — 이 파이프라인에서 마주칠 수 있는 것만.
INSUNITS: dict[int, str] = {
    0: "unitless",
    1: "inches",
    2: "feet",
    4: "millimeters",
    5: "centimeters",
    6: "meters",
}

#: 이 파이프라인이 표준으로 삼는 도면 단위. PLAN.md가 mm로 못박았다.
CANONICAL = "millimeters"

#: `INSUNITS` 에서 CANONICAL 에 해당하는 값.
CANONICAL_INSUNITS = 4

#: 1 인치 = 25.4 mm (국제 인치, 정의값이라 근사가 아니다).
MM_PER_INCH = 25.4


class UnitMismatchError(RuntimeError):
    """도면 단위가 파이프라인 표준(mm)과 다를 때.

    기본 정책은 **fail-loud**다 — 조용히 보정하면 3m 벽이 76m가 되는 식의 사고가
    한참 뒤 렌더 단계에서야 드러난다. 자동 보정은 호출자가 명시적으로 요청해야 한다
    (`require_millimeters(..., allow_conversion=True)`).
    """


def insunits_name(value: Any) -> str:
    """`INSUNITS` 원시값을 사람이 읽는 이름으로. 모르는 값은 그대로 노출한다."""
    try:
        return INSUNITS[int(value)]
    except (KeyError, TypeError, ValueError):
        return f"unknown({value!r})"


def require_millimeters(insunits: Any, *, allow_conversion: bool = False) -> float:
    """도면 단위를 검사하고 mm 로 가는 배율을 돌려준다.

    Args:
        insunits: `doc.GetVariable("INSUNITS")` 원시값.
        allow_conversion: True 면 mm 가 아닌 알려진 단위도 배율을 계산해 통과시킨다.
            False(기본)면 mm 가 아닌 즉시 `UnitMismatchError`.

    Returns:
        도면 단위 → mm 변환 배율. mm 도면이면 1.0.

    Raises:
        UnitMismatchError: mm 가 아니고 `allow_conversion` 이 False 이거나,
            단위 자체를 알 수 없을 때(그때는 `allow_conversion` 과 무관하게 실패한다 —
            모르는 단위에 배율을 지어낼 수는 없다).
    """
    name = insunits_name(insunits)
    if name == CANONICAL:
        return 1.0

    factor = _TO_MM.get(name)
    if factor is None:
        raise UnitMismatchError(
            f"도면 단위를 알 수 없습니다 (INSUNITS={insunits!r} → {name}). "
            f"이 파이프라인은 {CANONICAL}(INSUNITS={CANONICAL_INSUNITS})를 표준으로 씁니다. "
            f"AutoCAD 에서 단위를 mm 로 맞추고 다시 시도하세요."
        )

    if not allow_conversion:
        raise UnitMismatchError(
            f"도면 단위가 {name} 입니다 — 이 파이프라인 표준은 {CANONICAL} 입니다. "
            f"자동 보정을 원하면 allow_conversion=True 로 다시 호출하세요 "
            f"(배율 {factor} 가 적용됩니다)."
        )
    return factor


#: 알려진 단위 → mm 배율. `unitless` 는 의도적으로 제외 — 배율을 알 수 없다.
_TO_MM: dict[str, float] = {
    "millimeters": 1.0,
    "centimeters": 10.0,
    "meters": 1000.0,
    "inches": MM_PER_INCH,
    "feet": MM_PER_INCH * 12.0,
}


def mm_to_inch(mm: float) -> float:
    """mm → 인치. SketchUp Ruby API 에 길이를 넘길 때 쓴다.

    SketchUp 의 내부 길이 단위가 인치이고 sketchup-mcp 은 변환을 하지 않으므로,
    Ruby 스니펫에 박아 넣는 숫자는 전부 이 함수를 통과해야 한다.
    """
    return mm / MM_PER_INCH


def inch_to_mm(inch: float) -> float:
    """인치 → mm. SketchUp 이 돌려준 값을 도면 좌표계로 되돌릴 때 쓴다."""
    return inch * MM_PER_INCH


def describe(insunits: Any) -> dict[str, Any]:
    """단위 상태를 그대로 보고용 dict 로. `meta.json` 의 `units` 필드에 들어간다."""
    name = insunits_name(insunits)
    return {
        "insunits": insunits,
        "name": name,
        "canonical": name == CANONICAL,
        "to_mm": _TO_MM.get(name),
    }
