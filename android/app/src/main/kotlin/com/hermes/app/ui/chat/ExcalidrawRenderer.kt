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
 * 실기기 검증 완료(2026-08-30) — CDN import + exportToSvg 렌더링 정상 작동함. 렌더링
 * 실패 시엔 화면에 에러 문구가 뜨게 만들어뒀다(무한 빈 화면 대신).
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
        |  import { exportToSvg } from "https://unpkg.com/@excalidraw/utils@0.1.3-test32/dist/prod/index.js";
        |  const scene = $escapedScene;
        |  (async () => {
        |    try {
        |      const svg = await exportToSvg({
        |        elements: scene.elements || [],
        |        appState: Object.assign({ viewBackgroundColor: "#ffffff" }, scene.appState || {}),
        |        files: scene.files || null,
        |      });
        |      const root = document.getElementById('root');
        |      root.innerHTML = '';
        |      root.appendChild(svg);
        |    } catch (e) {
        |      document.getElementById('error').textContent = 'Excalidraw 렌더링 실패: ' + (e && e.message ? e.message : e);
        |    }
        |  })();
        |</script>
        |</body>
        |</html>
    """.trimMargin()
}

/** [filename]/[mimeType]가 WebView로 렌더링할 대상이면(실제로 그려지는 시각 콘텐츠 —
 * architecture-diagram의 완성된 HTML, excalidraw의 scene JSON) 로드할 HTML을 만들어
 * 돌려준다. 그 외(md/txt/csv/pdf 등 순수 문서)는 null — 호출부가 `FileChip`으로
 * 떨어뜨린다. `MediaImage`(버블 인라인)와 `TextPreviewDialog`(전체화면) 둘 다 이걸
 * 공유해서 판단 로직이 갈라지지 않게 한다(설계 문서: 2026-08-30, "탭해야만 보이는 게
 * 아니라 이미지처럼 바로 보이면 좋겠다"는 요청 반영). */
fun resolveWebViewHtml(filename: String, mimeType: String, rawText: String): String? = when {
    isExcalidrawFile(filename) -> buildExcalidrawViewerHtml(rawText)
    isRenderableHtml(mimeType) -> rawText
    else -> null
}
