package com.hermes.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hermes.app.ChatOutcome
import com.hermes.app.HermesApiClient
import com.hermes.app.SettingsStore
import com.hermes.app.UrlConnectionHttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stage 1 최소 화면: 서버 설정 + 연결 테스트 + 비스트리밍 채팅 한 번.
 * 세션 목록/스트리밍/이미지 뷰어는 Stage 2에서 붙인다. */
@Composable
fun HermesApp(settingsStore: SettingsStore) {
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsState(initial = null)

    var serverUrlInput by remember { mutableStateOf("") }
    var apiKeyInput by remember { mutableStateOf("") }
    var healthStatus by remember { mutableStateOf<Boolean?>(null) }

    var chatInput by remember { mutableStateOf("") }
    var chatOutput by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(settings) {
        settings?.let {
            serverUrlInput = it.serverUrl
            apiKeyInput = it.apiKey
        }
    }

    fun client() = HermesApiClient(
        transport = UrlConnectionHttpTransport(),
        serverUrl = { serverUrlInput },
        apiKey = { apiKeyInput },
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Hermes 설정", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = serverUrlInput,
            onValueChange = { serverUrlInput = it },
            label = { Text("서버 URL (예: http://192.168.0.10:8642)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            label = { Text("API 키") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { settingsStore.update(serverUrlInput, apiKeyInput) } }) {
                Text("저장")
            }
            OutlinedButton(onClick = {
                scope.launch {
                    healthStatus = withContext(Dispatchers.IO) { client().checkHealth() }
                }
            }) {
                Text("연결 테스트")
            }
        }
        when (healthStatus) {
            true -> Text("연결 성공", color = MaterialTheme.colorScheme.primary)
            false -> Text("연결 실패", color = MaterialTheme.colorScheme.error)
            null -> Unit
        }

        HorizontalDivider()

        Text("채팅 (Stage 1: 비스트리밍)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = chatInput,
            onValueChange = { chatInput = it },
            label = { Text("메시지") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !sending && chatInput.isNotBlank(),
            onClick = {
                val text = chatInput
                sending = true
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) { client().sendChat(text) }
                    chatOutput = when (outcome) {
                        is ChatOutcome.Success -> outcome.text
                        is ChatOutcome.Failure -> "에러 (${outcome.statusCode}): ${outcome.message}"
                    }
                    sending = false
                }
            },
        ) {
            Text(if (sending) "전송 중..." else "전송")
        }

        if (chatOutput.isNotBlank()) {
            Text(chatOutput)
        }
    }
}
