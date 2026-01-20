package com.example.camera_rtmp.data

/**
 * 推流状态
 */
sealed class StreamState {
    object Idle : StreamState()
    object Preparing : StreamState()
    object Previewing : StreamState()
    object Connecting : StreamState()
    data class Streaming(val bitrate: Long = 0) : StreamState()
    data class Error(val message: String) : StreamState()
    object Reconnecting : StreamState()
}

/**
 * 推流统计信息
 */
data class StreamStats(
    val bitrate: Long = 0,           // 当前码率 bps
    val fps: Float = 0f,             // 当前帧率
    val droppedFrames: Int = 0,      // 丢帧数
    val streamTime: Long = 0,        // 推流时长 ms
    val sentBytes: Long = 0          // 已发送字节数
)

/**
 * 服务通信事件
 */
sealed class StreamEvent {
    object StartPreview : StreamEvent()
    object StopPreview : StreamEvent()
    data class StartStream(val url: String) : StreamEvent()
    object StopStream : StreamEvent()
    object SwitchCamera : StreamEvent()
    data class SetFilter(val filterType: FilterType) : StreamEvent()
    data class UpdateSettings(val settings: StreamSettings) : StreamEvent()
    object ToggleMute : StreamEvent()
    object ToggleFlash : StreamEvent()
    data class SetBitrate(val bitrate: Int) : StreamEvent()
    object RequestKeyframe : StreamEvent()
}
