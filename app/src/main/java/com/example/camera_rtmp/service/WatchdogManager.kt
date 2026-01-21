package com.example.camera_rtmp.service

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.pedro.library.generic.GenericCamera2
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/**
 * 推流监控管理器 (Watchdog) - 永久运行模式
 * 
 * 设计原则：
 * 1. 服务启动时启动 Watchdog，服务销毁时停止
 * 2. Watchdog 永久运行，每次检查时判断状态
 * 3. STOPPED 状态跳过检查，RUNNING 状态执行检查
 * 
 * 功能：
 * 1. 监控推流健康状态
 * 2. 检测推流异常（码率异常、连接断开、编码器异常等）
 * 3. 自动触发恢复机制
 * 4. 提供监控统计信息
 */
class WatchdogManager(
    private val stateManager: SimpleStateManager,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val onAnomalyDetected: (WatchdogAnomaly) -> Unit
) {
    
    companion object {
        private const val TAG = "WatchdogManager"
        
        // 默认配置
        private const val DEFAULT_CHECK_INTERVAL = 5000L // 5秒检查一次
        private const val DEFAULT_BITRATE_TIMEOUT = 30000L // 30秒无码率视为异常
        private const val DEFAULT_MIN_BITRATE_THRESHOLD = 1000L // 最小码率阈值 1kbps
        private const val DEFAULT_MAX_ZERO_BITRATE_COUNT = 6 // 最多允许6次连续零码率（30秒）
        private const val DEFAULT_CONNECTION_TIMEOUT = 60000L // 60秒连接超时
    }
    
    /**
     * Watchdog配置
     */
    data class WatchdogConfig(
        val checkInterval: Long = DEFAULT_CHECK_INTERVAL,
        val bitrateTimeout: Long = DEFAULT_BITRATE_TIMEOUT,
        val minBitrateThreshold: Long = DEFAULT_MIN_BITRATE_THRESHOLD,
        val maxZeroBitrateCount: Int = DEFAULT_MAX_ZERO_BITRATE_COUNT,
        val connectionTimeout: Long = DEFAULT_CONNECTION_TIMEOUT,
        val enableBitrateMonitoring: Boolean = true,
        val enableConnectionMonitoring: Boolean = true,
        val enableEncoderMonitoring: Boolean = true
    )
    
    /**
     * 监控异常类型
     */
    sealed class WatchdogAnomaly(val description: String, val severity: Severity) {
        enum class Severity { WARNING, ERROR, CRITICAL }
        
        // 码率相关异常
        class ZeroBitrate(duration: Long) : WatchdogAnomaly(
            "码率为零持续 ${duration/1000} 秒", Severity.ERROR
        )
        
        class LowBitrate(currentBitrate: Long, threshold: Long) : WatchdogAnomaly(
            "码率过低: ${currentBitrate}bps < ${threshold}bps", Severity.WARNING
        )
        
        class BitrateFluctuation(variance: Double) : WatchdogAnomaly(
            "码率波动异常: 方差=${String.format("%.2f", variance)}", Severity.WARNING
        )
        
        // 连接相关异常
        class ConnectionStuck(duration: Long) : WatchdogAnomaly(
            "连接卡死 ${duration/1000} 秒", Severity.CRITICAL
        )
        
        class StreamingTimeout(duration: Long) : WatchdogAnomaly(
            "推流超时 ${duration/1000} 秒", Severity.ERROR
        )
        
        // 编码器相关异常
        class EncoderError(error: String) : WatchdogAnomaly(
            "编码器异常: $error", Severity.ERROR
        )
        
        class CameraDisconnected : WatchdogAnomaly(
            "摄像头连接断开", Severity.CRITICAL
        )
    }
    
    /**
     * 监控统计信息
     */
    data class WatchdogStats(
        val isRunning: Boolean = false,
        val startTime: Long = 0L,
        val totalChecks: Long = 0L,
        val effectiveChecks: Long = 0L, // 实际执行的检查次数（RUNNING状态下）
        val skippedChecks: Long = 0L,   // 跳过的检查次数（STOPPED状态下）
        val anomaliesDetected: Long = 0L,
        val lastCheckTime: Long = 0L,
        val currentBitrate: Long = 0L,
        val averageBitrate: Long = 0L,
        val bitrateHistory: List<Long> = emptyList(),
        val zeroBitrateCount: Int = 0,
        val lastAnomalyTime: Long = 0L,
        val lastAnomalyType: String = ""
    )
    
    // === 状态管理 ===
    private var config = WatchdogConfig()
    private var watchdogJob: Job? = null
    private var cameraRef: GenericCamera2? = null
    
    // === 监控数据 ===
    private var startTime = 0L
    private var totalChecks = 0L
    private var effectiveChecks = 0L
    private var skippedChecks = 0L
    private var anomaliesDetected = 0L
    private var lastCheckTime = 0L
    
    // 码率监控
    private var currentBitrate = 0L
    private var lastBitrateUpdateTime = 0L
    private var zeroBitrateCount = 0
    private var bitrateHistory = mutableListOf<Long>()
    private val maxHistorySize = 20 // 保留最近20次记录
    
    // 连接监控
    private var streamStartTime = 0L
    
    // 异常记录
    private var lastAnomalyTime = 0L
    private var lastAnomalyType = ""
    
    // 异常防抖机制
    private val anomalyDebounceMap = mutableMapOf<String, Long>()
    private val anomalyDebounceInterval = 30000L // 30秒内同类型异常只报告一次
    
    // 异常状态跟踪
    private var connectionStuckFirstDetected = 0L
    private var lowBitrateFirstDetected = 0L
    private var bitrateFluctuationFirstDetected = 0L
    
    // 状态流
    private val _stats = MutableStateFlow(WatchdogStats())
    val stats: StateFlow<WatchdogStats> = _stats.asStateFlow()
    
    /**
     * 启动永久运行的 Watchdog
     * 服务启动时调用，永不停止（直到 destroy 被调用）
     */
    fun startPermanentWatchdog(
        customConfig: WatchdogConfig = WatchdogConfig(),
        scope: CoroutineScope
    ) {
        if (watchdogJob?.isActive == true) {
            Log.d(TAG, "Watchdog already running, ignoring start request")
            return
        }
        
        Log.i(TAG, "Starting permanent watchdog with config: $customConfig")
        
        config = customConfig
        startTime = System.currentTimeMillis()
        resetMonitoringData()
        
        watchdogJob = scope.launch {
            try {
                while (isActive) {
                    delay(config.checkInterval)
                    performCheckIfRunning()
                    updateStats()
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Permanent watchdog cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Permanent watchdog error", e)
            }
        }
        
        Log.i(TAG, "Permanent watchdog started successfully")
    }
    
    /**
     * 销毁 Watchdog
     * 服务销毁时调用
     */
    fun destroy() {
        Log.i(TAG, "Destroying watchdog")
        
        watchdogJob?.cancel()
        watchdogJob = null
        cameraRef = null
        resetMonitoringData()
        
        Log.i(TAG, "Watchdog destroyed")
    }
    
    /**
     * 设置/更新摄像头引用
     * 开始推流时调用
     */
    fun setCamera(camera: GenericCamera2?) {
        cameraRef = camera
        if (camera != null) {
            streamStartTime = System.currentTimeMillis()
            resetMonitoringData()
            Log.d(TAG, "Camera reference set, monitoring data reset")
        } else {
            Log.d(TAG, "Camera reference cleared")
        }
    }
    
    /**
     * 更新配置
     */
    fun updateConfig(newConfig: WatchdogConfig) {
        config = newConfig
        Log.d(TAG, "Config updated: $newConfig")
    }
    
    /**
     * 执行检查（如果是 RUNNING 状态）
     */
    private suspend fun performCheckIfRunning() {
        // 防止溢出（虽然理论上需要万亿年才会溢出）
        if (totalChecks < Long.MAX_VALUE) {
            totalChecks++
        } else {
            // 重置计数器，记录日志
            Log.w(TAG, "totalChecks reached MAX_VALUE, resetting counters")
            resetCounters()
        }
        lastCheckTime = System.currentTimeMillis()
        
        // 检查状态：STOPPED 则跳过
        if (stateManager.isStopped()) {
            if (skippedChecks < Long.MAX_VALUE) {
                skippedChecks++
            }
            Log.v(TAG, "Status is STOPPED, skipping check #$totalChecks")
            return
        }
        
        // RUNNING 状态，执行实际检查
        if (effectiveChecks < Long.MAX_VALUE) {
            effectiveChecks++
        }
        Log.d(TAG, "Performing health check #$effectiveChecks (total: $totalChecks)")
        
        val camera = cameraRef
        if (camera == null) {
            Log.w(TAG, "Camera reference is null while RUNNING, triggering reconnect")
            reportAnomaly(WatchdogAnomaly.CameraDisconnected())
            return
        }
        
        try {
            // 1. 码率监控
            if (config.enableBitrateMonitoring) {
                checkBitrateHealth()
            }
            
            // 2. 连接监控
            if (config.enableConnectionMonitoring) {
                checkConnectionHealth(camera)
            }
            
            // 3. 编码器监控
            if (config.enableEncoderMonitoring) {
                checkEncoderHealth(camera)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Health check error", e)
            reportAnomaly(WatchdogAnomaly.EncoderError("健康检查异常: ${e.message}"))
        }
    }
    
    /**
     * 更新码率信息
     * 由StreamService在onNewBitrate回调中调用
     */
    fun updateBitrate(bitrate: Long) {
        currentBitrate = bitrate
        lastBitrateUpdateTime = System.currentTimeMillis()
        
        // 更新码率历史
        synchronized(bitrateHistory) {
            bitrateHistory.add(bitrate)
            if (bitrateHistory.size > maxHistorySize) {
                bitrateHistory.removeAt(0)
            }
        }
        
        // 重置零码率计数（如果码率恢复）
        if (bitrate > 0) {
            zeroBitrateCount = 0
        }
    }
    
    /**
     * 码率健康检查
     */
    private fun checkBitrateHealth() {
        val now = System.currentTimeMillis()
        val timeSinceStart = now - streamStartTime
        
        // 启动后等待一段时间再检查码率
        if (timeSinceStart < 10000) { // 启动10秒内不检查码率
            Log.d(TAG, "Bitrate check: waiting for startup (${timeSinceStart}ms < 10000ms)")
            return
        }
        
        // 关键检查：如果从未收到过码率更新，说明连接失败
        if (lastBitrateUpdateTime == 0L) {
            Log.w(TAG, "No bitrate received since start (${timeSinceStart}ms), connection likely failed")
            reportAnomaly(WatchdogAnomaly.ZeroBitrate(timeSinceStart))
            return
        }
        
        // 检查码率超时
        val bitrateAge = now - lastBitrateUpdateTime
        if (bitrateAge > config.bitrateTimeout) {
            reportAnomaly(WatchdogAnomaly.ZeroBitrate(bitrateAge))
            return
        }
        
        // 检查零码率
        if (currentBitrate == 0L) {
            zeroBitrateCount++
            if (zeroBitrateCount >= config.maxZeroBitrateCount) {
                val duration = zeroBitrateCount * config.checkInterval
                reportAnomaly(WatchdogAnomaly.ZeroBitrate(duration))
                return
            }
        } else {
            // 码率恢复，重置计数器
            zeroBitrateCount = 0
        }
        
        // 检查低码率（添加防抖）
        if (currentBitrate > 0 && currentBitrate < config.minBitrateThreshold) {
            val now = System.currentTimeMillis()
            if (lowBitrateFirstDetected == 0L) {
                lowBitrateFirstDetected = now
            } else if (now - lowBitrateFirstDetected > 15000) { // 持续15秒低码率才报警
                reportAnomalyWithDebounce(WatchdogAnomaly.LowBitrate(currentBitrate, config.minBitrateThreshold))
            }
        } else {
            // 码率恢复正常
            lowBitrateFirstDetected = 0L
        }
        
        // 检查码率波动（需要足够的历史数据）
        synchronized(bitrateHistory) {
            if (bitrateHistory.size >= 10) {
                val nonZeroRates = bitrateHistory.filter { it > 0 }
                if (nonZeroRates.size >= 5) {
                    val variance = calculateVariance(nonZeroRates)
                    val mean = nonZeroRates.average()
                    val standardDeviation = sqrt(variance)
                    val coefficientOfVariation = if (mean > 0) standardDeviation / mean else 0.0
                    
                    // 如果变异系数过大（超过50%），认为是异常波动
                    if (coefficientOfVariation > 0.5) {
                        reportAnomalyWithDebounce(WatchdogAnomaly.BitrateFluctuation(variance))
                    } else {
                        // 码率波动恢复正常
                        bitrateFluctuationFirstDetected = 0L
                    }
                }
            }
        }
    }
    
    /**
     * 连接健康检查
     */
    private fun checkConnectionHealth(camera: GenericCamera2) {
        val now = System.currentTimeMillis()
        
        try {
            val isStreaming = camera.isStreaming
            val timeSinceStart = now - streamStartTime
            Log.d(TAG, "Connection check: isStreaming=$isStreaming, timeSinceStart=${timeSinceStart}ms, connectionStuckFirstDetected=$connectionStuckFirstDetected")
            
            // 检查是否还在推流
            if (!isStreaming) {
                // 只有在启动一段时间后才检测连接卡死，避免启动时误报
                if (timeSinceStart > 5000) { // 启动5秒后才检测
                    if (connectionStuckFirstDetected == 0L) {
                        connectionStuckFirstDetected = now
                        Log.d(TAG, "Connection stuck detection started")
                    } else {
                        val stuckDuration = now - connectionStuckFirstDetected
                        Log.d(TAG, "Connection stuck duration: ${stuckDuration}ms")
                        if (stuckDuration > 10000) { // 持续10秒才报警
                            Log.w(TAG, "Connection stuck for ${stuckDuration}ms, triggering anomaly")
                            reportAnomaly(WatchdogAnomaly.ConnectionStuck(stuckDuration))
                            return
                        }
                    }
                }
                return
            } else {
                // 连接恢复正常
                connectionStuckFirstDetected = 0L
            }
            
            // 检查推流超时（如果长时间无码率更新）
            if (lastBitrateUpdateTime > 0) {
                val streamingDuration = now - lastBitrateUpdateTime
                if (streamingDuration > config.connectionTimeout) {
                    reportAnomaly(WatchdogAnomaly.StreamingTimeout(streamingDuration))
                }
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Connection health check failed", e)
            reportAnomaly(WatchdogAnomaly.EncoderError("连接检查失败: ${e.message}"))
        }
    }
    
    /**
     * 编码器健康检查
     */
    private fun checkEncoderHealth(camera: GenericCamera2) {
        try {
            // 检查摄像头状态
            if (!camera.isOnPreview) {
                reportAnomaly(WatchdogAnomaly.CameraDisconnected())
                return
            }
            
            // 检查编码器状态（通过isStreaming间接检查）
            if (camera.isOnPreview && !camera.isStreaming) {
                reportAnomaly(WatchdogAnomaly.EncoderError("编码器未运行但摄像头正常"))
            }
            
        } catch (e: Exception) {
            Log.w(TAG, "Encoder health check failed", e)
            reportAnomaly(WatchdogAnomaly.EncoderError("编码器检查失败: ${e.message}"))
        }
    }
    
    /**
     * 报告异常（带防抖机制）
     */
    private fun reportAnomalyWithDebounce(anomaly: WatchdogAnomaly) {
        val anomalyKey = anomaly::class.java.simpleName
        val now = System.currentTimeMillis()
        
        // 检查防抖间隔
        val lastReportTime = anomalyDebounceMap[anomalyKey] ?: 0L
        if (now - lastReportTime < anomalyDebounceInterval) {
            Log.v(TAG, "Anomaly debounced: ${anomaly.description}")
            return
        }
        
        // 更新防抖时间戳
        anomalyDebounceMap[anomalyKey] = now
        
        // 报告异常
        reportAnomaly(anomaly)
    }
    
    /**
     * 报告异常
     */
    private fun reportAnomaly(anomaly: WatchdogAnomaly) {
        // 再次检查状态，确保在 RUNNING 状态下才报告
        if (stateManager.isStopped()) {
            Log.d(TAG, "Status became STOPPED, ignoring anomaly: ${anomaly.description}")
            return
        }
        
        if (anomaliesDetected < Long.MAX_VALUE) {
            anomaliesDetected++
        }
        lastAnomalyTime = System.currentTimeMillis()
        lastAnomalyType = anomaly::class.java.simpleName
        
        Log.w(TAG, "Anomaly detected: ${anomaly.description} (${anomaly.severity})")
        
        // 在主线程中回调
        handler.post {
            // 再次检查状态，避免竞态条件
            if (stateManager.isRunning()) {
                onAnomalyDetected(anomaly)
            } else {
                Log.d(TAG, "Status became STOPPED before callback, ignoring anomaly")
            }
        }
    }
    
    /**
     * 更新统计信息
     */
    private fun updateStats() {
        val averageBitrate = synchronized(bitrateHistory) {
            if (bitrateHistory.isNotEmpty()) {
                bitrateHistory.filter { it > 0 }.average().toLong()
            } else {
                0L
            }
        }
        
        _stats.value = WatchdogStats(
            isRunning = watchdogJob?.isActive == true,
            startTime = startTime,
            totalChecks = totalChecks,
            effectiveChecks = effectiveChecks,
            skippedChecks = skippedChecks,
            anomaliesDetected = anomaliesDetected,
            lastCheckTime = lastCheckTime,
            currentBitrate = currentBitrate,
            averageBitrate = averageBitrate,
            bitrateHistory = synchronized(bitrateHistory) { bitrateHistory.toList() },
            zeroBitrateCount = zeroBitrateCount,
            lastAnomalyTime = lastAnomalyTime,
            lastAnomalyType = lastAnomalyType
        )
    }
    
    /**
     * 重置监控数据（开始新的推流会话时）
     */
    private fun resetMonitoringData() {
        currentBitrate = 0L
        lastBitrateUpdateTime = 0L
        zeroBitrateCount = 0
        bitrateHistory.clear()
        lastAnomalyTime = 0L
        lastAnomalyType = ""
        
        // 重置异常状态跟踪
        connectionStuckFirstDetected = 0L
        lowBitrateFirstDetected = 0L
        bitrateFluctuationFirstDetected = 0L
        anomalyDebounceMap.clear()
        
        // 注意：不重置 totalChecks、effectiveChecks、skippedChecks，这些是累计值
    }
    
    /**
     * 重置所有计数器（溢出保护）
     */
    private fun resetCounters() {
        totalChecks = 0L
        effectiveChecks = 0L
        skippedChecks = 0L
        anomaliesDetected = 0L
        startTime = System.currentTimeMillis()
        Log.i(TAG, "All counters reset due to overflow protection")
    }
    
    /**
     * 计算方差
     */
    private fun calculateVariance(values: List<Long>): Double {
        if (values.isEmpty()) return 0.0
        
        val mean = values.average()
        val sumOfSquaredDifferences = values.sumOf { (it - mean) * (it - mean) }
        return sumOfSquaredDifferences / values.size
    }
    
    /**
     * 获取当前配置
     */
    fun getCurrentConfig(): WatchdogConfig = config
    
    /**
     * Watchdog 是否正在运行
     */
    fun isWatchdogRunning(): Boolean = watchdogJob?.isActive == true
}
