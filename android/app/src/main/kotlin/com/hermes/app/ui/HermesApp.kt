package com.hermes.app.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.hermes.app.RunsClient
import com.hermes.app.SettingsStore
import com.hermes.app.UrlConnectionHttpTransport
import com.hermes.app.UrlConnectionSseTransport
import com.hermes.app.WakeWordBus
import com.hermes.app.ui.chat.ChatConversationState
import com.hermes.app.ui.chat.ChatScreen
import com.hermes.app.ui.settings.SettingsScreen
import java.util.Locale

private enum class Screen { Chat, Settings }

/**
 * 두 화면(채팅/설정) 전환 + 웨이크워드 STT 핸즈프리 경로를 담당한다. `navigation-compose`는
 * 이 규모에 과함(계획 §3). 채팅 입력은 전부 `/v1/runs`(승인 게이트 포함)로 나간다.
 *
 * [ChatConversationState]를 여기서 소유한다(`ChatScreen.kt`에서 끌어올림) — 직접 타이핑
 * 경로(`ChatScreen`)와 웨이크워드 핸즈프리 경로가 같은 인스턴스를 공유해야 하기 때문
 * (계획 §1). [recognitionTrigger]는 [com.hermes.app.MainActivity]가
 * `WakeWordService`發 인텐트를 받을 때마다 증가시키는 카운터 —
 * `ChatConversationState.revision`과 같은 관례로, 값 자체가 아니라 변화가 신호다.
 */
@Composable
fun HermesApp(settingsStore: SettingsStore, recognitionTrigger: Int = 0) {
    var currentScreen by rememberSaveable { mutableStateOf(Screen.Chat) }
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsState(initial = null)

    // 기기 영구 장기 기억 키(longTermMemoryKey)가 없으면 최초 1회 생성해 DataStore에 쓴다 —
    // settingsFlow가 그걸 다시 흘려보내 sessionKey로 들어간다.
    LaunchedEffect(Unit) { settingsStore.getOrCreateLongTermMemoryKey() }

    BackHandler(enabled = currentScreen == Screen.Settings) { currentScreen = Screen.Chat }

    val chatState = settings?.let { s ->
        remember(s.serverUrl, s.apiKey) {
            ChatConversationState(
                scope = scope,
                sessionKey = { s.longTermMemoryKey.ifBlank { null } },
                client = {
                    RunsClient(
                        transport = UrlConnectionHttpTransport(),
                        sse = UrlConnectionSseTransport(),
                        serverUrl = { s.serverUrl },
                        apiKey = { s.apiKey },
                    )
                },
            )
        }
    }

    // Wear 앱(android/wear/.../MainActivity.kt)과 같은 방식 — ACTION_RECOGNIZE_SPEECH로
    // 시스템 음성인식 팝업을 띄운다(삼성 기기에선 빅스비 음성 서비스가 처리).
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) {
            currentScreen = Screen.Chat
            chatState?.submit(text)
        }
        // 성공/실패/취소 무관하게 반드시 알려준다 — WakeWordService가 이 신호를 못 받으면
        // Porcupine이 영영 안 켜진다.
        WakeWordBus.notifyRecognitionFinished()
    }

    LaunchedEffect(recognitionTrigger) {
        if (recognitionTrigger > 0) {
            speechLauncher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN)
                },
            )
        }
    }

    when (currentScreen) {
        Screen.Chat -> chatState?.let {
            ChatScreen(state = it, onOpenSettings = { currentScreen = Screen.Settings })
        }
        Screen.Settings -> SettingsScreen(settingsStore = settingsStore, onBack = { currentScreen = Screen.Chat })
    }
}
