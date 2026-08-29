package com.hermes.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.hermes.app.ui.chat.ChatConversationState

/**
 * 앱 프로세스 전체 수명의 대화 상태 싱글턴. `HermesApp.kt`가 Compose composition마다
 * `remember`로 새로 만들던 것을 여기로 끌어올렸다 — [WakeWordService]가 액티비티/Compose
 * 없이도(앱이 완전히 닫혀있어도) 헤드리스 STT 결과를 바로 [ChatConversationState.submit]에
 * 넣을 수 있어야 하기 때문. [ChatConversationState]/[com.hermes.app.RunsClient]는 원래
 * Android Context 의존성이 전혀 없어서 이 전환에 코드 변경이 필요 없었다 — 소유자만 바뀐다.
 *
 * [currentSettings]는 [com.hermes.app.SettingsStore]의 `Flow`를 실시간으로 받아 캐시해둔
 * 값이다 — Service는 Compose의 `collectAsState`를 못 쓰므로 동기적으로 읽을 수 있는
 * 최신값이 하나 필요하다.
 */
object HermesRuntime {
    lateinit var chatState: ChatConversationState
        private set

    @Volatile
    var currentSettings: HermesSettings = HermesSettings()
        private set

    fun initialize(applicationScope: CoroutineScope, settingsStore: SettingsStore) {
        chatState = ChatConversationState(
            scope = applicationScope,
            sessionKey = { currentSettings.longTermMemoryKey.ifBlank { null } },
            client = {
                RunsClient(
                    transport = UrlConnectionHttpTransport(),
                    sse = UrlConnectionSseTransport(),
                    serverUrl = { currentSettings.serverUrl },
                    apiKey = { currentSettings.apiKey },
                )
            },
            mediaClient = {
                FileUploadClient(
                    uploadServerUrl = { currentSettings.uploadServerUrl },
                    apiKey = { currentSettings.apiKey },
                )
            },
        )
        applicationScope.launch {
            settingsStore.settingsFlow.collect { currentSettings = it }
        }
    }
}
