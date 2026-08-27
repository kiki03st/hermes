"""좌표 VARIANT 변환과 AcSaveAsType 상수 해석.

이 개발 PC 에는 AutoCAD·pywin32 의 VARIANT 경로를 실행할 대상이 없다. 그래서
검증 가능한 것 — 입력 정규화, 검증, 3D 승격, 상수 조회 우선순위 — 만 테스트한다.
VARIANT 로 감싸는 마지막 한 겹은 대상 환경 체크리스트로 넘어간다.
"""

from __future__ import annotations

import pytest

from acad_assist import acad_constants, variants


# ---------------------------------------------------------------- variants


def test_to_doubles_promotes_2d_to_3d():
    assert variants.to_doubles([1, 2]) == (1.0, 2.0, 0.0)


def test_to_doubles_keeps_3d_and_coerces_to_float():
    assert variants.to_doubles((1, 2, 3)) == (1.0, 2.0, 3.0)


def test_to_doubles_rejects_strings_explicitly():
    """'1,2,3' 은 시퀀스라 길이 검사만 하면 통과하고 엉뚱한 좌표가 된다."""
    with pytest.raises(variants.CoordinateError) as exc:
        variants.to_doubles("1,2,3")
    assert "문자열" in str(exc.value)


@pytest.mark.parametrize("bad", [5, None, {"x": 1}, [1], [1, 2, 3, 4]])
def test_to_doubles_rejects_bad_shapes(bad):
    with pytest.raises(variants.CoordinateError):
        variants.to_doubles(bad)


def test_to_doubles_rejects_non_numeric_elements():
    with pytest.raises(variants.CoordinateError):
        variants.to_doubles([1, "x", 3])


def test_to_doubles_error_names_the_argument():
    with pytest.raises(variants.CoordinateError) as exc:
        variants.to_doubles([1], name="from_point")
    assert "from_point" in str(exc.value)


def test_a_double_returns_normalized_tuple_without_pywin32():
    """pywin32 VARIANT 를 못 만드는 환경에서는 정규화된 튜플이 그대로 나온다 —
    기존 테스트 fake 가 튜플을 기록하는 계약과 맞는다."""
    result = variants.a_double([1, 2])
    assert result == (1.0, 2.0, 0.0) or hasattr(result, "value")


def test_flatten_points_builds_flat_array():
    flat = variants.flatten_points([[0, 0], [10, 0], [10, 5]])
    assert flat == [0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 10.0, 5.0, 0.0]


def test_flatten_points_rejects_empty():
    with pytest.raises(variants.CoordinateError):
        variants.flatten_points([])


def test_flatten_points_error_points_at_the_bad_index():
    with pytest.raises(variants.CoordinateError) as exc:
        variants.flatten_points([[0, 0], [1]])
    assert "points[1]" in str(exc.value)


def test_doubles_array_coerces_numbers():
    result = variants.doubles_array([1, 2, 3])
    assert result == (1.0, 2.0, 3.0) or hasattr(result, "value")


# ---------------------------------------------------------- acad_constants


def test_save_as_type_name_templates():
    assert acad_constants.save_as_type_name("dwg", "2018") == "ac2018_dwg"
    assert acad_constants.save_as_type_name("dxf", "2013") == "ac2013_dxf"
    assert acad_constants.save_as_type_name("template", "2018") == "ac2018_Template"


def test_save_as_type_name_rejects_unknown_format():
    with pytest.raises(acad_constants.ConstantUnavailable):
        acad_constants.save_as_type_name("svg")


def test_runtime_lookup_wins_over_fallback():
    """실행 중인 AutoCAD 의 타입 라이브러리 값이 항상 우선이다 —
    릴리스마다 값이 움직이기 때문."""
    value = acad_constants.save_as_type(
        "dwg", "2018", runtime_lookup=lambda name: 999
    )
    assert value == 999


def test_verified_fallback_used_when_runtime_unavailable():
    assert acad_constants.save_as_type("dwg", "2018", runtime_lookup=lambda n: None) == 64
    assert acad_constants.save_as_type("dxf", "2018", runtime_lookup=lambda n: None) == 65


def test_unverified_version_refuses_to_guess():
    """ac2013_dxf = 61 같은 널리 인용되는 값은 1차 출처로 확인되지 않았다.
    추측해서 저장하면 파일이 조용히 다른 포맷으로 나간다."""
    with pytest.raises(acad_constants.ConstantUnavailable) as exc:
        acad_constants.save_as_type("dxf", "2013", runtime_lookup=lambda n: None)
    msg = str(exc.value)
    assert "ac2013_dxf" in msg
    assert "추측" in msg


def test_fallback_table_holds_only_primary_source_verified_values():
    assert set(acad_constants.VERIFIED_SAVE_AS_TYPE) == {
        "ac2018_dwg",
        "ac2018_dxf",
        "ac2018_Template",
    }


def test_lookup_runtime_returns_none_without_autocad():
    """이 개발 PC 에는 AutoCAD 가 없으므로 None 이어야 한다 (예외를 던지면 안 된다)."""
    assert acad_constants.lookup_runtime("ac2018_dwg") is None
