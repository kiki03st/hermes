"""단위계 — mm 표준, SketchUp 인치 변환, 불일치 시 fail-loud."""

from __future__ import annotations

import pytest

from acad_assist import units


def test_canonical_is_millimeters_matching_insunits_4():
    """기존 테스트 계약(tests/fakes.py 의 INSUNITS=4 == mm)과 일치해야 한다."""
    assert units.CANONICAL == "millimeters"
    assert units.CANONICAL_INSUNITS == 4
    assert units.insunits_name(4) == "millimeters"


@pytest.mark.parametrize(
    "value,expected",
    [(0, "unitless"), (1, "inches"), (2, "feet"), (4, "millimeters"), (5, "centimeters"), (6, "meters")],
)
def test_insunits_name_maps_known_values(value, expected):
    assert units.insunits_name(value) == expected


@pytest.mark.parametrize("value", [99, None, "x", 3.7])
def test_insunits_name_exposes_unknown_values_verbatim(value):
    assert units.insunits_name(value).startswith("unknown(")


def test_require_millimeters_passes_through_mm():
    assert units.require_millimeters(4) == 1.0


def test_require_millimeters_fails_loud_by_default():
    """조용히 보정하면 3m 벽이 76m 가 되는 사고가 렌더 단계에서야 드러난다."""
    with pytest.raises(units.UnitMismatchError) as exc:
        units.require_millimeters(6)  # meters
    assert "allow_conversion" in str(exc.value)


def test_require_millimeters_converts_when_explicitly_allowed():
    assert units.require_millimeters(6, allow_conversion=True) == 1000.0
    assert units.require_millimeters(5, allow_conversion=True) == 10.0
    assert units.require_millimeters(1, allow_conversion=True) == 25.4
    assert units.require_millimeters(2, allow_conversion=True) == 25.4 * 12


def test_unknown_units_fail_even_with_conversion_allowed():
    """모르는 단위에 배율을 지어낼 수는 없다."""
    for bad in (99, None, 0):  # 0 == unitless: 배율이 정의되지 않는다
        with pytest.raises(units.UnitMismatchError):
            units.require_millimeters(bad, allow_conversion=True)


def test_mm_inch_roundtrip_is_exact_for_the_defined_inch():
    assert units.MM_PER_INCH == 25.4
    assert units.mm_to_inch(25.4) == 1.0
    assert units.inch_to_mm(1.0) == 25.4
    for mm in (0.0, 1.0, 200.0, 3000.0, 12345.6):
        assert units.inch_to_mm(units.mm_to_inch(mm)) == pytest.approx(mm)


def test_mm_to_inch_is_what_sketchup_needs():
    """SketchUp Ruby API 는 내부 단위가 인치이고 sketchup-mcp 는 변환을 하지 않는다.
    3m 벽 높이를 mm 그대로 넘기면 3000인치(=76.2m)로 해석된다."""
    assert units.mm_to_inch(3000.0) == pytest.approx(118.11, abs=0.01)


def test_describe_shape_for_meta_json():
    d = units.describe(4)
    assert d == {"insunits": 4, "name": "millimeters", "canonical": True, "to_mm": 1.0}
    d2 = units.describe(6)
    assert d2["canonical"] is False and d2["to_mm"] == 1000.0
    d3 = units.describe(99)
    assert d3["canonical"] is False and d3["to_mm"] is None
