package com.hermes.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.hermes.app.ui.HermesApp
import com.hermes.app.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    private var recognitionTrigger by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeAutoRecognizeExtra(intent)
        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HermesApp(
                        settingsStore = SettingsStore(applicationContext),
                        recognitionTrigger = recognitionTrigger,
                    )
                }
            }
        }
    }

    // launchMode="singleTop"이라 앱이 이미 떠 있으면(웨이크워드가 재실행 시도 시) 여기로
    // 온다 — 완전히 새로 켜질 때는 onCreate가 대신 처리한다.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeAutoRecognizeExtra(intent)
    }

    private fun consumeAutoRecognizeExtra(source: Intent) {
        if (source.getBooleanExtra(EXTRA_AUTO_RECOGNIZE, false)) {
            recognitionTrigger++
        }
    }

    companion object {
        /** [WakeWordService]가 웨이크워드 감지 시 이 extra를 달아 이 액티비티를 띄운다. */
        const val EXTRA_AUTO_RECOGNIZE = "com.hermes.app.EXTRA_AUTO_RECOGNIZE"
    }
}
