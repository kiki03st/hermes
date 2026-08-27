"""테스트 헬퍼.

`variants.a_double` 은 pywin32 가 있으면 `VARIANT(VT_ARRAY|VT_R8, [...])` 를 만든다.
이 개발 PC 에는 AutoCAD 는 없지만 pywin32 는 있으므로 **VARIANT 래핑 자체는 여기서
실제로 검증된다** — COM 이 그것을 받아주는지만 대상 환경 몫이다.

fake 엔티티는 인자를 그대로 기록하므로, 좌표를 비교할 때 VARIANT 든 튜플이든
같은 형태로 펴서 본다.
"""

from __future__ import annotations

from typing import Any

#: `VT_ARRAY | VT_R8`. VARIANT 가 실수 배열로 만들어졌는지 확인할 때 쓴다.
VT_ARRAY_R8 = 8197


def coords(value: Any) -> tuple[float, ...]:
    """VARIANT 또는 시퀀스에서 좌표를 튜플로 꺼낸다."""
    inner = getattr(value, "value", value)
    return tuple(float(v) for v in inner)


def is_variant_double_array(value: Any) -> bool:
    """실수 배열 VARIANT 로 감싸졌는지. pywin32 가 없는 환경에서는 False."""
    vartype = getattr(value, "varianttype", None)
    return vartype == VT_ARRAY_R8
