package com.hermes.app.ui.chat

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * 렌더링이 끝났으면 오프스크린 [WebView]를 비트맵으로 캡처한다 — 크기가 0이면(아직
 * 레이아웃 전) `null`을 돌려준다. Compose가 이 [WebView]를 이미 측정/배치해뒀다는
 * 전제(`AndroidView`로 실제 크기를 준 상태에서 호출) — 직접 measure/layout을 부르지
 * 않는다.
 */
fun captureWebViewBitmap(webView: WebView): ImageBitmap? {
    val width = webView.width
    val height = webView.height
    if (width <= 0 || height <= 0) return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    webView.draw(Canvas(bitmap))
    return bitmap.asImageBitmap()
}

/**
 * excalidraw 뷰어 페이지가 렌더링을 마쳤을 때(성공이든 에러든) 자바스크립트가
 * 불러주는 다리 — `WebView.onPageFinished`는 문서 로드 완료 시점일 뿐, excalidraw는
 * 그 뒤에 CDN에서 `exportToSvg`를 비동기로 불러와 그리기 때문에 그 시점엔 아직 빈
 * 화면이다(오늘 밤 실측 버그의 근본 원인과 같은 지점). `buildExcalidrawViewerHtml`이
 * 성공/실패 양쪽 경로 다 `window.AndroidRenderBridge.onRenderComplete()`를 부르게
 * 만들어뒀다 — 그래서 "진짜 다 됐을 때"만 캡처한다.
 *
 * `@JavascriptInterface` 콜백은 WebView 내부 스레드에서 온다 — Compose 상태를 직접
 * 건드리면 안 되므로 메인 스레드로 넘긴다.
 */
class RenderCompleteBridge(private val onComplete: () -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onRenderComplete() {
        mainHandler.post(onComplete)
    }
}
