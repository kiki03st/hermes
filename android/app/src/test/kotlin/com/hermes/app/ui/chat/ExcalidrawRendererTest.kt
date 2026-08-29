package com.hermes.app.ui.chat

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
        assertTrue(html.contains("import { exportToSvg }"))
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
}
