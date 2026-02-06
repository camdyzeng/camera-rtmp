package com.example.camera_rtmp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.rememberNavController
import com.example.camera_rtmp.service.StreamService
import com.example.camera_rtmp.ui.navigation.NavGraph
import com.example.camera_rtmp.ui.theme.CamerartmpTheme
import com.example.camera_rtmp.viewmodel.StreamViewModel

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
    }
    
    private var streamService: StreamService? = null
    private var serviceBound by mutableStateOf(false)
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as StreamService.StreamBinder
            streamService = binder.getService()
            serviceBound = true
            Log.d(TAG, "Service connected")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            streamService = null
            serviceBound = false
            Log.d(TAG, "Service disconnected")
        }
    }
    
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "Notification permission granted: $isGranted")
        // 无论是否授权，都启动服务
        startAndBindService()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // 屏幕常亮由 MainScreen 根据设置动态控制
        // 不再在这里全局设置
        
        // 请求通知权限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                startAndBindService()
            }
        } else {
            startAndBindService()
        }
        
        // 请求忽略电池优化
        requestIgnoreBatteryOptimization()
        
        setContent {
            CamerartmpTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val viewModel: StreamViewModel = viewModel()
                    
                    NavGraph(
                        navController = navController,
                        viewModel = viewModel,
                        streamService = if (serviceBound) streamService else null
                    )
                }
            }
        }
    }
    
    private fun startAndBindService() {
        Log.d(TAG, "Starting and binding service")
        // 启动前台服务
        StreamService.startService(this)
        
        // 绑定服务
        Intent(this, StreamService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }
    
    /**
     * 请求忽略电池优化
     * 这对于长时间后台推流非常重要，特别是在华为/荣耀等手机上
     */
    private fun requestIgnoreBatteryOptimization() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                val packageName = packageName
                
                if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    Log.d(TAG, "Requesting to ignore battery optimizations")
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    
                    // 检查是否有应用可以处理这个 Intent
                    if (intent.resolveActivity(packageManager) != null) {
                        startActivity(intent)
                    } else {
                        Log.w(TAG, "No app can handle battery optimization request")
                    }
                } else {
                    Log.d(TAG, "Already ignoring battery optimizations")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting battery optimization ignore", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy, isStreaming=${streamService?.isStreaming()}")
        
        // 如果不在推流，解绑并停止服务
        if (serviceBound) {
            if (streamService?.isStreaming() != true) {
                Log.d(TAG, "Not streaming, stopping service")
                unbindService(serviceConnection)
                StreamService.stopService(this)
            } else {
                // 推流中只解绑，不停止服务（服务继续在后台运行）
                Log.d(TAG, "Streaming in progress, keeping service alive")
                unbindService(serviceConnection)
            }
        }
    }
    
    override fun onStop() {
        super.onStop()
        // Activity 进入后台时，如果正在推流，保持服务运行
        Log.d(TAG, "onStop, isStreaming=${streamService?.isStreaming()}")
    }
    
    override fun onStart() {
        super.onStart()
        // Activity 回到前台时，重新绑定服务（如果之前解绑了）
        if (!serviceBound) {
            Log.d(TAG, "onStart, rebinding service")
            Intent(this, StreamService::class.java).also { intent ->
                bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
    }
}
