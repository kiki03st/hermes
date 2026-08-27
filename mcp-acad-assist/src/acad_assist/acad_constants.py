"""AutoCAD ActiveX 열거형 상수 — 런타임 조회 우선, 검증된 폴백은 최소한만.

왜 하드코딩하지 않는가 (조사 결과, 2026-08-28):

`AcSaveAsType` 은 `SaveAs(path, FileType)` 의 두 번째 인자로 쓰는 열거형인데,
Autodesk 는 **이름만 공개하고 숫자값은 릴리스별 API History 페이지에만** 적어놓았다.
게다가 `acNative` 는 릴리스마다 값이 움직이는 별칭이다 (2018 세대에서 64로 바뀌었다).
널리 인용되는 표(`ac2013_dxf = 61` 등)는 1차 출처로 확인되지 않는다.

그래서 이 모듈은:
1. `win32com.client.constants` 에서 **실행 중인 AutoCAD 의 타입 라이브러리 값을 읽는다.**
   (`Win32AcadPort.reconnect()` 가 `gencache.EnsureDispatch` 로 캐시를 만들어 둔다.)
2. 그게 안 되면 **1차 출처로 확인된 것만** 폴백으로 쓴다.
3. 둘 다 안 되면 **값을 지어내지 않고 실패한다** — 잘못된 FileType 으로 저장하면
   파일이 조용히 다른 포맷으로 나가거나 열리지 않는다.
"""

from __future__ import annotations

from typing import Any

#: 1차 출처(Autodesk AutoCAD 2018 API History)로 확인된 값만.
#: 다른 릴리스 값은 확인되지 않았으므로 **추가하지 않는다** — 런타임 조회로 처리한다.
VERIFIED_SAVE_AS_TYPE: dict[str, int] = {
    "ac2018_dwg": 64,
    "ac2018_dxf": 65,
    "ac2018_Template": 66,
}

#: 이 파이프라인의 기본 저장 세대. 폴백 표가 존재하는 유일한 세대이기도 하다.
DEFAULT_DWG_VERSION = "2018"

#: `fmt` → `AcSaveAsType` 이름 템플릿.
_NAME_TEMPLATE: dict[str, str] = {
    "dwg": "ac{version}_dwg",
    "dxf": "ac{version}_dxf",
    "template": "ac{version}_Template",
}


class ConstantUnavailable(RuntimeError):
    """상수를 런타임에도 폴백에서도 확정할 수 없을 때.

    호출자는 이 예외를 사용자에게 그대로 보여줘야 한다 — "AutoCAD 를 띄운 상태로
    다시 시도하라"가 실제 해결책이기 때문이다.
    """


def save_as_type_name(fmt: str, version: str = DEFAULT_DWG_VERSION) -> str:
    """`fmt`/`version` 에 대응하는 `AcSaveAsType` 열거형 이름."""
    try:
        template = _NAME_TEMPLATE[fmt]
    except KeyError:
        raise ConstantUnavailable(
            f"저장 포맷 {fmt!r} 에 대응하는 AcSaveAsType 이름을 모릅니다. "
            f"가능한 값: {', '.join(sorted(_NAME_TEMPLATE))}"
        ) from None
    return template.format(version=version)


def lookup_runtime(name: str) -> int | None:
    """`win32com.client.constants` 에서 상수를 읽는다. 없으면 None.

    타입 라이브러리 캐시가 만들어져 있어야 값이 채워진다. AutoCAD 가 떠 있지 않거나
    pywin32 가 없는 환경(이 개발 PC)에서는 항상 None 이다.
    """
    try:
        import win32com.client

        value = getattr(win32com.client.constants, name)
    except Exception:
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def save_as_type(
    fmt: str,
    version: str = DEFAULT_DWG_VERSION,
    *,
    runtime_lookup: Any = None,
) -> int:
    """`SaveAs` 의 `FileType` 인자에 넣을 정수값.

    Args:
        fmt: `dwg` / `dxf` / `template`.
        version: AutoCAD 파일 포맷 세대 (`"2018"` 등).
        runtime_lookup: 테스트용 주입 지점. `name -> int | None` 콜러블.

    Raises:
        ConstantUnavailable: 런타임 조회도 실패하고 검증된 폴백에도 없을 때.
    """
    name = save_as_type_name(fmt, version)
    lookup = runtime_lookup or lookup_runtime

    value = lookup(name)
    if value is not None:
        return value

    if name in VERIFIED_SAVE_AS_TYPE:
        return VERIFIED_SAVE_AS_TYPE[name]

    raise ConstantUnavailable(
        f"AcSaveAsType 상수 {name!r} 를 확정할 수 없습니다.\n"
        f"- 실행 중인 AutoCAD 의 타입 라이브러리에서 읽지 못했습니다 "
        f"(AutoCAD 가 떠 있는지, pywin32 가 설치됐는지 확인).\n"
        f"- 검증된 폴백 표에도 없습니다 (있는 것: "
        f"{', '.join(sorted(VERIFIED_SAVE_AS_TYPE))}).\n"
        f"이 값을 추측해서 저장하면 파일이 다른 포맷으로 나갈 수 있어 진행하지 않습니다. "
        f"version={DEFAULT_DWG_VERSION!r} 로 시도하거나, AutoCAD 를 띄운 뒤 다시 호출하세요."
    )
