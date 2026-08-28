package com.hermes.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.core.content.ContextCompat
import com.hermes.app.HermesApiClient
import com.hermes.app.SettingsStore
import com.hermes.app.UrlConnectionHttpTransport
import com.hermes.app.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 서버 URL/API 키/연결 테스트 — 옛 `HermesApp.kt` 맨 위에 항상 보이던 것을 여기로 옮겼다
 * (계획 결정 #2). 로직은 그대로, 위치만 기어 아이콘 뒤 별도 화면으로 이동. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settingsStore: SettingsStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by settingsStore.settingsFlow.collectAsState(initial = null)

    var serverUrlInput by remember { mutableStateOf("") }
    var apiKeyInput by remember { mutableStateOf("") }
    var healthStatus by remember { mutableStateOf<Boolean?>(null) }

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

    // 웨이크워드 마이크/알림 런타임 권한 — 이 리포에 재사용할 헬퍼가 없어 여기 직접 둔다
    // (계획 §3, 설정 화면이 사용자가 명시적으로 액션하는 유일한 곳).
    val wakeWordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val recordOk = grants[Manifest.permission.RECORD_AUDIO] == true
        val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            grants[Manifest.permission.POST_NOTIFICATIONS] == true
        if (recordOk && notifOk) {
            scope.launch { settingsStore.setWakeWordEnabled(true) }
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        }
        // 거부 시: settingsStore를 안 건드리므로 스위치는 자동으로 꺼진 채 유지된다.
    }

    fun enableWakeWord() {
        val recordOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val notifOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (recordOk && notifOk) {
            scope.launch { settingsStore.setWakeWordEnabled(true) }
            ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
        } else {
            val perms = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            wakeWordPermissionLauncher.launch(perms.toTypedArray())
        }
    }

    fun disableWakeWord() {
        scope.launch { settingsStore.setWakeWordEnabled(false) }
        context.stopService(Intent(context, WakeWordService::class.java))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Hermes 서버", style = MaterialTheme.typography.titleMedium)
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

            Text("웨이크워드", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("\"제우스\" 항상 듣기")
                Switch(
                    checked = settings?.wakeWordEnabled ?: false,
                    onCheckedChange = { checked -> if (checked) enableWakeWord() else disableWakeWord() },
                )
            }
            if (settings?.wakeWordEnabled == true) {
                OutlinedButton(onClick = {
                    val powerManager = context.getSystemService(PowerManager::class.java)
                    if (powerManager?.isIgnoringBatteryOptimizations(context.packageName) != true) {
                        context.startActivity(
                            Intent(
                                AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                }) {
                    Text("배터리 최적화 예외 설정")
                }
            }
        }
    }
}
