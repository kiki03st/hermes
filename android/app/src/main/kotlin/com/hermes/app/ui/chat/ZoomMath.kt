package com.hermes.app.ui.chat

/** 전체화면 이미지 뷰어의 핀치줌 배율을 [min]~[max] 안으로 자른다 — Compose 프레임워크
 * 의존 없는 순수 함수라 유닛테스트 가능(`ZoomMathTest.kt`). */
fun clampZoom(scale: Float, min: Float = 1f, max: Float = 5f): Float = scale.coerceIn(min, max)
