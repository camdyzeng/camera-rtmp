package com.example.camera_rtmp.service

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * 简化的流状态管理器
 * 
 * 只有两个状态：
 * - STOPPED: 停止状态（初始状态，用户主动停止）
 * - RUNNING: 运行状态（包括连接中、推流中、重连中）
 */
class SimpleStateManager {
    
    companion object {
        private const val TAG = "SimpleStateManager"
    }
    
    /**
     * 简化的流状态枚举
     */
    enum class StreamStatus {
        STOPPED,  // 停止状态
        RUNNING   // 运行状态
    }
    
    private val status = AtomicReference(StreamStatus.STOPPED)
    
    /**
     * 设置为运行状态
     */
    fun setRunning() {
        status.set(StreamStatus.RUNNING)
        Log.d(TAG, "State -> RUNNING")
    }
    
    /**
     * 设置为停止状态
     */
    fun setStopped() {
        status.set(StreamStatus.STOPPED)
        Log.d(TAG, "State -> STOPPED")
    }
    
    /**
     * 检查当前是否为运行状态
     */
    fun isRunning(): Boolean = status.get() == StreamStatus.RUNNING
    
    /**
     * 检查当前是否为停止状态
     */
    fun isStopped(): Boolean = status.get() == StreamStatus.STOPPED
}
