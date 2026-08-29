package com.hermes.app.ui.chat

import android.graphics.BitmapFactory
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ChatMessageList(
    messages: List<ChatMessage>,
    onApprovalChoice: (turnId: String, choice: String) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    revision: Int = messages.size,
) {
    // MessageDelta는 기존 아이템(AssistantTurn)을 갱신하지 새 아이템을 추가하지 않는다 —
    // messages.size만 보고 스크롤하면 스트리밍 중엔 안 따라간다. 호출자가 리듀서 호출마다
    // 증가시키는 revision을 키로 써야 매 델타마다 최신 위치로 스크롤된다.
    LaunchedEffect(revision) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    // LazyColumn 안에 두면 다이얼로그가 리스트 클리핑에 갇히므로 여기(리스트 밖)에서
    // 호이스팅한다 — 클로드/챗GPT식 전체화면 뷰어(설계 문서: 이미지 다운로드/공유 기능).
    // MediaImage가 Loaded 상태일 때만 클릭 가능하게 하므로 [ChatMedia.status]는 항상
    // Loaded임이 보장된다.
    var fullscreenMedia by remember { mutableStateOf<ChatMedia?>(null) }
    // 텍스트 파일(마크다운 등) 미리보기 — 같은 이유로 리스트 밖에서 호이스팅.
    var previewMedia by remember { mutableStateOf<ChatMedia?>(null) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp, horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            when (message) {
                is ChatMessage.User -> UserBubble(message)
                is ChatMessage.AssistantTurn -> AssistantBubble(
                    turn = message,
                    onApprovalChoice = onApprovalChoice,
                    onImageClick = { fullscreenMedia = it },
                    onFileClick = { previewMedia = it },
                )
                is ChatMessage.SystemNotice -> NoticeRow(message)
            }
        }
    }

    fullscreenMedia?.let { media ->
        FullscreenImageViewer(media = media, onDismiss = { fullscreenMedia = null })
    }
    previewMedia?.let { media ->
        TextPreviewDialog(media = media, onDismiss = { previewMedia = null })
    }
}

@Composable
private fun UserBubble(message: ChatMessage.User) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun AssistantBubble(
    turn: ChatMessage.AssistantTurn,
    onApprovalChoice: (String, String) -> Unit,
    onImageClick: (ChatMedia) -> Unit,
    onFileClick: (ChatMedia) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Card(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                turn.reasoning?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = "생각 중: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (turn.toolActivity.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        turn.toolActivity.forEach { activity -> ToolActivityRow(activity) }
                    }
                }

                if (turn.textSoFar.isNotBlank()) {
                    Text(
                        text = turn.textSoFar,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                } else if (turn.isStreaming && turn.toolActivity.isEmpty() && turn.reasoning == null) {
                    TypingIndicator()
                }

                turn.media.forEach { media -> MediaImage(media, onImageClick, onFileClick) }

                turn.error?.let {
                    Text(
                        text = "오류: $it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                turn.approval?.let { approval ->
                    ApprovalCard(approval, onChoice = { choice -> onApprovalChoice(turn.id, choice) })
                }
            }
        }
    }
}

/** 원본 `MEDIA:` 텍스트 줄은 [ChatReducer]가 이미 떼어냈다(승인된 방향 — 경로 문자열은
 * 사용자에게 무의미하므로 안 보여준다) — 여기선 이 자리에 이미지/로딩/에러만 그린다. */
@Composable
private fun MediaImage(media: ChatMedia, onImageClick: (ChatMedia) -> Unit, onFileClick: (ChatMedia) -> Unit) {
    when (val status = media.status) {
        is MediaStatus.Loading ->
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)

        is MediaStatus.Loaded -> {
            val bitmap = remember(media.id) {
                runCatching { BitmapFactory.decodeByteArray(status.bytes, 0, status.bytes.size)?.asImageBitmap() }
                    .getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = media.filename,
                    modifier = Modifier.widthIn(max = 280.dp).clickable { onImageClick(media) },
                )
            } else {
                // 비트맵 디코드 실패 = 이미지가 아닌 파일(마크다운, HTML 다이어그램, excalidraw,
                // PDF 등) — 다운로드 자체는 이미 성공해서 바이트가 메모리에 있다(실측 확인,
                // 2026-08-29: 예전엔 여기서 "이미지를 표시할 수 없습니다"만 띄우고 바이트를
                // 그냥 버렸다).
                //
                // HTML/excalidraw를 버블에 바로 라이브 인라인 WebView로 렌더링해봤는데
                // (2026-08-30) 콘솔 로그 접근이 없는 상태로 세 번 고쳐도 매번 다른 증상
                // (흰 화면→까만 박스→흰 화면)으로 계속 깨져서 롤백했다. 대신 렌더링
                // 가능한 콘텐츠면 [DiagramThumbnail](오프스크린 캡처 → 정적 비트맵)로,
                // 그 외 순수 문서는 [FileChip]으로 그린다.
                val mimeType = remember(media.filename) { guessMimeType(media.filename) }
                val webHtml = remember(media.id) {
                    runCatching { status.bytes.toString(Charsets.UTF_8) }.getOrNull()
                        ?.let { resolveWebViewHtml(media.filename, mimeType, it) }
                }
                if (webHtml != null) {
                    DiagramThumbnail(media = media, html = webHtml, bytes = status.bytes, onClick = onFileClick)
                } else {
                    FileChip(media = media, bytes = status.bytes, onClick = onFileClick)
                }
            }
        }

        is MediaStatus.Failed ->
            Text(
                text = "이미지를 불러올 수 없습니다: ${status.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
    }
}

/**
 * 채팅 버블 인라인 자리 — 라이브 WebView를 절대 안 띄운다(롤백 사유는 [resolveWebViewHtml]
 * 문서 참고). 대신 화면 밖 투명 WebView에서 **한 번만** 렌더링한 뒤 [captureWebViewBitmap]로
 * 정적 비트맵을 뜨고, 그 뒤부턴 실제 comfyui 이미지와 완전히 같은 `Image`/`.clickable`
 * 경로로 보여준다 — 리스트가 스크롤되는 동안 스크립트가 살아있는 WebView가 하나도
 * 없어서 예전 버그 클래스(흰 화면/까만 박스) 자체가 안 생긴다.
 *
 * 렌더링 완료 신호는 콘텐츠 종류마다 다르다:
 * - architecture-diagram의 완성된 HTML(정적) → `WebViewClient.onPageFinished`로 충분.
 *   데스크톱 폭 절대좌표 레이아웃이라 [stripViewportMetaForCapture] +
 *   `useWideViewPort`/`loadWithOverviewMode`로 좁은 캡처 프레임에 맞춰 축소한다(실측
 *   버그, 2026-08-30: 안 하면 배경색만 찍힘 — "까만 빈 박스").
 * - excalidraw(비동기: CDN에서 `exportToSvg` 로드 후 그림) → 문서 로드 완료 시점엔 아직
 *   빈 화면이라 [RenderCompleteBridge]를 붙인다(`buildExcalidrawViewerHtml`이 성공/실패
 *   양쪽 다 `AndroidRenderBridge.onRenderComplete()`를 부르도록 되어있다).
 *
 * 캡처가 실패하거나 8초 안에 안 끝나면(타임아웃 — 두 신호 다 영원히 안 올 가능성에
 * 대비) [FileChip]으로 떨어진다 — 최소한 다운로드/공유는 계속 할 수 있다.
 */
@Composable
private fun DiagramThumbnail(media: ChatMedia, html: String, bytes: ByteArray, onClick: (ChatMedia) -> Unit) {
    var captured by remember(media.id) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(media.id) { mutableStateOf(false) }
    val isAsync = remember(media.filename) { isExcalidrawFile(media.filename) }

    when {
        captured != null -> Image(
            bitmap = captured!!,
            contentDescription = media.filename,
            modifier = Modifier.widthIn(max = 280.dp).clickable { onClick(media) },
        )

        failed -> FileChip(media = media, bytes = bytes, onClick = onClick)

        else -> {
            Box(modifier = Modifier.width(280.dp).height(220.dp)) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), strokeWidth = 2.dp)
                AndroidView(
                    // alpha(0f) — 크기는 실제로 잡혀야 캡처가 되니(0크기 뷰는
                    // captureWebViewBitmap이 바로 null 반환) 안 보이게만 한다(0 크기
                    // 아님). matchParentSize()는 BoxScope 멤버라 여기(Box 바로 안)에서만
                    // 암시적으로 풀린다 — 별도 import 넣으면 Unresolved reference(실측
                    // 확인, 2026-08-30).
                    modifier = Modifier.matchParentSize().alpha(0f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            if (isAsync) {
                                addJavascriptInterface(
                                    RenderCompleteBridge {
                                        val bmp = captureWebViewBitmap(this)
                                        if (bmp != null) captured = bmp else failed = true
                                    },
                                    "AndroidRenderBridge",
                                )
                            } else {
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        val bmp = view?.let { captureWebViewBitmap(it) }
                                        if (bmp != null) captured = bmp else failed = true
                                    }
                                }
                            }
                        }
                    },
                    update = { webView ->
                        val toLoad = if (isAsync) html else stripViewportMetaForCapture(html)
                        webView.loadDataWithBaseURL(null, toLoad, "text/html", "utf-8", null)
                    },
                )
            }
            LaunchedEffect(media.id) {
                delay(8000)
                if (captured == null) failed = true
            }
        }
    }
}

/** 이미지로 디코드 안 되는 생성 파일(마크다운, PDF 등) — 텍스트 계열([isPreviewableText])
 * 이면 탭해서 원문 미리보기([onClick]), 저장/공유 버튼은 항상 있음. [bytes]는 이미
 * 다운로드 완료된 상태라 버튼 누르면 바로 저장/공유되고 재다운로드는 안 한다. */
@Composable
private fun FileChip(media: ChatMedia, bytes: ByteArray, onClick: (ChatMedia) -> Unit) {
    val filename = media.filename
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mimeType = remember(filename) { guessMimeType(filename) }
    val previewable = remember(mimeType) { isPreviewableText(mimeType) }

    Row(
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
            )
            .let { if (previewable) it.clickable { onClick(media) } else it }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("📄")
        Text(
            text = filename,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            modifier = Modifier.widthIn(max = 160.dp),
        )
        IconButton(onClick = {
            scope.launch {
                val saved = withContext(Dispatchers.IO) { saveFileToDownloads(context, filename, bytes, mimeType) }
                val message = if (saved) "다운로드 폴더에 저장됨" else "저장 실패"
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }) {
            Text("⬇")
        }
        IconButton(onClick = {
            scope.launch { withContext(Dispatchers.IO) { shareFile(context, filename, bytes, mimeType) } }
        }) {
            Text("📤")
        }
    }
}

/**
 * 클로드/챗GPT식 전체화면 이미지 뷰어(설계 문서: 이미지 뷰어 다운로드/공유 기능,
 * 2026-08-29). [Dialog]를 `usesPlatformDefaultWidth = false`로 써야 진짜 풀스크린이
 * 된다(기본값은 다이얼로그를 화면보다 좁게 강제함). [media.status]는 호출부
 * ([ChatMessageList])가 Loaded일 때만 이 컴포저블을 띄우므로 항상 [MediaStatus.Loaded]다.
 */
@Composable
private fun FullscreenImageViewer(media: ChatMedia, onDismiss: () -> Unit) {
    val loaded = media.status as? MediaStatus.Loaded ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scale by remember(media.id) { mutableStateOf(1f) }
    var offset by remember(media.id) { mutableStateOf(Offset.Zero) }

    val bitmap = remember(media.id) {
        runCatching { BitmapFactory.decodeByteArray(loaded.bytes, 0, loaded.bytes.size)?.asImageBitmap() }
            .getOrNull()
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color.Black)
                .clickable(onClick = onDismiss),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = media.filename,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(media.id) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = clampZoom(scale * zoom)
                                offset = if (scale <= 1f) Offset.Zero else offset + pan
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        ),
                )
            }

            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .background(
                        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.medium,
                    ),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onDismiss) {
                    Text("✕", color = androidx.compose.ui.graphics.Color.White)
                }
                IconButton(onClick = {
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            saveImageToGallery(context, media.filename, loaded.bytes)
                        }
                        val message = if (saved) "갤러리에 저장됨" else "저장 실패"
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("⬇", color = androidx.compose.ui.graphics.Color.White)
                }
                IconButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { shareFile(context, media.filename, loaded.bytes, "image/png") }
                    }
                }) {
                    Text("📤", color = androidx.compose.ui.graphics.Color.White)
                }
            }
        }
    }
}

/**
 * `FileChip` 탭 시 뜨는 미리보기. `text/html`(architecture-diagram 등 다이어그램
 * 스킬 산출물)은 클로드 Artifacts처럼 소스코드가 아니라 [WebView]로 실제 렌더링해서
 * 보여준다 — 그 외 텍스트(md/txt 등)는 마크다운 서식 렌더링 없이(YAGNI) UTF-8 원문
 * 그대로 스크롤 가능하게 보여준다. [media.status]는 호출부([ChatMessageList])가
 * Loaded일 때만 이 컴포저블을 띄우므로 항상 [MediaStatus.Loaded]다.
 */
@Composable
private fun TextPreviewDialog(media: ChatMedia, onDismiss: () -> Unit) {
    val loaded = media.status as? MediaStatus.Loaded ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mimeType = remember(media.filename) { guessMimeType(media.filename) }
    val text = remember(media.id) {
        runCatching { loaded.bytes.toString(Charsets.UTF_8) }.getOrDefault("(디코드 실패)")
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                IconButton(onClick = onDismiss) { Text("✕") }
                Text(
                    text = media.filename,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(top = 12.dp),
                )
                IconButton(onClick = {
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            saveFileToDownloads(context, media.filename, loaded.bytes, mimeType)
                        }
                        val message = if (saved) "다운로드 폴더에 저장됨" else "저장 실패"
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }) { Text("⬇") }
                IconButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { shareFile(context, media.filename, loaded.bytes, mimeType) }
                    }
                }) { Text("📤") }
            }
            // MediaImage(버블 인라인)와 판단 로직을 공유한다(resolveWebViewHtml) — excalidraw는
            // 원문이 렌더링 가능한 HTML이 아니라 scene JSON이라 exportToSvg를 호출하는 뷰어
            // 페이지로 감싸야 한다(ExcalidrawRenderer.kt, 실기기 검증 완료 2026-08-30).
            val htmlToLoad = remember(media.id) { resolveWebViewHtml(media.filename, mimeType, text) }
            if (htmlToLoad != null) {
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        android.webkit.WebView(ctx).apply {
                            // 다이어그램 산출물은 인라인 CSS/JS로 자체완결형이라 스크립트가
                            // 필요할 수 있다(호버 효과, excalidraw의 exportToSvg 호출 등) —
                            // 로컬에서 만든 신뢰 콘텐츠라 XSS 우려 없음(우리 자신의 생성
                            // 파이프라인 산출물).
                            settings.javaScriptEnabled = true
                        }
                    },
                    update = { webView -> webView.loadDataWithBaseURL(null, htmlToLoad, "text/html", "utf-8", null) },
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(androidx.compose.foundation.rememberScrollState())
                        .padding(12.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolActivityRow(activity: ToolActivity) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (activity.state) {
            ToolState.RUNNING -> CircularProgressIndicator(
                modifier = Modifier.padding(2.dp),
                strokeWidth = 2.dp,
            )
            ToolState.DONE -> StatusDot(color = MaterialTheme.colorScheme.primary)
            ToolState.ERROR -> StatusDot(color = MaterialTheme.colorScheme.error)
        }
        Text(
            text = activity.tool,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatusDot(color: androidx.compose.ui.graphics.Color) {
    Box(modifier = Modifier.padding(2.dp)) {
        Box(
            modifier = Modifier
                .padding(2.dp)
                .background(color = color, shape = androidx.compose.foundation.shape.CircleShape)
                .widthIn(min = 8.dp),
        )
    }
}

@Composable
private fun TypingIndicator() {
    Text(
        text = "...",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 승인 프롬프트를 모달이 아니라 대화 흐름 안 카드로 렌더한다(계획 §2) — "무시 불가" 보장은
 * 입력창을 막는 쪽(`ChatScreen`)에서 담당하고, 여기선 [PendingApproval.choices]를 그대로
 * 버튼으로 그린다(하드코딩 금지, `RunsSection.kt`의 옛 `ApprovalDialog`와 같은 원칙).
 */
@Composable
private fun ApprovalCard(approval: PendingApproval, onChoice: (String) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("승인이 필요합니다", style = MaterialTheme.typography.titleSmall)
            approval.command?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            approval.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                approval.choices.forEach { choice ->
                    Button(onClick = { onChoice(choice) }) {
                        Text(approvalChoiceLabel(choice))
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeRow(message: ChatMessage.SystemNotice) {
    Text(
        text = message.text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun approvalChoiceLabel(choice: String): String = when (choice) {
    "once" -> "이번만 승인"
    "session" -> "이 대화 동안 계속 승인"
    "always" -> "항상 승인"
    "deny" -> "거부"
    else -> choice
}
