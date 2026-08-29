package com.hermes.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcalidrawRendererTest {

    @Test
    fun `isExcalidrawFile accepts the excalidraw extension case-insensitively`() {
        assertTrue(isExcalidrawFile("diagram.excalidraw"))
        assertTrue(isExcalidrawFile("DIAGRAM.EXCALIDRAW"))
    }

    @Test
    fun `isExcalidrawFile rejects other extensions`() {
        assertFalse(isExcalidrawFile("diagram.json"))
        assertFalse(isExcalidrawFile("diagram.html"))
    }

    @Test
    fun `buildExcalidrawViewerHtml embeds the scene JSON and imports exportToSvg from the pinned build`() {
        val scene = """{"elements": [], "appState": {}}"""

        val html = buildExcalidrawViewerHtml(scene)

        assertTrue(html.contains(scene))
        assertTrue(html.contains("await import("))
        // 실측 확인(2026-08-30): 안정 태그 0.1.2엔 exportToSvg 함수 자체가 없다(번들
        // 직접 받아서 문자열 검색, 0개 매치 — 다른 용도의 UMD 번들이었다). 문서에 나온
        // exportToSvg는 "-test32" 프리릴리즈 dist-tag에만 있어서, 이름이 마음에 안
        // 들어도 이거 아니면 함수 자체가 없다 — 그래서 이 버전으로 고정한다.
        assertTrue(html.contains("@excalidraw/utils@0.1.3-test32/"))
    }

    @Test
    fun `buildExcalidrawViewerHtml escapes a literal closing script tag inside the scene JSON`() {
        // 실제로 나올 가능성은 낮지만, 엘리먼트 텍스트 안에 우연히 들어가면 HTML 파서가
        // 스크립트를 조기 종료시켜 페이지가 깨진다 — 방어적으로 이스케이프한다.
        val scene = """{"elements": [{"type": "text", "text": "</script><script>alert(1)</script>"}]}"""

        val html = buildExcalidrawViewerHtml(scene)

        assertFalse(html.contains("</script><script>alert(1)"))
        assertTrue(html.contains("<\\/script><script>alert(1)"))
    }

    @Test
    fun `buildExcalidrawViewerHtml catches module load and unhandled errors, not just the try block`() {
        // 실측 버그(2026-08-30): 정적 import가 실패하면 try/catch 밖에서 일어나서 하얀
        // 화면만 뜨고 에러가 안 보였다 — 동적 import() + 전역 에러 핸들러로 고쳤다.
        val html = buildExcalidrawViewerHtml("""{"elements": []}""")

        assertTrue(html.contains("addEventListener('error'"))
        assertTrue(html.contains("addEventListener('unhandledrejection'"))
    }

    @Test
    fun `resolveWebViewHtml wraps excalidraw scenes through the exportToSvg viewer`() {
        val scene = """{"elements": []}"""

        val html = resolveWebViewHtml("diagram.excalidraw", "application/json", scene)

        assertTrue(html != null && html.contains("await import("))
    }

    @Test
    fun `resolveWebViewHtml passes html mime content through unchanged`() {
        val page = "<!DOCTYPE html><html><body>hi</body></html>"

        val html = resolveWebViewHtml("diagram.html", "text/html", page)

        assertEquals(page, html)
    }

    @Test
    fun `resolveWebViewHtml returns null for plain documents`() {
        assertEquals(null, resolveWebViewHtml("notes.md", "text/markdown", "# hi"))
        assertEquals(null, resolveWebViewHtml("data.json", "application/json", "{}"))
    }

    @Test
    fun `stripViewportMetaForInlinePreview removes the viewport meta tag`() {
        // 실측 버그(2026-08-30): "까만 빈 박스" — architecture-diagram 산출물이 데스크톱
        // 폭 기준 절대좌표라, viewport 메타 태그가 있으면 WebView의 축소-전체보기 모드가
        // 안 먹혀서 인라인 미리보기엔 배경색만 보였다.
        val html = """<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"></head><body>hi</body></html>"""

        val stripped = stripViewportMetaForInlinePreview(html)

        assertFalse(stripped.contains("viewport"))
        assertTrue(stripped.contains("<meta charset=\"utf-8\">"))
        assertTrue(stripped.contains("<body>hi</body>"))
    }

    @Test
    fun `stripViewportMetaForInlinePreview is a no-op when there is no viewport tag`() {
        val html = "<html><body>hi</body></html>"

        assertEquals(html, stripViewportMetaForInlinePreview(html))
    }
}
