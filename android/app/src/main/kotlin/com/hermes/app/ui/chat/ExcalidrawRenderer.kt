package com.hermes.app.ui.chat

/** [filename]이 excalidraw 스킬 산출물(표준 Excalidraw scene JSON)인지 — MIME만으론
 * 구분 못 한다(둘 다 `application/json`이라 순수 `.json` 파일과 겹침), 그래서 확장자로
 * 판단한다. */
fun isExcalidrawFile(filename: String): Boolean = filename.endsWith(".excalidraw", ignoreCase = true)

/**
 * [sceneJson](Excalidraw scene 파일 원문 — `{"elements": [...], "appState": {...}, ...}`
 * 형태)을 `@excalidraw/utils`의 `exportToSvg`로 실제 렌더링하는 자체완결형 HTML 페이지를
 * 만든다 — `TextPreviewDialog`가 WebView에 그대로 로드한다(`loadDataWithBaseURL`).
 *
 * `@excalidraw/utils` 버전 선택 — 실측으로 두 번 뒤집힌 결정이다:
 * 1. 처음엔 최신 dist-tag(`0.1.3-test32`)가 프리릴리즈라 안정 버전 `0.1.2`로 고정하려 했다.
 * 2. `0.1.2`의 실제 번들(`dist/excalidraw-utils.min.js`)을 unpkg에서 직접 받아
 *    `exportToSvg` 문자열을 검색해보니 **0개 매치** — 그 버전엔 이 함수 자체가 없다
 *    (다른 용도의 UMD 번들, CSS-in-JS 스타일 로더 쪽이었다). 공식 문서(docs.excalidraw.com)의
 *    `exportToSvg` API는 `0.1.3-test32`(ES 모듈, `package.json`의 `"type": "module"` 확인)
 *    에만 있어서, "-test" 태그가 마음에 안 들어도 이거 아니면 함수 자체가 없다.
 * 그래서 UMD 전역변수 방식이 아니라 `<script type="module">` + `import`로 CDN에서 직접
 * 불러온다. `exportToSvg({elements, appState, files})`는 `Promise<SVGSVGElement>`를
 * 돌려준다(공식 문서 확인) — 비동기 IIFE로 감싸서 최대한 넓은 WebView 버전과 호환되게
 * 한다(top-level await 대신).
 *
 * 실기기 검증(2026-08-30): 처음엔 됐는데, 이후 재현 시 **하얀 화면만 뜨는** 실패가
 * 나왔다 — 정적 `import` 문은 모듈 자체(네트워크/CORS/버전 등)가 실패하면 그 실패가
 * try/catch **밖**에서 일어나서 에러 문구를 못 띄웠다(가장 그럴듯한 원인, 콘솔 로그를
 * 못 봐서 확정은 아니다). 동적 `import()`로 바꿔서 모듈 로드 실패까지 같은 try/catch
 * 안에서 잡히게 했고, `window.onerror`/`unhandledrejection`도 추가로 걸어서 정말
 * 예상 못 한 실패까지도 빈 화면 대신 에러 문구가 뜨게 했다.
 *
 * [sceneJson] 안의 `</script`는 그대로 두면 HTML 파서가 스크립트 태그를 조기 종료시켜
 * 페이지를 깨뜨린다 — 있을 가능성은 낮지만(엘리먼트 텍스트 안에 우연히 들어갈 수 있음)
 * `<\/script`로 이스케이프해서 방어한다.
 */
fun buildExcalidrawViewerHtml(sceneJson: String): String {
    val escapedScene = sceneJson.replace("</script", "<\\/script", ignoreCase = true)
    return """
        |<!DOCTYPE html>
        |<html>
        |<head>
        |<meta charset="utf-8">
        |<meta name="viewport" content="width=device-width, initial-scale=1.0">
        |<style>
        |  body { margin: 0; padding: 0; background: #ffffff; }
        |  #root { width: 100%; }
        |  svg { width: 100%; height: auto; display: block; }
        |  #error { font-family: monospace; padding: 16px; color: #b00020; white-space: pre-wrap; font-size: 13px; }
        |</style>
        |</head>
        |<body>
        |<div id="root"><div id="error"></div></div>
        |<script type="module">
        |  // 오프스크린 캡처(WebViewCapture.kt의 RenderCompleteBridge)가 붙어있으면 알려준다
        |  // — 없으면(전체화면 뷰어처럼 그냥 보여주기만 할 때) 아무 일도 안 한다. 성공/실패
        |  // 양쪽 다 부른다 — 캡처 쪽이 "다 됐다"만 알면 되고, 뭘 캡처하든(그림이든 에러
        |  // 문구든) 그건 캡처 쪽 문제가 아니다.
        |  function notifyRenderComplete() {
        |    if (window.AndroidRenderBridge) window.AndroidRenderBridge.onRenderComplete();
        |  }
        |  function showError(msg) {
        |    document.getElementById('error').textContent = 'Excalidraw 렌더링 실패: ' + msg;
        |    notifyRenderComplete();
        |  }
        |  // 정적 import는 모듈 로드 실패(네트워크/CORS/버전 문제 등)가 try/catch 밖에서
        |  // 일어나 에러를 못 잡는다(실측: 하얀 화면만 뜨는 실패가 있었다, 2026-08-30) —
        |  // 동적 import()로 바꿔서 모듈 로드 실패까지 여기서 잡는다.
        |  window.addEventListener('error', (e) => showError(e.message || String(e)));
        |  window.addEventListener('unhandledrejection', (e) => showError((e.reason && e.reason.message) || String(e.reason)));
        |  const scene = $escapedScene;
        |  (async () => {
        |    try {
        |      const { exportToSvg } = await import("https://unpkg.com/@excalidraw/utils@0.1.3-test32/dist/prod/index.js");
        |      const svg = await exportToSvg({
        |        elements: scene.elements || [],
        |        appState: Object.assign({ viewBackgroundColor: "#ffffff" }, scene.appState || {}),
        |        files: scene.files || null,
        |      });
        |      const root = document.getElementById('root');
        |      root.innerHTML = '';
        |      root.appendChild(svg);
        |      notifyRenderComplete();
        |    } catch (e) {
        |      showError(e && e.message ? e.message : String(e));
        |    }
        |  })();
        |</script>
        |</body>
        |</html>
    """.trimMargin()
}

/** [filename]/[mimeType]가 WebView로 렌더링할 대상이면(실제로 그려지는 시각 콘텐츠 —
 * architecture-diagram의 완성된 HTML, excalidraw의 scene JSON) 로드할 HTML을 만들어
 * 돌려준다. 그 외(md/txt/csv/pdf 등 순수 문서)는 null — 호출부가 `FileChip`으로 떨어뜨린다.
 * `TextPreviewDialog`(전체화면 탭 미리보기)와 `DiagramThumbnail`(버블 인라인 캡처용
 * 오프스크린 WebView) 둘 다 쓴다.
 *
 * 채팅 버블에 라이브 WebView를 바로 인라인으로 띄우는 것도 시도했다(2026-08-30, 클로드
 * Artifacts처럼 탭 없이 바로 보이길 원해서) — 콘솔 로그 접근 없이 세 번 고쳐도 매번
 * 다른 증상(흰 화면 → 까만 박스 → 흰 화면)으로 계속 깨져서 롤백했다. 대신 인라인
 * 자리엔 **한 번 캡처한 정적 비트맵**만 놓는다(`DiagramThumbnail`) — 리스트 스크롤
 * 중에 살아있는 WebView가 하나도 없어서 그 버그 클래스 자체가 안 생긴다. 라이브
 * WebView는 전체화면 탭 미리보기(이미 안정적으로 작동)에만 남긴다. */
fun resolveWebViewHtml(filename: String, mimeType: String, rawText: String): String? = when {
    isExcalidrawFile(filename) -> buildExcalidrawViewerHtml(rawText)
    isRenderableHtml(mimeType) -> rawText
    else -> null
}

// architecture-diagram 등의 템플릿이 넣는 <meta name="viewport" ...> 태그 — 데스크톱
// 브라우저 폭 기준 절대좌표 레이아웃인데 이 태그가 있으면 WebView의
// useWideViewPort/loadWithOverviewMode(좁은 뷰에 맞춰 축소해서 보여주는 설정)가 안
// 먹힌다(페이지 자신의 viewport 선언이 우선시됨). 캡처용 오프스크린 WebView에서만 이
// 태그를 지운다 — 전체화면은 공간이 넉넉해서 안 건드린다.
private val _VIEWPORT_META_TAG = Regex("""<meta[^>]*name=["']viewport["'][^>]*>""", RegexOption.IGNORE_CASE)

/** [DiagramThumbnail]의 오프스크린 캡처 WebView에서만 쓴다 — [html]에서 viewport 메타
 * 태그를 지운다. 실측 버그(2026-08-30, 라이브 인라인 WebView 시절): architecture-diagram
 * 산출물이 데스크톱 폭 기준 절대좌표라, 축소 없이 그대로 캡처하면 실제 다이어그램
 * 내용은 화면 밖으로 벗어나고 배경색만 찍혔다. */
fun stripViewportMetaForCapture(html: String): String = _VIEWPORT_META_TAG.replace(html, "")
