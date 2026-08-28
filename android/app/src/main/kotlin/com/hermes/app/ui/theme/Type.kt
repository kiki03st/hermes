package com.hermes.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Material3 기본 Typography()에서 본문(bodyLarge/bodyMedium)만 줄간격을 넓혀
// 채팅 메시지 가독성에 맞춘다 — 나머지 스케일은 기본값 그대로 상속.
private val base = Typography()

val AppTypography = base.copy(
    bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
)

/** 도구 활동 칩 등 아주 작은 보조 텍스트용 — Typography 스케일엔 없는 크기라 별도로 둔다. */
val ToolActivityLabelStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
