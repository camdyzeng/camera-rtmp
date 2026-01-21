package com.example.camera_rtmp.data

import com.google.gson.Gson

/**
 * 推流设置数据类
 */
data class StreamSettings(
    // RTMP 服务器设置
    val rtmpUrl: String = "rtmp://",
    
    // 视频设置 (横屏模式，宽 > 高)
    val videoWidth: Int = 1920,
    val videoHeight: Int = 1080,
    val videoBitrate: Int = 4000, // kbps
    val videoFps: Int = 30,
    val videoCodec: VideoCodec = VideoCodec.H264,
    val iFrameInterval: Int = 2,
    
    // 音频设置
    val audioSampleRate: Int = 44100,
    val audioIsStereo: Boolean = true,
    val audioBitrate: Int = 128, // kbps
    val audioEchoCanceler: Boolean = false,
    val audioNoiseSuppressor: Boolean = false,
    
    // 摄像头设置
    val cameraFacing: CameraFacing = CameraFacing.BACK,
    val autoFocus: Boolean = true,
    
    // 视频旋转设置
    val videoRotation: VideoRotation = VideoRotation.AUTO,
    
    // 滤镜设置
    val filterType: FilterType = FilterType.NONE,
    
    // 其他设置
    val keepScreenOn: Boolean = true,
    
    // Watchdog监控设置
    val watchdogEnabled: Boolean = true,
    val watchdogCheckInterval: Long = 5000L, // 5秒检查一次
    val watchdogBitrateTimeout: Long = 30000L, // 30秒无码率视为异常
    val watchdogMinBitrateThreshold: Long = 1000L, // 最小码率1kbps
    val watchdogMaxZeroBitrateCount: Int = 6, // 最多6次连续零码率
    val watchdogConnectionTimeout: Long = 60000L // 60秒连接超时
) {
    fun toJson(): String = Gson().toJson(this)
    
    companion object {
        fun fromJson(json: String): StreamSettings {
            return try {
                Gson().fromJson(json, StreamSettings::class.java)
            } catch (e: Exception) {
                StreamSettings()
            }
        }
    }
}

enum class VideoCodec(val displayName: String) {
    H264("H.264"),
    H265("H.265 (HEVC)")
}

enum class CameraFacing(val displayName: String) {
    FRONT("前置摄像头"),
    BACK("后置摄像头")
}

enum class FilterType(val displayName: String) {
    NONE("无滤镜"),
    GREY_SCALE("黑白"),
    SEPIA("复古"),
    NEGATIVE("负片"),
    AQUA("水蓝"),
    POSTERIZE("色调分离"),
    CONTRAST("高对比度"),
    SATURATION("高饱和度"),
    BRIGHTNESS("高亮度"),
    SHARP("锐化"),
    TEMPERATURE("暖色调"),
    EDGE_DETECTION("边缘检测"),
    BEAUTY("美颜")
}

/**
 * 视频旋转方向选项
 */
enum class VideoRotation(val degrees: Int, val displayName: String) {
    AUTO(0, "自动检测"),
    ROTATION_0(0, "0° (正常)"),
    ROTATION_90(90, "90° (向左旋转)"),
    ROTATION_180(180, "180° (倒置)"),
    ROTATION_270(270, "270° (向右旋转)")
}

/**
 * 预设分辨率选项 (横屏模式)
 */
enum class VideoResolution(val width: Int, val height: Int, val displayName: String) {
    R_4K(3840, 2160, "4K (3840x2160)"),
    R_1080P(1920, 1080, "1080P (1920x1080)"),
    R_720P(1280, 720, "720P (1280x720)"),
    R_480P(854, 480, "480P (854x480)"),
    R_360P(640, 360, "360P (640x360)")
}

/**
 * 预设码率选项
 */
enum class VideoBitratePreset(val bitrate: Int, val displayName: String) {
    ULTRA_HIGH(8000, "超高 (8 Mbps)"),
    HIGH(4000, "高 (4 Mbps)"),
    MEDIUM(2000, "中 (2 Mbps)"),
    LOW(1000, "低 (1 Mbps)"),
    VERY_LOW(500, "极低 (500 Kbps)")
}

/**
 * 预设帧率选项
 */
enum class VideoFpsPreset(val fps: Int, val displayName: String) {
    FPS_60(60, "60 FPS"),
    FPS_30(30, "30 FPS"),
    FPS_25(25, "25 FPS"),
    FPS_24(24, "24 FPS"),
    FPS_15(15, "15 FPS")
}

/**
 * 音频采样率选项
 */
enum class AudioSampleRatePreset(val sampleRate: Int, val displayName: String) {
    SR_48000(48000, "48 kHz"),
    SR_44100(44100, "44.1 kHz"),
    SR_32000(32000, "32 kHz"),
    SR_22050(22050, "22.05 kHz")
}

/**
 * 音频码率选项
 */
enum class AudioBitratePreset(val bitrate: Int, val displayName: String) {
    HIGH(192, "高 (192 kbps)"),
    MEDIUM(128, "中 (128 kbps)"),
    LOW(64, "低 (64 kbps)")
}
