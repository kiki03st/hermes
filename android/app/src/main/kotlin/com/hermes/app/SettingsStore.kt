package com.hermes.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "hermes_settings")

data class HermesSettings(
    val serverUrl: String = "",
    val apiKey: String = "",
    /** 기기 단위로 한 번 생성해 절대 안 바뀌는 장기 기억 스코프(X-Hermes-Session-Key).
     * 대화창(session_id)을 몇 번을 리셋해도 이 값은 그대로라 Hermes의 memory 도구가
     * 쌓아온 내용(이름, 선호도 등)은 계속 이어진다. */
    val longTermMemoryKey: String = "",
)

/** 서버 URL/API 키/장기 기억 키. 워치 릴레이 서비스와 설정 화면이 같은 저장소를 공유한다. */
class SettingsStore(private val context: Context) {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val apiKeyKey = stringPreferencesKey("api_key")
    private val longTermMemoryKeyKey = stringPreferencesKey("long_term_memory_key")

    /** 저장된 값이 없으면 빌드 시점 기본값(local.properties, 개발용)으로 채운다 —
     * 최초 실행 시 설정 화면을 안 건드려도 바로 연결 테스트가 가능하게 하기 위함. */
    val settingsFlow: Flow<HermesSettings> = context.dataStore.data.map { prefs ->
        HermesSettings(
            serverUrl = prefs[serverUrlKey] ?: BuildConfig.DEFAULT_SERVER_URL,
            apiKey = prefs[apiKeyKey] ?: BuildConfig.DEFAULT_API_KEY,
            longTermMemoryKey = prefs[longTermMemoryKeyKey] ?: "",
        )
    }

    suspend fun snapshot(): HermesSettings = settingsFlow.first()

    suspend fun update(serverUrl: String, apiKey: String) {
        context.dataStore.edit { prefs ->
            prefs[serverUrlKey] = serverUrl
            prefs[apiKeyKey] = apiKey
        }
    }

    /** 저장된 게 없으면 새로 하나 만들어서 영구 저장 — 최초 1회만 생성되고
     * 이후로는 앱을 지우기 전까지 절대 안 바뀐다. */
    suspend fun getOrCreateLongTermMemoryKey(): String {
        val existing = snapshot().longTermMemoryKey
        if (existing.isNotBlank()) return existing

        val generated = UUID.randomUUID().toString()
        context.dataStore.edit { prefs -> prefs[longTermMemoryKeyKey] = generated }
        return generated
    }
}
