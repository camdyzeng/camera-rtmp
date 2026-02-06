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
 * RTMP 推流服务 - 简化版
 * 
 * 设计原则：
 * 1. 只有两个状态：STOPPED/RUNNING
 * 2. Watchdog 永久运行，根据状态判断是否执行检查
 * 3. 重连逻辑简单：延迟3秒后重连
 * 4. onDisconnect 不触发重连
 * 5. onConnectionFailed 不触发重连，由 Watchdog 统一监控
 * 6. 所有异常由 Watchdog 监控处理，简化架构
 */
class StreamService : Service(), ConnectChecker {
    
    companion object {
        private const val TAG = "StreamService"
        private const val NOTIFICATION_ID = 1001
        private const val RECONNECT_DELAY_MS = 3000L  // 重连前等待时间
        
        const val ACTION_START_STREAMING = "com.example.camera_rtmp.START_STREAMING"
        const val ACTION_STOP_STREAMING = "com.example.camera_rtmp.STOP_STREAMING"
        
        fun startService(context: Context) {
            val intent = Intent(context, StreamService::class.java).apply {
                action = ACTION_START_STREAMING
            }
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
    
    // ==================== 简化状态管理 ====================
    
    private val stateManager = SimpleStateManager()
    private lateinit var watchdogManager: WatchdogManager
    
    // 当前推流 URL
    private var currentUrl: String = ""
    
    // 重连任务
    private var reconnectJob: Job? = null
    
    // 对外暴露的状态接口
    private val _streamState = MutableStateFlow<StreamState>(StreamState.Idle)
    val streamState: StateFlow<StreamState> = _streamState.asStateFlow()
    
    val watchdogStats: StateFlow<WatchdogManager.WatchdogStats> by lazy { 
        watchdogManager.stats 
    }
    
    // ==================== 其他状态 ====================
    
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
    
    // ==================== 配置和临时变量 ====================
    
    private var currentSettings = StreamSettings()
    private var streamStartTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    
    inner class StreamBinder : Binder() {
        fun getService(): StreamService = this@StreamService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        // 初始化 Watchdog 管理器
        watchdogManager = WatchdogManager(
            stateManager = stateManager,
            handler = handler,
            onAnomalyDetected = { anomaly -> handleWatchdogAnomaly(anomaly) }
        )
        
        // 启动永久运行的 Watchdog（使用默认配置，所有参数由 WatchdogManager 内部管理）
        watchdogManager.startPermanentWatchdog(scope = serviceScope)
        
        Log.d(TAG, "Permanent watchdog started")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STREAMING -> {
                startForeground(NOTIFICATION_ID, createNotification("准备就绪"))
                _isServiceRunning.value = true
            }
            ACTION_STOP_STREAMING -> {
                stopStreaming()
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
            // 1. 先取消重连任务
            cancelReconnect()
            
            // 2. 销毁 watchdog（内部会取消 watchdogJob）
            if (::watchdogManager.isInitialized) {
                watchdogManager.destroy()
            }
            
            // 3. 最后取消整个作用域（兜底）
            serviceScope.cancel()
            
            // 4. 释放资源
            releaseAllResources()
            releaseWakeLock()
            
            // 5. 重置状态
            stateManager.setStopped()
            _isServiceRunning.value = false
            _streamStats.value = StreamStats()
            _streamState.value = StreamState.Idle
            
            Log.d(TAG, "Service cleanup completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Exception during service cleanup", e)
        }
    }
    
    // ==================== 公共 API ====================
    
    fun initializeStreaming(settings: StreamSettings) {
        this.currentSettings = settings
        _currentCameraFacing.value = settings.cameraFacing
        
        // Watchdog 使用内部默认配置，无需外部传入参数
        Log.d(TAG, "Streaming initialized with settings: $settings")
    }
    
    fun updateSettings(settings: StreamSettings) {
        currentSettings = settings
        if (stateManager.isRunning()) {
            genericCamera2?.setVideoBitrateOnFly(settings.videoBitrate * 1000)
        }
    }
    
    fun startStreaming(url: String): Boolean {
        Log.d(TAG, "User requested start streaming: $url")
        
        if (stateManager.isRunning()) {
            Log.w(TAG, "Already running, ignoring start request")
            return false
        }
        
        if (url.isBlank()) {
            Log.e(TAG, "URL is empty")
            return false
        }
        
        // 重置状态
        currentUrl = url
        stateManager.setRunning()
        
        _streamState.value = StreamState.Preparing
        updateNotification("正在准备...")
        
        try {
            acquireWakeLock()
            releaseAllResources()
            
            val success = initAndStartStream(url)
            if (!success) {
                stateManager.setStopped()
                _streamState.value = StreamState.Error("推流启动失败")
                releaseWakeLock()
                return false
            }
            
            _streamState.value = StreamState.Connecting
            updateNotification("正在连接...")
            Log.d(TAG, "Stream initialization successful")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting stream", e)
            stateManager.setStopped()
            _streamState.value = StreamState.Error("推流启动失败: ${e.message}")
            releaseWakeLock()
            return false
        }
    }
    
    fun stopStreaming() {
        Log.d(TAG, "User requested stop streaming")
        
        if (stateManager.isStopped()) {
            Log.d(TAG, "Already stopped, ignoring stop request")
            return
        }
        
        cancelReconnect()
        stateManager.setStopped()
        releaseAllResources()
        
        _streamState.value = StreamState.Idle
        _streamStats.value = StreamStats()
        
        releaseWakeLock()
        updateNotification("已停止")
        
        Log.d(TAG, "Streaming stopped by user")
    }
    
    fun switchCamera() {
        genericCamera2?.let { camera ->
            try {
                camera.switchCamera()
                _currentCameraFacing.value = if (_currentCameraFacing.value == CameraFacing.FRONT) {
                    CameraFacing.BACK
                } else {
                    CameraFacing.FRONT
                }
                _isFlashOn.value = false
                
                val isFrontCamera = _currentCameraFacing.value == CameraFacing.FRONT
                try {
                    camera.getGlInterface().setIsStreamHorizontalFlip(isFrontCamera)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to update stream horizontal flip: ${e.message}")
                }
                
                Log.d(TAG, "Camera switched to: ${_currentCameraFacing.value}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to switch camera", e)
            }
        }
    }
    
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
    
    fun setBitrate(bitrate: Int) {
        genericCamera2?.setVideoBitrateOnFly(bitrate * 1000)
        Log.d(TAG, "Bitrate set to: ${bitrate}kbps")
    }
    
    private fun releaseAllResources() {
        Log.d(TAG, "Releasing all resources...")
        
        if (::watchdogManager.isInitialized) {
            watchdogManager.setCamera(null)
        }
        
        genericCamera2?.let { camera ->
            try {
                try { camera.stopStream() } catch (e: Exception) { Log.w(TAG, "stopStream failed: ${e.message}") }
                try { camera.stopPreview() } catch (e: Exception) { Log.w(TAG, "stopPreview failed: ${e.message}") }
                try { camera.stopCamera() } catch (e: Exception) { Log.w(TAG, "stopCamera failed: ${e.message}") }
                Log.d(TAG, "All camera resources released")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing camera resources", e)
            }
        }
        
        genericCamera2 = null
    }
    
    fun isStreaming(): Boolean = genericCamera2?.isStreaming == true
    
    fun getWatchdogConfig(): WatchdogManager.WatchdogConfig? {
        return if (::watchdogManager.isInitialized) watchdogManager.getCurrentConfig() else null
    }
    
    fun isWatchdogRunning(): Boolean {
        return if (::watchdogManager.isInitialized) watchdogManager.isWatchdogRunning() else false
    }
    
    // ==================== ConnectChecker 回调 ====================
    
    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "Connection started: $url")
        handler.post {
            if (stateManager.isStopped()) return@post
            _streamState.value = StreamState.Connecting
            updateNotification("正在连接...")
        }
    }
    
    override fun onConnectionSuccess() {
        Log.d(TAG, "Connection success!")
        handler.post {
            if (stateManager.isStopped()) return@post
            
            _streamState.value = StreamState.Streaming()
            updateNotification("正在推流")
            
            // 恢复音频和闪光灯状态
            genericCamera2?.let { camera ->
                if (_isMuted.value) {
                    try { camera.disableAudio() } catch (e: Exception) { Log.w(TAG, "Failed to restore mute: ${e.message}") }
                }
                if (_isFlashOn.value && _currentCameraFacing.value == CameraFacing.BACK) {
                    try { if (camera.isLanternSupported) camera.enableLantern() } catch (e: Exception) { Log.w(TAG, "Failed to restore flash: ${e.message}") }
                }
            }
        }
    }
    
    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "Connection failed: $reason")
        handler.post {
            releaseAllResources()  // 立即释放资源，避免TCP半连接
            
            if (stateManager.isStopped()) return@post
            
            // 不触发重连，由 Watchdog 统一监控处理
            Log.d(TAG, "Connection failed, waiting for watchdog to handle reconnect")
            _streamState.value = StreamState.Error("连接失败: $reason")
            updateNotification("连接失败，等待重试...")
        }
    }
    
    override fun onNewBitrate(bitrate: Long) {
        handler.post {
            if (stateManager.isStopped()) return@post
            
            if (::watchdogManager.isInitialized) {
                watchdogManager.updateBitrate(bitrate)
            }
            
            _streamStats.value = _streamStats.value.copy(
                bitrate = bitrate,
                streamTime = System.currentTimeMillis() - streamStartTime
            )
        }
    }
    
    override fun onDisconnect() {
        Log.d(TAG, "onDisconnect called")
        handler.post {
            releaseAllResources()  // 立即释放资源，避免TCP半连接
            
            if (stateManager.isStopped()) {
                Log.d(TAG, "User initiated disconnect, ignoring")
                return@post
            }
            
            // 不触发重连，只更新状态，由 Watchdog 负责
            Log.d(TAG, "Disconnect detected, waiting for watchdog")
            //_streamState.value = StreamState.Reconnecting
            updateNotification("连接断开，等待重连...")
        }
    }
    
    override fun onAuthError() {
        Log.e(TAG, "Auth error")
        handler.post {
            releaseAllResources()  // 释放资源，避免TCP半连接
            
            if (stateManager.isStopped()) return@post
            stateManager.setStopped()
            _streamState.value = StreamState.Error("认证失败")
            updateNotification("认证失败")
            releaseWakeLock()
        }
    }
    
    override fun onAuthSuccess() {
        Log.d(TAG, "Auth success")
    }
    
    // ==================== 核心推流方法 ====================
    
    private fun initAndStartStream(url: String): Boolean {
        try {
            Log.d(TAG, "Creating GenericCamera2 with Context mode")
            val camera = GenericCamera2(this, this)
            genericCamera2 = camera
            
            // 立即设置 camera 到 watchdog，这样即使连接失败 watchdog 也能监控
            if (::watchdogManager.isInitialized) {
                watchdogManager.setCamera(camera)
            }
            
            val codec = when (currentSettings.videoCodec) {
                com.example.camera_rtmp.data.VideoCodec.H264 -> VideoCodec.H264
                com.example.camera_rtmp.data.VideoCodec.H265 -> VideoCodec.H265
            }
            camera.setVideoCodec(codec)
            
            val rotation = when (currentSettings.videoRotation) {
                VideoRotation.AUTO -> CameraHelper.getCameraOrientation(applicationContext)
                else -> currentSettings.videoRotation.degrees
            }
            
            // 确保宽 > 高（横向模式）
            val settingsWidth = currentSettings.videoWidth
            val settingsHeight = currentSettings.videoHeight
            val (videoWidth, videoHeight) = if (settingsWidth < settingsHeight) {
                settingsHeight to settingsWidth
            } else {
                settingsWidth to settingsHeight
            }
            
            Log.d(TAG, "Video params: ${videoWidth}x${videoHeight}, rotation=$rotation")
            
            val videoPrepared = camera.prepareVideo(
                videoWidth, videoHeight,
                currentSettings.videoFps,
                currentSettings.videoBitrate * 1000,
                currentSettings.iFrameInterval,
                rotation
            )
            
            if (!videoPrepared) {
                Log.e(TAG, "Failed to prepare video encoder")
                return false
            }
            
            val audioPrepared = camera.prepareAudio(
                currentSettings.audioBitrate * 1000,
                currentSettings.audioSampleRate,
                currentSettings.audioIsStereo,
                currentSettings.audioEchoCanceler,
                currentSettings.audioNoiseSuppressor
            )
            
            if (!audioPrepared) {
                Log.w(TAG, "Failed to prepare audio encoder, continuing without audio")
            }
            
            val facing = if (currentSettings.cameraFacing == CameraFacing.FRONT) {
                CameraHelper.Facing.FRONT
            } else {
                CameraHelper.Facing.BACK
            }
            camera.startPreview(facing)
            
            val isFrontCamera = currentSettings.cameraFacing == CameraFacing.FRONT
            try {
                camera.getGlInterface().setIsStreamHorizontalFlip(isFrontCamera)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set stream horizontal flip: ${e.message}")
            }
            
            if (currentSettings.autoFocus) {
                try { camera.enableAutoFocus() } catch (e: Exception) { Log.w(TAG, "Failed to enable auto focus: ${e.message}") }
            }
            
            Log.d(TAG, "Starting stream to: $url")
            camera.startStream(url)
            streamStartTime = System.currentTimeMillis()
            
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in initAndStartStream", e)
            return false
        }
    }
    
    // ==================== 简化的重连管理 ====================
    
    private fun scheduleReconnect() {
        cancelReconnect()
        
        Log.d(TAG, "Scheduling reconnect")
        
        reconnectJob = serviceScope.launch {
            performReconnect()
        }
    }
    
    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }
    
    private suspend fun performReconnect() {
        if (stateManager.isStopped()) {
            Log.d(TAG, "Status is STOPPED, skipping reconnect")
            return
        }
        
        if (currentUrl.isEmpty()) {
            Log.e(TAG, "Reconnect URL is empty")
            return
        }
        
        Log.d(TAG, "=== Performing reconnect ===")
        
        releaseAllResources()
        
        Log.d(TAG, "Resources released, waiting ${RECONNECT_DELAY_MS}ms before reconnect")
        delay(RECONNECT_DELAY_MS)
        
        if (stateManager.isStopped()) {
            Log.d(TAG, "Status changed to STOPPED during delay, aborting reconnect")
            return
        }
        
        val success = initAndStartStream(currentUrl)
        if (!success) {
            Log.e(TAG, "Reconnect initialization failed, waiting for watchdog")
        }
        
        Log.d(TAG, "=== Reconnect operation completed ===")
    }
    
    private fun handleWatchdogAnomaly(anomaly: WatchdogManager.WatchdogAnomaly) {
        Log.w(TAG, "Watchdog anomaly: ${anomaly.description} (${anomaly.severity})")
        
        if (stateManager.isStopped()) {
            Log.d(TAG, "Status is STOPPED, ignoring watchdog anomaly")
            return
        }
        
        when (anomaly.severity) {
            WatchdogManager.WatchdogAnomaly.Severity.WARNING -> {
                updateNotification("推流异常: ${anomaly.description}")
            }
            WatchdogManager.WatchdogAnomaly.Severity.ERROR,
            WatchdogManager.WatchdogAnomaly.Severity.CRITICAL -> {
                Log.w(TAG, "Watchdog detected error, triggering reconnect")
                _streamState.value = StreamState.Reconnecting
                updateNotification("检测到异常，准备重连...")
                scheduleReconnect()
            }
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
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
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
            this, 1,
            Intent(this, StreamService::class.java).apply { action = ACTION_STOP_STREAMING },
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
