package com.hermes.app.ui.chat

/** 확장자→MIME 타입 순수 함수. `android.webkit.MimeTypeMap`은 Android 프레임워크라
 * 로컬 유닛테스트 불가능(Robolectric 없음, 이 프로젝트 전체 방침) — 대신 직접
 * 매핑한다(`MimeTypeTest.kt`). 목록은 실제로 나오는 확장자만 그때그때 추가(YAGNI). */
fun guessMimeType(filename: String): String {
    val extension = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return _MIME_BY_EXTENSION[extension] ?: "application/octet-stream"
}

/** [mimeType]이 UTF-8 텍스트로 디코드해서 그대로 보여줘도 되는 형식인지 — 이거면
 * `FileChip` 탭 시 원문 미리보기를 띄운다. "text/"로 시작하는 타입 전부 + `application/json`만
 * (마크다운 서식 렌더링은 범위 밖, 원문 그대로만 보여준다, YAGNI). */
fun isPreviewableText(mimeType: String): Boolean =
    mimeType.startsWith("text/") || mimeType == "application/json"

/** [isPreviewableText] 중에서도 [mimeType]이 실제로 브라우저 렌더링해서 보여줄
 * 대상인지 — 다이어그램 스킬(architecture-diagram 등) 산출물이 여기 해당한다.
 * 클로드 Artifacts처럼 소스코드가 아니라 완성된 그림으로 보여주기 위함
 * (설계 문서: 2026-08-30, TextPreviewDialog의 WebView 분기). */
fun isRenderableHtml(mimeType: String): Boolean = mimeType == "text/html"

private val _MIME_BY_EXTENSION = mapOf(
    "md" to "text/markdown",
    "txt" to "text/plain",
    "pdf" to "application/pdf",
    "csv" to "text/csv",
    "json" to "application/json",
    "zip" to "application/zip",
    // architecture-diagram 등 다이어그램 스킬이 SVG를 HTML로 감싸서 내보낸다
    // (file-redirect 플러그인이 이 확장자를 리다이렉트 대상에 추가한 것과 짝).
    "html" to "text/html",
    // excalidraw 스킬 산출물 — 내용 자체는 표준 JSON이라 원문 미리보기는 가능하다
    // (excalidraw.com 캔버스 렌더링은 범위 밖, YAGNI — HTML처럼 WebView로 그리려면
    // excalidraw 자체 JS 라이브러리가 필요하다).
    "excalidraw" to "application/json",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
)
