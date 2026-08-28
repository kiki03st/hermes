package com.hermes.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** [HermesRuntime]을 초기화한다 — `Application.onCreate()`는 어떤 Activity/Service의
 * `onCreate()`보다도 항상 먼저 실행되므로, 이후 [HermesRuntime.chatState]는 항상
 * 초기화된 상태로 보장된다(별도 null 체크 불필요). */
class HermesApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        HermesRuntime.initialize(applicationScope, SettingsStore(this))
    }
}
