package com.hermes.wear

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import com.google.android.gms.wearable.Wearable
import com.hermes.shared.HermesRequest
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }

        setContent {
            MaterialTheme {
                WearApp()
            }
        }
    }

    private companion object {
        const val REQUEST_NOTIFICATIONS = 1
    }
}

@Composable
private fun WearApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var resultText by remember { mutableStateOf("마이크를 눌러 말해보세요") }
    var sending by remember { mutableStateOf(false) }

    val status by WearResponseBus.status.collectAsState()
    val response by WearResponseBus.response.collectAsState()

    LaunchedEffect(response) {
        if (response != null) sending = false
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (text.isNullOrBlank()) return@rememberLauncherForActivityResult

        resultText = text
        sending = true
        scope.launch {
            val nodeId = PhoneNodeResolver.findPhoneNodeId(context)
            if (nodeId == null) {
                resultText = "폰과 연결되어 있지 않아요"
                sending = false
                return@launch
            }
            val sender = DataLayerSender(Wearable.getMessageClient(context))
            sender.sendRequest(nodeId, HermesRequest(reqId = UUID.randomUUID().toString(), text = text))
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize().padding(8.dp)) {
            item {
                Button(onClick = {
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN)
                    }
                    speechLauncher.launch(intent)
                }) {
                    Text("🎙") // 마이크 이모지 — 별도 아이콘 라이브러리 의존성 없이 표시
                }
            }
            item {
                Text(text = if (sending) (status?.label ?: "처리 중...") else resultText)
            }
            response?.let { r ->
                item { Text(text = r.text) }
            }
        }
    }
}
