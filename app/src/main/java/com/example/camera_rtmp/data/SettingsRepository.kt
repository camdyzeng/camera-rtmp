package com.example.camera_rtmp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "stream_settings")

class SettingsRepository(private val context: Context) {
    
    companion object {
        private val SETTINGS_KEY = stringPreferencesKey("stream_settings")
    }
    
    val settingsFlow: Flow<StreamSettings> = context.dataStore.data.map { preferences ->
        val json = preferences[SETTINGS_KEY] ?: ""
        if (json.isEmpty()) {
            StreamSettings()
        } else {
            StreamSettings.fromJson(json)
        }
    }
    
    suspend fun saveSettings(settings: StreamSettings) {
        context.dataStore.edit { preferences ->
            preferences[SETTINGS_KEY] = settings.toJson()
        }
    }
    
    suspend fun updateRtmpUrl(url: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[SETTINGS_KEY]?.let { StreamSettings.fromJson(it) } ?: StreamSettings()
            preferences[SETTINGS_KEY] = current.copy(rtmpUrl = url).toJson()
        }
    }
}
