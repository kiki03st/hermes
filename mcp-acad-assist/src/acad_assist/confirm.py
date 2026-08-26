"""승인 게이트 1차: Hermes 승인 API에 의존하지 않고 무조건 동작하는 안전장치.

쓰기 도구는 `confirm=False`(기본값)면 실행하지 않고 무엇을 할지 요약과
영향받는 엔티티 수를 반환한다. 에이전트가 이를 사용자에게 보여주고,
사용자가 승인하면 `confirm=True`로 재호출해 실제로 실행한다.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, TypeVar

T = TypeVar("T")


@dataclass
class ActionPreview:
    summary: str
    affected_count: int
    preview_image: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "confirm_required": True,
            "summary": self.summary,
            "affected_count": self.affected_count,
            "preview_image": self.preview_image,
        }


def with_confirmation(
    confirm: bool,
    build_preview: Callable[[], ActionPreview],
    execute: Callable[[], T],
) -> ActionPreview | T:
    if not confirm:
        return build_preview()
    return execute()
