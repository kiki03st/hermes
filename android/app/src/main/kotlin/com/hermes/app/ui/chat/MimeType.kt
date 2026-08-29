package com.hermes.app.ui.chat

/** 확장자→MIME 타입 순수 함수. `android.webkit.MimeTypeMap`은 Android 프레임워크라
 * 로컬 유닛테스트 불가능(Robolectric 없음, 이 프로젝트 전체 방침) — 대신 직접
 * 매핑한다(`MimeTypeTest.kt`). 목록은 실제로 나오는 확장자만 그때그때 추가(YAGNI). */
fun guessMimeType(filename: String): String {
    val extension = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return _MIME_BY_EXTENSION[extension] ?: "application/octet-stream"
}

private val _MIME_BY_EXTENSION = mapOf(
    "md" to "text/markdown",
    "txt" to "text/plain",
    "pdf" to "application/pdf",
    "csv" to "text/csv",
    "json" to "application/json",
    "zip" to "application/zip",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
)
