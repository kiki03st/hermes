"""좌표를 COM 이 받는 형태로 바꾸는 계층.

PLAN.md의 COM 취급 주의: *"좌표는 `VARIANT` 배열이어야 한다 (`pyautocad`/`pyacadcom`의
`aDouble` 헬퍼 방식 차용)"*.

AutoCAD ActiveX 의 점 인자는 `VARIANT(VT_ARRAY | VT_R8)` — 배정밀도 실수 배열이다.
파이썬 튜플이나 리스트를 그대로 넘기면 pywin32 가 알아서 변환해주는 경우도 있지만
보장되지 않고, 실패하면 `Type mismatch` 나 조용한 오동작(좌표가 0으로 들어감)이 된다.
그래서 모든 점 인자는 이 모듈을 통과한다.

이 개발 PC 에는 AutoCAD 가 없어 pywin32 의 VARIANT 경로를 실행 검증할 수 없다.
따라서 구조를 이렇게 잡았다:
- `to_doubles()` — 순수 파이썬. 입력 정규화·검증·3D 승격을 전부 여기서 하고 테스트한다.
- `a_double()` — VARIANT 로 감싼다. pywin32 가 없으면 튜플을 그대로 돌려준다
  (테스트 fake 는 튜플을 그대로 기록하므로 기존 테스트 계약이 유지된다).
"""

from __future__ import annotations

from typing import Any, Iterable, Sequence

#: AutoCAD 점은 3D 다. 2D 로 들어온 좌표는 z=0 으로 승격한다.
POINT_LEN = 3


class CoordinateError(ValueError):
    """좌표 형태가 AutoCAD 점으로 쓸 수 없을 때."""


def to_doubles(value: Any, *, name: str = "point") -> tuple[float, ...]:
    """점 하나를 길이 3의 float 튜플로 정규화한다.

    허용: 길이 2 또는 3의 시퀀스(리스트/튜플). 길이 2면 z=0.0 을 붙인다.
    거부: 스칼라, 문자열, 길이가 2·3이 아닌 것, float 로 못 바꾸는 원소.

    문자열을 명시적으로 거부하는 이유: `"1,2,3"` 은 시퀀스라서 길이 검사만 하면
    통과해버리고, 원소가 문자 하나씩이라 엉뚱한 좌표가 만들어진다.
    """
    if isinstance(value, (str, bytes)):
        raise CoordinateError(
            f"{name}: 좌표를 문자열로 줄 수 없습니다 ({value!r}). "
            "[x, y] 또는 [x, y, z] 형태의 숫자 배열로 주세요."
        )
    if not isinstance(value, Sequence):
        raise CoordinateError(
            f"{name}: 좌표는 숫자 배열이어야 합니다 (받은 것: {type(value).__name__})."
        )
    if len(value) not in (2, POINT_LEN):
        raise CoordinateError(
            f"{name}: 좌표 길이가 {len(value)} 입니다 — 2(2D) 또는 3(3D)이어야 합니다."
        )
    try:
        nums = [float(v) for v in value]
    except (TypeError, ValueError) as exc:
        raise CoordinateError(f"{name}: 좌표에 숫자가 아닌 값이 있습니다 ({value!r}).") from exc
    if len(nums) == 2:
        nums.append(0.0)
    return tuple(nums)


def a_double(value: Any, *, name: str = "point") -> Any:
    """점을 COM 이 받는 `VARIANT(VT_ARRAY | VT_R8)` 로 감싼다.

    pywin32 가 없으면(비-Windows, 이 개발 PC) 정규화된 튜플을 그대로 돌려준다.
    상위 계층은 반환값을 그대로 COM 메서드에 넘기면 된다.
    """
    coords = to_doubles(value, name=name)
    try:
        import pythoncom
        import win32com.client

        return win32com.client.VARIANT(
            pythoncom.VT_ARRAY | pythoncom.VT_R8, list(coords)
        )
    except Exception:
        return coords


def doubles_array(values: Iterable[float], *, name: str = "values") -> Any:
    """실수 여러 개(예: 폴리라인 좌표 평면 배열)를 VARIANT 배열로.

    폴리라인·해치처럼 점 여러 개를 하나의 평탄한 배열로 받는 API 용이다.
    """
    try:
        nums = [float(v) for v in values]
    except (TypeError, ValueError) as exc:
        raise CoordinateError(f"{name}: 숫자가 아닌 값이 있습니다.") from exc
    try:
        import pythoncom
        import win32com.client

        return win32com.client.VARIANT(pythoncom.VT_ARRAY | pythoncom.VT_R8, nums)
    except Exception:
        return tuple(nums)


def flatten_points(points: Iterable[Any], *, name: str = "points") -> list[float]:
    """점 리스트를 [x1,y1,z1, x2,y2,z2, ...] 평탄 배열로. 각 점은 검증을 거친다."""
    out: list[float] = []
    for i, pt in enumerate(points):
        out.extend(to_doubles(pt, name=f"{name}[{i}]"))
    if not out:
        raise CoordinateError(f"{name}: 점이 하나도 없습니다.")
    return out
