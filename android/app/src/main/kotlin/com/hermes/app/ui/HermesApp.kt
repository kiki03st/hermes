package com.hermes.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.hermes.app.HermesRuntime
import com.hermes.app.SettingsStore
import com.hermes.app.ui.chat.ChatScreen
import com.hermes.app.ui.settings.SettingsScreen

private enum class Screen { Chat, Settings }

/**
 * 두 화면(채팅/설정) 전환만 담당한다 — `navigation-compose`는 이 규모에 과함. 채팅 입력은
 * 전부 `/v1/runs`(승인 게이트 포함)로 나간다.
 *
 * 대화 상태는 [HermesRuntime.chatState](앱 프로세스 전체 수명 싱글턴)를 그대로 쓴다 —
 * `WakeWordService`가 앱이 닫혀있을 때도 헤드리스 STT 결과를 같은 인스턴스에 바로
 * 제출해야 해서, 예전처럼 이 컴포저블 안에서 `remember`로 만들지 않는다. 웨이크워드
 * 감지 후 음성 인식도 이제 `WakeWordService`가 헤드리스로 전부 처리하므로(오버레이 표시),
 * 이 화면은 액티비티를 여는 것과는 완전히 무관해졌다.
 */
@Composable
fun HermesApp(settingsStore: SettingsStore) {
    var currentScreen by rememberSaveable { mutableStateOf(Screen.Chat) }

    // 기기 영구 장기 기억 키(longTermMemoryKey)가 없으면 최초 1회 생성해 DataStore에 쓴다 —
    // settingsFlow가 그걸 다시 흘려보내 HermesRuntime의 sessionKey로 들어간다.
    LaunchedEffect(Unit) { settingsStore.getOrCreateLongTermMemoryKey() }

    BackHandler(enabled = currentScreen == Screen.Settings) { currentScreen = Screen.Chat }

    when (currentScreen) {
        Screen.Chat -> ChatScreen(
            state = HermesRuntime.chatState,
            onOpenSettings = { currentScreen = Screen.Settings },
        )
        Screen.Settings -> SettingsScreen(settingsStore = settingsStore, onBack = { currentScreen = Screen.Chat })
    }
}
