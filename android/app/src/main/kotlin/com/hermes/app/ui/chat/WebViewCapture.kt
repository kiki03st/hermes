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

/**
 * [onReady]는 [webView]가 지금까지의 DOM 상태를 **실제로 화면에 그릴 준비가 됐을 때만**
 * 불린다(`WebView.postVisualStateCallback`, API 23+ — 이 프로젝트 minSdk 26이라 항상 있다).
 *
 * 실측 버그(2026-08-30): 전체화면 뷰어(`TextPreviewDialog`, 라이브 WebView)는 정상인데
 * 버블 인라인 캡처만 계속 흰 화면 — 콘텐츠 자체는 정상 생성/렌더링된다는 뜻이라(같은
 * HTML을 같은 방식으로 로드), `onPageFinished`/JS 렌더 완료 브리지 호출 시점과 실제
 * 컴포지터가 그 프레임을 커밋하는 시점 사이의 경쟁이 유력한 원인이다 — "문서/스크립트
 * 로드 끝남"과 "그 결과가 실제로 그려짐"은 다른 시점이다. `postVisualStateCallback`은
 * 그 간극을 없애는 공식 API다.
 */
fun requestCaptureWhenPainted(webView: WebView, onReady: () -> Unit) {
    webView.postVisualStateCallback(
        0L,
        object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                onReady()
            }
        },
    )
    // 콜백은 그 시점까지 예약된 그리기가 실제로 커밋된 뒤 불린다 — 예약된 그리기가
    // 하나도 없으면 콜백 자체가 영영 안 올 수 있어 invalidate()로 한 프레임 그리기를
    // 반드시 예약해둔다.
    webView.invalidate()
}
