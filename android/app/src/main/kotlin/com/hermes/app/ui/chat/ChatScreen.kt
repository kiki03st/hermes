package com.hermes.app.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hermes.app.FileUploadClient
import com.hermes.app.HermesRuntime
import com.hermes.app.UploadOutcome
import com.hermes.app.WakeWordService
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 메인 챗봇 화면 — 입력은 전부 `/v1/runs`(승인 게이트 포함) 하나로만 나간다(계획 결정 #1).
 * 옛 `RunsSection.kt`가 하던 일이 [ChatConversationState]/[ChatReducer]/[ChatMessageList]로
 * 흡수됐다.
 *
 * [state]는 [com.hermes.app.ui.HermesApp]이 소유·전달한다(여기서 안 만듦) — 직접 타이핑
 * 경로와 웨이크워드 핸즈프리 경로가 같은 인스턴스를 공유해야 하기 때문(웨이크워드 계획 §1).
 *
 * 입력창의 마이크 버튼은 "제우스" 웨이크워드(항상 듣기)와 완전히 독립적이다 — 앱이 이미
 * 열려있는 상태에서 버튼을 눌러 직접 트리거하는 것이므로 BAL 제한이 걸릴 이유가 없어
 * `WakeWordService`의 헤드리스 방식이 아니라 `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`
 * 액티비티를 그대로 띄운다(Wear 앱과 같은 방식). 인식되면 웨이크워드 경로와 동일하게
 * 확인 없이 바로 전송한다(계획 확인).
 *
 * 첨부 흐름은 클로드/챗GPT식(선택 → 대기 칩 → 캡션 같이 입력 → 전송 눌러야 나감)이다 —
 * 고르는 즉시 파일별로 백그라운드 업로드 시작, 업로드 다 끝나야(그리고 실패한 게 하나도
 * 없어야) 전송 버튼이 눌린다. 실패한 칩은 지워야(X) 다시 보낼 수 있다 — "보낸 줄 알았는데
 * 그 파일만 안 들어갔다" 사고를 막기 위함.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(state: ChatConversationState, onOpenSettings: () -> Unit) {
    var input by remember { mutableStateOf("") }
    val pendingApproval = (state.messages.lastOrNull() as? ChatMessage.AssistantTurn)?.approval
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingAttachments by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }

    fun updateAttachment(id: String, transform: (PendingAttachment) -> PendingAttachment) {
        // id가 이미 리스트에서 지워졌으면(사용자가 X 눌러 제거) 그냥 조용히 무시한다 —
        // 업로드가 뒤늦게 끝나도 이미 관심 밖인 첨부다.
        pendingAttachments = pendingAttachments.map { if (it.id == id) transform(it) else it }
    }

    val attachLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            val id = UUID.randomUUID().toString()
            pendingAttachments = pendingAttachments +
                PendingAttachment(id = id, name = "파일", mimeType = "application/octet-stream")
            scope.launch {
                val selected = withContext(Dispatchers.IO) {
                    runCatching { readSelectedFile(context, uri) }.getOrNull()
                }
                if (selected == null) {
                    val message = "파일을 읽을 수 없습니다"
                    updateAttachment(id) { it.copy(status = AttachmentStatus.Failed(message)) }
                    state.reportSystemNotice("첨부 실패: $message")
                    return@launch
                }
                val thumbnail = if (selected.mimeType.startsWith("image/")) {
                    withContext(Dispatchers.IO) { runCatching { decodeThumbnail(selected.bytes) }.getOrNull() }
                } else {
                    null
                }
                updateAttachment(id) { it.copy(name = selected.name, mimeType = selected.mimeType, thumbnail = thumbnail) }

                val outcome = withContext(Dispatchers.IO) {
                    try {
                        FileUploadClient(
                            uploadServerUrl = { HermesRuntime.currentSettings.uploadServerUrl },
                            apiKey = { HermesRuntime.currentSettings.apiKey },
                        ).upload(selected.name, selected.mimeType, selected.bytes)
                    } catch (e: Exception) {
                        UploadOutcome.Failure(0, "업로드 오류: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
                when (outcome) {
                    is UploadOutcome.Success ->
                        updateAttachment(id) { it.copy(status = AttachmentStatus.Uploaded(outcome.path, outcome.note)) }
                    is UploadOutcome.Failure -> {
                        val message = "${outcome.statusCode}: ${outcome.message}"
                        updateAttachment(id) { it.copy(status = AttachmentStatus.Failed(message)) }
                        state.reportSystemNotice("파일 업로드 실패 ($message)")
                    }
                }
            }
        }
    }

    // 웨이크워드가 항상 듣기 중이면 그쪽 AudioRecord랑 이 버튼의 STT가 마이크를 동시에
    // 잡으려고 해서 충돌한다(마이크는 단일 클라이언트 제약) — 켜져있을 때만, 시작 전엔
    // 일시정지, 끝나면(성공/실패/취소 무관) 재개 신호를 보낸다. 꺼져있으면 굳이 서비스를
    // 새로 띄우지 않게 아예 호출을 안 한다.
    fun pauseWakeWordIfRunning() {
        if (HermesRuntime.currentSettings.wakeWordEnabled) {
            context.startService(
                Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_PAUSE_FOR_EXTERNAL_STT),
            )
        }
    }

    fun resumeWakeWordIfRunning() {
        if (HermesRuntime.currentSettings.wakeWordEnabled) {
            context.startService(
                Intent(context, WakeWordService::class.java).setAction(WakeWordService.ACTION_RESUME_AFTER_EXTERNAL_STT),
            )
        }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        resumeWakeWordIfRunning()
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) state.submit(text)
    }

    fun launchSpeechRecognition() {
        pauseWakeWordIfRunning()
        speechLauncher.launch(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN)
            },
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) launchSpeechRecognition() }

    fun onMicClick() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchSpeechRecognition()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val hasBlockingAttachments = pendingAttachments.any { it.status !is AttachmentStatus.Uploaded }
    val canSend = pendingApproval == null && !state.isRunning && !hasBlockingAttachments &&
        (input.isNotBlank() || pendingAttachments.isNotEmpty())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hermes") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "설정")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            ChatMessageList(
                messages = state.messages,
                onApprovalChoice = { turnId, choice -> state.resolveApproval(turnId, choice) },
                modifier = Modifier.weight(1f),
                revision = state.revision,
            )
            if (pendingAttachments.isNotEmpty()) {
                AttachmentTray(
                    attachments = pendingAttachments,
                    onRemove = { id -> pendingAttachments = pendingAttachments.filterNot { it.id == id } },
                )
            }
            InputBar(
                value = input,
                onValueChange = { input = it },
                enabled = pendingApproval == null,
                sending = state.isRunning,
                sendEnabled = canSend,
                onMicClick = ::onMicClick,
                onAttachClick = { attachLauncher.launch(arrayOf("*/*")) },
                onSend = {
                    val attachmentsText = pendingAttachments.mapNotNull { attachment ->
                        (attachment.status as? AttachmentStatus.Uploaded)?.let {
                            "첨부 파일 경로: ${it.path}\n${it.note}"
                        }
                    }.joinToString("\n\n")
                    val finalText = listOf(input, attachmentsText).filter { it.isNotBlank() }.joinToString("\n\n")
                    input = ""
                    pendingAttachments = emptyList()
                    state.submit(finalText)
                },
            )
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    sending: Boolean,
    sendEnabled: Boolean,
    onMicClick: () -> Unit,
    onAttachClick: () -> Unit,
    onSend: () -> Unit,
) {
    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onAttachClick, enabled = enabled && !sending) {
                // 클립 이모지 — 마이크 버튼(🎙)과 같은 이유로 material-icons-extended 없이 표시
                Text("📎")
            }
            IconButton(onClick = onMicClick, enabled = enabled && !sending) {
                // 마이크 이모지 — material-icons-extended 의존성 없이 표시 (Wear 앱과 같은 방식)
                Text("🎙")
            }
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                placeholder = { Text(if (enabled) "메시지 보내기" else "승인 대기 중") },
                modifier = Modifier.weight(1f),
            )
            Button(enabled = sendEnabled, onClick = onSend) {
                Text("전송")
            }
        }
    }
}

private data class PendingAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val thumbnail: ImageBitmap? = null,
    val status: AttachmentStatus = AttachmentStatus.Uploading,
)

private sealed interface AttachmentStatus {
    data object Uploading : AttachmentStatus
    data class Uploaded(val path: String, val note: String) : AttachmentStatus
    data class Failed(val message: String) : AttachmentStatus
}

@Composable
private fun AttachmentTray(attachments: List<PendingAttachment>, onRemove: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            AttachmentChip(attachment = attachment, onRemove = { onRemove(attachment.id) })
        }
    }
}

@Composable
private fun AttachmentChip(attachment: PendingAttachment, onRemove: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val thumbnail = attachment.thumbnail
            if (thumbnail != null) {
                Image(bitmap = thumbnail, contentDescription = attachment.name, modifier = Modifier.size(32.dp))
            } else {
                Text("📄")
            }
            Text(text = attachment.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            when (attachment.status) {
                is AttachmentStatus.Uploading ->
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                is AttachmentStatus.Failed ->
                    Text("⚠", color = MaterialTheme.colorScheme.error)
                is AttachmentStatus.Uploaded -> Unit
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
                Text("✕", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private data class SelectedFile(val name: String, val mimeType: String, val bytes: ByteArray)

/** [android.provider.OpenableColumns.DISPLAY_NAME]으로 원본 파일명을 얻는다 —
 * `content://` URI엔 실제 경로가 없어 이 방법이 표준이다. */
private fun readSelectedFile(context: Context, uri: Uri): SelectedFile? {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri) ?: "application/octet-stream"
    var name = "file"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            cursor.getString(nameIndex)?.let { name = it }
        }
    }
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return SelectedFile(name, mimeType, bytes)
}

/** 첨부 칩용 작은 미리보기 — 원본 그대로 디코드하면 큰 사진에서 메모리를 낭비하므로
 * [maxDimensionPx] 안에 들어오도록 `inSampleSize`로 미리 축소해서 디코드한다. */
private fun decodeThumbnail(bytes: ByteArray, maxDimensionPx: Int = 128): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimensionPx || bounds.outHeight / sampleSize > maxDimensionPx) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    return bitmap.asImageBitmap()
}
