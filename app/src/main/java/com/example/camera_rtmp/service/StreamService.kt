package com.example.camera_rtmp.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.camera_rtmp.CameraRtmpApplication
import com.example.camera_rtmp.MainActivity
import com.example.camera_rtmp.R
import com.example.camera_rtmp.data.*
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.video.CameraHelper
import com.pedro.library.generic.GenericCamera2
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RTMP 推流服务 - Context 模式（无预览后台推流）
 * 
 * 使用 GenericCamera2(context, connectChecker) 构造函数
 * 
 * Context 模式特点：
 * - 无需 UI 组件（OpenGlView/SurfaceView）
 * - 适合后台推流场景
 * - 页面切换不影响推流
 * - 需要预先处理分辨率和旋转参数
 * 
 * 分辨率处理说明：
 * RootEncoder 在 Context 模式下存在一个已知问题：
 * - prepareGlView() 会根据 rotation 交换 GL 渲染尺寸
 * - 但 cameraManager 使用的是原始尺寸
 * - 导致尺寸不匹配
 * 
 * 解决方案：
 * - 在调用 prepareVideo 之前，根据设备方向预先交换宽高
 * - 传入 rotation=0，避免库再次交换
 */
class StreamService : Service(), ConnectChecker {
    
    companion object {
        private const val TAG = "StreamService"
        private const val NOTIFICATION_ID = 1001
        
        const val ACTION_START_STREAMING = "com.example.camera_rtmp.START_STREAMING"
        const val ACTION_STOP_STREAMING = "com.example.camera_rtmp.STOP_STREAMING"
        
        fun startService(context: Context) {
            val intent = Intent(context, StreamService::class.java).apply {
                action = ACTION_START_STREAMING
            }
            // Android 8.0 (API 26) 及以上版本需要使用 startForegroundService
            // Android 7.x 直接使用 startService
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stopService(context: Context) {
            val intent = Intent(context, StreamService::class.java).apply {
                action = ACTION_STOP_STREAMING
            }
            context.startService(intent)
        }
    }
    
    private val binder = StreamBinder()
    private var genericCamera2: GenericCamera2? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()
    
    private val _streamStats = MutableStateFlow(StreamStats())
    val streamStats: StateFlow<StreamStats> = _streamStats.asStateFlow()
    
    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()
    
    private val _isFlashOn = MutableStateFlow(false)
    val isFlashOn: StateFlow<Boolean> = _isFlashOn.asStateFlow()
    
    private val _currentCameraFacing = MutableStateFlow(CameraFacing.BACK)
    val currentCameraFacing: StateFlow<CameraFacing> = _currentCameraFacing.asStateFlow()
    
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()
    
    private var currentSettings = StreamSettings()
    private var streamStartTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null
    private var currentRtmpUrl: String = ""
    
    // 存储计算后的视频尺寸（用于重连）
    private var computedVideoWidth: Int = 0
    private var computedVideoHeight: Int = 0
    
    // 指数退避重连相关变量
    private var reconnectAttempts: Int = 0
    private var firstReconnectTime: Long = 0L // 首次重连时间戳
    private val baseReconnectDelay: Long = 1000L // 基础重连延迟1秒（毫秒）
    private val maxReconnectDelay: Long = 60 * 60 * 1000L // 最大延迟1小时（毫秒）
    private val maxReconnectDuration: Long = 30L * 24 * 60 * 60 * 1000L // 最大重连时间30天（毫秒）
    
    inner class StreamBinder : Binder() {
        fun getService(): StreamService = this@StreamService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STREAMING -> {
                startForeground(NOTIFICATION_ID, createNotification("准备就绪"))
                _isServiceRunning.value = true
            }
            ACTION_STOP_STREAMING -> {
                stopStreaming()
                // Android 7.0 (API 24) 及以上版本支持 STOP_FOREGROUND_REMOVE
                // 更低版本使用传统的 stopForeground(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                _isServiceRunning.value = false
            }
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed - performing complete cleanup")
        
        try {
            // 取消所有协程
            serviceScope.cancel()
            
            // 取消重连
            cancelReconnect()
            
            // 完全释放所有资源（更新状态并清空URL）
            releaseAllResources(updateState = true, clearUrl = true)
            
            // 释放 WakeLock
            releaseWakeLock()
            
            // 重置所有状态
            _isServiceRunning.value = false
            _streamStats.value = StreamStats()
            resetReconnectAttempts()
            
            Log.d(TAG, "Service cleanup completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Exception during service cleanup", e)
        }
    }
    
    // ==================== 公共 API ====================
    
    /**
     * 初始化推流设置
     */
    fun initializeStreaming(settings: StreamSettings) {
        this.currentSettings = settings
        _currentCameraFacing.value = settings.cameraFacing
        Log.d(TAG, "Streaming initialized with settings: $settings")
    }
    
    /**
     * 更新设置
     */
    fun updateSettings(settings: StreamSettings) {
        currentSettings = settings
        // 如果正在推流，动态更新码率
        if (_streamState.value is StreamState.Streaming) {
            genericCamera2?.setVideoBitrateOnFly(settings.videoBitrate * 1000)
        }
    }
    
    /**
     * 开始推流（Context 模式 - 无预览后台推流）
     */
    fun startStreaming(url: String): Boolean {
        if (_streamState.value is StreamState.Streaming || 
            _streamState.value is StreamState.Connecting) {
            Log.w(TAG, "Already streaming or connecting")
            return false
        }
        
        currentRtmpUrl = url
        // 开始新的推流会话时重置重连计数器
        resetReconnectAttempts()
        _streamState.value = StreamState.Preparing
        updateNotification("正在准备...")
        
        try {
            acquireWakeLock()
            
            // 释放之前的摄像头实例（确保完全清理，但不清空URL）
            releaseAllResources(updateState = false, clearUrl = false)
            
            // 使用公共方法初始化并开始推流
            val success = initAndStartStream(url, recalculateResolution = true)
            if (!success) {
                _streamState.value = StreamState.Error("推流启动失败")
                releaseWakeLock()
                return false
            }
            
            _streamState.value = StreamState.Connecting
            updateNotification("正在连接...")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting stream", e)
            _streamState.value = StreamState.Error("推流启动失败: ${e.message}")
            releaseWakeLock()
            return false
        }
    }
    
    /**
     * 停止推流
     */
    fun stopStreaming() {
        Log.d(TAG, "User requested stop streaming")
        cancelReconnect()
        
        // 使用统一的资源释放方法，更新状态并清空URL
        releaseAllResources(updateState = true, clearUrl = true)
        
        releaseWakeLock()
        _streamStats.value = StreamStats()
        updateNotification("已停止")
        Log.d(TAG, "Streaming stopped by user")
    }
    
    /**
     * 切换摄像头
     */
    fun switchCamera() {
        genericCamera2?.let { camera ->
            try {
                camera.switchCamera()
                _currentCameraFacing.value = if (_currentCameraFacing.value == CameraFacing.FRONT) {
                    CameraFacing.BACK
                } else {
                    CameraFacing.FRONT
                }
                // 切换摄像头后关闭闪光灯
                _isFlashOn.value = false
                
                // 切换摄像头后更新镜像设置
                // 前置摄像头需要水平翻转，后置摄像头不需要
                val isFrontCamera = _currentCameraFacing.value == CameraFacing.FRONT
                try {
                    camera.getGlInterface().setIsStreamHorizontalFlip(isFrontCamera)
                    Log.d(TAG, "Stream horizontal flip updated to: $isFrontCamera")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update stream horizontal flip: ${e.message}")
                }
                
                Log.d(TAG, "Camera switched to: ${_currentCameraFacing.value}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch camera", e)
            }
        }
    }
    
    /**
     * 切换静音
     */
    fun toggleMute() {
        genericCamera2?.let { camera ->
            val newMuteState = !_isMuted.value
            try {
                if (newMuteState) {
                    camera.disableAudio()
                } else {
                    camera.enableAudio()
                }
                _isMuted.value = newMuteState
                Log.d(TAG, "Mute toggled: $newMuteState")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle mute", e)
            }
        }
    }
    
    /**
     * 切换闪光灯
     */
    fun toggleFlash(): Boolean {
        genericCamera2?.let { camera ->
            if (_currentCameraFacing.value == CameraFacing.BACK && camera.isLanternSupported) {
                val newFlashState = !_isFlashOn.value
                try {
                    if (newFlashState) {
                        camera.enableLantern()
                    } else {
                        camera.disableLantern()
                    }
                    _isFlashOn.value = camera.isLanternEnabled
                    Log.d(TAG, "Flash toggled: ${_isFlashOn.value}")
                    return _isFlashOn.value
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle flash", e)
                }
            }
        }
        return false
    }
    
    /**
     * 设置码率
     */
    fun setBitrate(bitrate: Int) {
        genericCamera2?.setVideoBitrateOnFly(bitrate * 1000)
        Log.d(TAG, "Bitrate set to: ${bitrate}kbps")
    }
    
    /**
     * 统一的资源释放方法
     * 合并了原来的 releaseCamera 和 releaseResourcesForReconnect 方法
     * 
     * @param updateState 是否更新流状态为 Idle（用户主动停止时为 true，重连时为 false）
     * @param clearUrl 是否清空 RTMP URL（用户主动停止时为 true，重连时为 false）
     */
    private fun releaseAllResources(updateState: Boolean = true, clearUrl: Boolean = false) {
        Log.d(TAG, "Releasing all resources (updateState=$updateState, clearUrl=$clearUrl)...")
        
        genericCamera2?.let { camera ->
            try {
                // 无条件调用 stopStream()，确保释放 TCP 连接
                // 即使 isStreaming 为 false，也调用以确保底层资源释放
                try {
                    camera.stopStream()
                    Log.d(TAG, "stopStream called")
                } catch (e: Exception) {
                    Log.w(TAG, "stopStream failed: ${e.message}")
                }
                
                // 停止预览，释放摄像头和麦克风
                try {
                    camera.stopPreview()
                    Log.d(TAG, "stopPreview called")
                } catch (e: Exception) {
                    Log.w(TAG, "stopPreview failed: ${e.message}")
                }
                
                // 停止摄像头
                try {
                    camera.stopCamera()
                    Log.d(TAG, "stopCamera called")
                } catch (e: Exception) {
                    Log.w(TAG, "stopCamera failed: ${e.message}")
                }
                
                Log.d(TAG, "All camera resources released successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing camera resources", e)
            }
        }
        
        // 清空摄像头实例
        genericCamera2 = null
        
        // 根据参数决定是否更新状态
        if (updateState) {
            _streamState.value = StreamState.Idle
        }
        
        // 根据参数决定是否清空 URL
        if (clearUrl) {
            currentRtmpUrl = ""
        }
        
        Log.d(TAG, "Resource cleanup completed")
    }

    /**
     * 兼容性方法：释放摄像头资源（用户主动停止）
     */
    private fun releaseCamera() {
        releaseAllResources(updateState = true, clearUrl = false)
    }

    /**
     * 兼容性方法：释放资源用于重连
     */
    private fun releaseResourcesForReconnect() {
        releaseAllResources(updateState = false, clearUrl = false)
    }
    
    fun isStreaming(): Boolean = genericCamera2?.isStreaming == true
    
    // ==================== ConnectChecker 回调 ====================
    
    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "Connection started: $url")
        handler.post {
            _streamState.value = StreamState.Connecting
            updateNotification("正在连接...")
        }
    }
    
    override fun onConnectionSuccess() {
        Log.d(TAG, "Connection success!")
        handler.post {
            // 连接成功，重置重连计数器
            resetReconnectAttempts()
            _streamState.value = StreamState.Streaming()
            updateNotification("正在推流")
            
            // ✅ 移到这里：连接成功后恢复音频和闪光灯状态
            genericCamera2?.let { camera ->
                // 恢复音频状态
                if (_isMuted.value) {
                    try {
                        camera.disableAudio()
                        Log.d(TAG, "Audio mute state restored after connection success")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to restore audio mute state: ${e.message}")
                    }
                }
                
                // 恢复闪光灯状态
                if (_isFlashOn.value && _currentCameraFacing.value == CameraFacing.BACK) {
                    try {
                        if (camera.isLanternSupported) {
                            camera.enableLantern()
                            Log.d(TAG, "Flash state restored after connection success")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to restore flash state: ${e.message}")
                    }
                }
            }
        }
    }
    
    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "Connection failed: $reason")
        handler.post {
            // 所有连接失败都使用指数退避算法，不重置计数器
            if (currentRtmpUrl.isNotEmpty()) {
                _streamState.value = StreamState.Reconnecting
                
                if (reason.contains("UnresolvedAddressException") || reason.contains("UnknownHostException")) {
                    Log.w(TAG, "Network address resolution failed, using exponential backoff")
                    updateNotification("网络解析失败，准备重连...")
                } else {
                    Log.w(TAG, "Connection failed, using exponential backoff")
                    updateNotification("连接失败，准备重连...")
                }
                // 资源释放移到 scheduleReconnect() 内部统一处理
                scheduleReconnect()
            } else {
                _streamState.value = StreamState.Error("连接失败: $reason")
                updateNotification("连接失败")
                releaseWakeLock()
            }
        }
    }
    
    override fun onNewBitrate(bitrate: Long) {
        handler.post {
            val currentState = _streamState.value
            if (currentState is StreamState.Streaming) {
                _streamState.value = StreamState.Streaming(bitrate)
            }
            _streamStats.value = _streamStats.value.copy(
                bitrate = bitrate,
                streamTime = System.currentTimeMillis() - streamStartTime
            )
        }
    }
    
    override fun onDisconnect() {
        Log.d(TAG, "onDisconnect called - checking if this is user-initiated")
        handler.post {
            // 根据 RootEncoder 文档，onDisconnect 主要在用户主动调用 disconnect() 时触发
            // 如果 currentRtmpUrl 为空，说明是用户主动停止，不应该重连
            // 如果 currentRtmpUrl 不为空，可能是异常断开，但需要谨慎处理
            
            if (currentRtmpUrl.isEmpty()) {
                // 用户已主动停止推流，不需要重连
                Log.d(TAG, "User-initiated disconnect, no reconnection needed")
                _streamState.value = StreamState.Idle
                updateNotification("推流已停止")
                releaseWakeLock()
            } else {
                // ✅ 优化：检查当前状态，避免与 onConnectionFailed 重复处理
                val currentState = _streamState.value
                if (currentState is StreamState.Reconnecting) {
                    // 如果已经在重连状态，说明 onConnectionFailed 已经处理了，忽略此次 onDisconnect
                    Log.d(TAG, "Already in reconnecting state, ignoring onDisconnect (likely triggered after onConnectionFailed)")
                    return@post
                }
                
                // URL 不为空且不在重连状态，可能是异常断开
                Log.w(TAG, "Unexpected onDisconnect with non-empty URL and not in reconnecting state")
                Log.w(TAG, "This might be a genuine disconnect that wasn't caught by onConnectionFailed")
                
                // 保守处理：设置为重连状态并开始重连
                _streamState.value = StreamState.Reconnecting
                updateNotification("连接断开，准备重连...")
                scheduleReconnect()
            }
        }
    }
    
    override fun onAuthError() {
        Log.e(TAG, "Auth error")
        handler.post {
            _streamState.value = StreamState.Error("认证失败")
            updateNotification("认证失败")
            releaseWakeLock()
        }
    }
    
    override fun onAuthSuccess() {
        Log.d(TAG, "Auth success")
    }
    
    // ==================== 指数退避重连逻辑 ====================
    
    /**
     * 初始化摄像头并开始推流（核心方法）
     * 被 startStreaming 和 performReconnect 共同调用
     * 
     * @param url RTMP 推流地址
     * @param recalculateResolution 是否重新计算分辨率（首次启动为 true，重连为 false）
     * @return 是否成功启动
     */
    private fun initAndStartStream(url: String, recalculateResolution: Boolean = true): Boolean {
        try {
            // 1. 创建 GenericCamera2 实例
            Log.d(TAG, "Creating GenericCamera2 with Context mode")
            val camera = GenericCamera2(this, this)
            genericCamera2 = camera
            
            // 2. 设置视频编码器
            val codec = when (currentSettings.videoCodec) {
                com.example.camera_rtmp.data.VideoCodec.H264 -> VideoCodec.H264
                com.example.camera_rtmp.data.VideoCodec.H265 -> {
                    Log.w(TAG, "H.265 requires Enhanced RTMP server support")
                    VideoCodec.H265
                }
            }
            camera.setVideoCodec(codec)
            Log.d(TAG, "Video codec set to: $codec")
            
            // 3. 计算视频参数
            val rotation = when (currentSettings.videoRotation) {
                VideoRotation.AUTO -> CameraHelper.getCameraOrientation(applicationContext)
                else -> currentSettings.videoRotation.degrees
            }
            
            val videoWidth: Int
            val videoHeight: Int
            
            if (recalculateResolution) {
                // 首次启动：计算并保存分辨率
                val settingsWidth = currentSettings.videoWidth
                val settingsHeight = currentSettings.videoHeight
                val (landscapeWidth, landscapeHeight) = if (settingsWidth < settingsHeight) {
                    settingsHeight to settingsWidth
                } else {
                    settingsWidth to settingsHeight
                }
                videoWidth = landscapeWidth
                videoHeight = landscapeHeight
                computedVideoWidth = videoWidth
                computedVideoHeight = videoHeight
                Log.d(TAG, "Calculated resolution: ${videoWidth}x${videoHeight}")
            } else {
                // 重连：使用已保存的分辨率
                videoWidth = computedVideoWidth
                videoHeight = computedVideoHeight
                Log.d(TAG, "Using saved resolution: ${videoWidth}x${videoHeight}")
            }
            
            Log.d(TAG, "Video params: ${videoWidth}x${videoHeight}, rotation=$rotation")
            
            // 4. 准备视频编码器
            val videoPrepared = camera.prepareVideo(
                videoWidth,
                videoHeight,
                currentSettings.videoFps,
                currentSettings.videoBitrate * 1000,
                currentSettings.iFrameInterval,
                rotation
            )
            
            if (!videoPrepared) {
                Log.e(TAG, "Failed to prepare video encoder")
                return false
            }
            Log.d(TAG, "Video encoder prepared successfully")
            
            // 5. 准备音频编码器
            val audioPrepared = camera.prepareAudio(
                currentSettings.audioBitrate * 1000,
                currentSettings.audioSampleRate,
                currentSettings.audioIsStereo,
                currentSettings.audioEchoCanceler,
                currentSettings.audioNoiseSuppressor
            )
            
            if (!audioPrepared) {
                Log.w(TAG, "Failed to prepare audio encoder, continuing without audio")
            } else {
                Log.d(TAG, "Audio encoder prepared successfully")
            }
            
            // 6. 启动摄像头数据采集
            val facing = if (currentSettings.cameraFacing == CameraFacing.FRONT) {
                CameraHelper.Facing.FRONT
            } else {
                CameraHelper.Facing.BACK
            }
            camera.startPreview(facing)
            Log.d(TAG, "Camera capture started, isOnPreview=${camera.isOnPreview}")
            
            // 7. 设置镜像（前置摄像头需要水平翻转）
            val isFrontCamera = currentSettings.cameraFacing == CameraFacing.FRONT
            try {
                camera.getGlInterface().setIsStreamHorizontalFlip(isFrontCamera)
                Log.d(TAG, "Stream horizontal flip: $isFrontCamera")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set stream horizontal flip: ${e.message}")
            }
            
            // 8. 设置自动对焦
            if (currentSettings.autoFocus) {
                try {
                    camera.enableAutoFocus()
                    Log.d(TAG, "Auto focus enabled")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to enable auto focus: ${e.message}")
                }
            }
            
            // 9. 开始推流
            Log.d(TAG, "Starting stream to: $url")
            camera.startStream(url)
            streamStartTime = System.currentTimeMillis()
            
            Log.d(TAG, "Stream started, isStreaming=${camera.isStreaming}")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in initAndStartStream", e)
            return false
        }
    }
    
    /**
     * 重置重连尝试计数器
     */
    private fun resetReconnectAttempts() {
        reconnectAttempts = 0
        firstReconnectTime = 0L
        Log.d(TAG, "Reset reconnect attempts, base delay: ${baseReconnectDelay}ms")
    }
    
    /**
     * 计算指数退避延迟时间
     * 算法：delay = baseDelay * (2^attempts) + jitter
     * 
     * 参数：
     * - 第一次从1秒开始
     * - 最大延迟1小时
     * - 最大重连时间30天
     * 
     * 安全防护：
     * - 防止位移溢出
     * - 防止时间计算异常
     * - 添加异常捕获
     */
    private fun calculateBackoffDelay(): Long {
        return try {
            val currentTime = System.currentTimeMillis()
            
            // 记录首次重连时间
            if (firstReconnectTime == 0L) {
                firstReconnectTime = currentTime
            }
            
            // 检查是否超过最大重连时间（30天）
            val reconnectDuration = currentTime - firstReconnectTime
            if (reconnectDuration > maxReconnectDuration) {
                Log.w(TAG, "Max reconnect duration (30 days) reached, stopping reconnection")
                handler.post {
                    _streamState.value = StreamState.Error("重连时间超限（30天）")
                    updateNotification("重连超时")
                    releaseWakeLock()
                }
                return -1 // 表示停止重连
            }
            
            // 指数退避：1秒 * (2^attempts)，防止溢出
            val exponentialDelay = if (reconnectAttempts >= 20) {
                // 防止位移溢出，20次后直接使用最大延迟
                maxReconnectDelay
            } else {
                val power = 1L shl reconnectAttempts
                val delay = baseReconnectDelay * power
                // 检查是否溢出或超过最大值
                if (delay < 0 || delay > maxReconnectDelay) {
                    maxReconnectDelay
                } else {
                    delay
                }
            }
            
            // 添加随机抖动（0-25%），避免多个客户端同时重连
            val jitter = try {
                (exponentialDelay * 0.25 * Math.random()).toLong()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to calculate jitter, using 0", e)
                0L
            }
            
            // 限制最大延迟为1小时
            val finalDelay = minOf(exponentialDelay + jitter, maxReconnectDelay)
            
            val remainingDays = (maxReconnectDuration - reconnectDuration) / (24 * 60 * 60 * 1000L)
            Log.d(TAG, "Reconnect attempt ${reconnectAttempts + 1}, delay: ${finalDelay}ms (${finalDelay/1000}s), remaining: ${remainingDays}天")
            
            finalDelay
        } catch (e: Exception) {
            Log.e(TAG, "Exception in calculateBackoffDelay", e)
            // 发生异常时返回固定延迟
            60 * 1000L // 1分钟
        }
    }
    
    /**
     * 重构后的重连逻辑
     * 根据RootEncoder API文档，采用完全重新初始化的方式
     */
    private fun scheduleReconnect() {
        // 1. 取消之前的重连任务（防重入）
        cancelReconnect()
        
        // 2. 立即释放资源，确保干净的重连环境
        Log.d(TAG, "Releasing resources before scheduling reconnect...")
        releaseResourcesForReconnect()
        
        try {
            val delay = calculateBackoffDelay()
            if (delay < 0) {
                return // 停止重连
            }
            
            reconnectRunnable = Runnable {
                try {
                    if (_streamState.value is StreamState.Reconnecting && currentRtmpUrl.isNotEmpty()) {
                        reconnectAttempts++
                        Log.d(TAG, "=== 开始重连 (第${reconnectAttempts}次尝试) ===")
                        
                        // 根据RootEncoder文档，重连时应该完全重新初始化
                        performReconnect()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "重连准备阶段异常", e)
                    // 重连准备阶段的异常不再调用 scheduleReconnect()
                    // 连接相关的失败会通过 onConnectionFailed 统一处理
                    handler.post {
                        if (_streamState.value is StreamState.Reconnecting) {
                            // 准备阶段失败，直接设置为错误状态，避免与 onConnectionFailed 重复
                            _streamState.value = StreamState.Error("重连准备失败: ${e.message}")
                            updateNotification("重连准备失败")
                            releaseWakeLock()
                        }
                    }
                }
            }
            
            handler.postDelayed(reconnectRunnable!!, delay)
            
            // 计算显示信息
            val delaySeconds = delay / 1000
            val displayTime = when {
                delaySeconds < 60 -> "${delaySeconds}秒"
                delaySeconds < 3600 -> "${delaySeconds / 60}分钟"
                else -> "${delaySeconds / 3600}小时"
            }
            
            val remainingDays = if (firstReconnectTime > 0) {
                val elapsed = System.currentTimeMillis() - firstReconnectTime
                (maxReconnectDuration - elapsed) / (24 * 60 * 60 * 1000L)
            } else 30L
            
            updateNotification("重连中... 延迟${displayTime} (剩余${remainingDays}天)")
            
        } catch (e: Exception) {
            Log.e(TAG, "重连调度异常", e)
            // 调度异常时，确保完全清理资源
            releaseAllResources(updateState = true, clearUrl = true)
            releaseWakeLock()
            _streamState.value = StreamState.Error("重连调度异常: ${e.message}")
            updateNotification("重连调度失败")
        }
    }
    
    /**
     * 执行重连操作
     * 根据RootEncoder文档，完全重新初始化摄像头和编码器
     * 资源已在调度重连前释放，这里只需重新创建
     */
    private fun performReconnect() {
        Log.d(TAG, "执行重连操作...")
        
        // 资源已经在 scheduleReconnect 调度前释放
        // 这里只需确保 genericCamera2 为 null
        if (genericCamera2 != null) {
            Log.w(TAG, "Camera instance still exists, releasing...")
            releaseResourcesForReconnect()
        }
        
        // 等待一小段时间确保资源完全释放
        Thread.sleep(500)
        
        // 使用公共方法初始化并开始推流（重连时不重新计算分辨率）
        val success = initAndStartStream(currentRtmpUrl, recalculateResolution = false)
        if (!success) {
            throw Exception("重连初始化失败")
        }
        
        Log.d(TAG, "=== 重连操作完成 ===")
    }
    
    private fun cancelReconnect() {
        try {
            reconnectRunnable?.let { 
                handler.removeCallbacks(it)
                Log.d(TAG, "Cancelled reconnect runnable")
            }
            reconnectRunnable = null
        } catch (e: Exception) {
            Log.e(TAG, "Exception in cancelReconnect", e)
        }
    }
    
    // ==================== WakeLock 管理 ====================
    
    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CameraRtmp::StreamingWakeLock"
            )
        }
        wakeLock?.acquire(10 * 60 * 60 * 1000L) // 10 hours max
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
    
    // ==================== 通知管理 ====================
    
    private fun createNotification(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, StreamService::class.java).apply {
                action = ACTION_STOP_STREAMING
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CameraRtmpApplication.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("RTMP 推流")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
    
    private fun updateNotification(status: String) {
        val notification = createNotification(status)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}