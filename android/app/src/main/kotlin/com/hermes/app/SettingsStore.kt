package com.hermes.app

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "hermes_settings")

data class HermesSettings(
    val serverUrl: String = "",
    val apiKey: String = "",
)

/** 서버 URL/API 키. 워치 릴레이 서비스와 설정 화면이 같은 저장소를 공유한다. */
class SettingsStore(private val context: Context) {
    private val serverUrlKey = stringPreferencesKey("server_url")
    private val apiKeyKey = stringPreferencesKey("api_key")

    val settingsFlow: Flow<HermesSettings> = context.dataStore.data.map { prefs ->
        HermesSettings(
            serverUrl = prefs[serverUrlKey] ?: "",
            apiKey = prefs[apiKeyKey] ?: "",
        )
    }

    suspend fun snapshot(): HermesSettings = settingsFlow.first()

    suspend fun update(serverUrl: String, apiKey: String) {
        context.dataStore.edit { prefs ->
            prefs[serverUrlKey] = serverUrl
            prefs[apiKeyKey] = apiKey
        }
    }
}
