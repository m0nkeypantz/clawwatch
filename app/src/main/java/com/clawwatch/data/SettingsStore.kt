package com.clawwatch.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "clawwatch_settings")

class SettingsStore(private val context: Context) {

    companion object {
        private val KEY_GATEWAY_URL = stringPreferencesKey("gateway_url")
        private val KEY_AUTH_TOKEN = stringPreferencesKey("auth_token")
        private val KEY_USE_LOCAL_TTS = booleanPreferencesKey("use_local_tts")
        private val KEY_ELEVENLABS_API_KEY = stringPreferencesKey("elevenlabs_api_key")
        private val KEY_ELEVENLABS_VOICE_ID = stringPreferencesKey("elevenlabs_voice_id")
        private val KEY_VOICE_MUTE = booleanPreferencesKey("voice_mute")
        private val KEY_CONTINUOUS_MODE = booleanPreferencesKey("continuous_mode")
        private val KEY_ALARM_VOLUME = floatPreferencesKey("alarm_volume")
        private val KEY_ASSISTANT_VOLUME = floatPreferencesKey("assistant_volume")
    }

    val gatewayUrl: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_GATEWAY_URL] ?: ""
    }

    val authToken: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTH_TOKEN] ?: ""
    }

    val useLocalTts: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_USE_LOCAL_TTS] ?: true
    }

    val elevenLabsApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_ELEVENLABS_API_KEY] ?: ""
    }

    val elevenLabsVoiceId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_ELEVENLABS_VOICE_ID] ?: ""
    }

    val voiceMute: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_VOICE_MUTE] ?: false
    }

    val continuousMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_CONTINUOUS_MODE] ?: false
    }

    val alarmVolume: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[KEY_ALARM_VOLUME] ?: 1.0f
    }

    val assistantVolume: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[KEY_ASSISTANT_VOLUME] ?: 1.0f
    }

    suspend fun setVoiceMute(muted: Boolean) {
        context.dataStore.edit { it[KEY_VOICE_MUTE] = muted }
    }

    suspend fun setGatewayUrl(url: String) {
        context.dataStore.edit { it[KEY_GATEWAY_URL] = url }
    }

    suspend fun setAuthToken(token: String) {
        context.dataStore.edit { it[KEY_AUTH_TOKEN] = token }
    }

    suspend fun setUseLocalTts(enabled: Boolean) {
        context.dataStore.edit { it[KEY_USE_LOCAL_TTS] = enabled }
    }

    suspend fun setElevenLabsApiKey(apiKey: String) {
        context.dataStore.edit { it[KEY_ELEVENLABS_API_KEY] = apiKey }
    }

    suspend fun setElevenLabsVoiceId(voiceId: String) {
        context.dataStore.edit { it[KEY_ELEVENLABS_VOICE_ID] = voiceId }
    }

    suspend fun setContinuousMode(enabled: Boolean) {
        context.dataStore.edit { it[KEY_CONTINUOUS_MODE] = enabled }
    }

    suspend fun setAlarmVolume(volume: Float) {
        context.dataStore.edit { it[KEY_ALARM_VOLUME] = volume.coerceIn(0f, 1f) }
    }

    suspend fun setAssistantVolume(volume: Float) {
        context.dataStore.edit { it[KEY_ASSISTANT_VOLUME] = volume.coerceIn(0f, 1f) }
    }
}
