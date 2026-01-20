package com.example.camera_rtmp.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera_rtmp.data.SettingsRepository
import com.example.camera_rtmp.data.StreamSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StreamViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = SettingsRepository(application)
    
    val settings: StateFlow<StreamSettings> = repository.settingsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = StreamSettings()
        )
    
    private val _rtmpUrl = MutableStateFlow("")
    val rtmpUrl: StateFlow<String> = _rtmpUrl.asStateFlow()
    
    init {
        viewModelScope.launch {
            repository.settingsFlow.collect { settings ->
                _rtmpUrl.value = settings.rtmpUrl
            }
        }
    }
    
    fun updateRtmpUrl(url: String) {
        _rtmpUrl.value = url
    }
    
    fun saveSettings(settings: StreamSettings) {
        viewModelScope.launch {
            repository.saveSettings(settings)
        }
    }
    
    fun saveRtmpUrl() {
        viewModelScope.launch {
            repository.updateRtmpUrl(_rtmpUrl.value)
        }
    }
}
