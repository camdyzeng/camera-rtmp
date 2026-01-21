package com.example.camera_rtmp.ui.screens

import android.Manifest
import android.app.Activity
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.camera_rtmp.data.StreamState
import com.example.camera_rtmp.service.StreamService
import com.example.camera_rtmp.viewmodel.StreamViewModel

private const val TAG = "MainScreen"

/**
 * MainScreen - 主界面（Context 模式 - 无预览后台推流）
 * 
 * 架构设计：
 * - 使用 Context 模式，无摄像头预览
 * - 推流完全由 Service 管理
 * - 页面切换不影响推流
 * - 适合后台推流场景
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: StreamViewModel,
    streamService: StreamService?,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    
    val settings by viewModel.settings.collectAsState()
    val rtmpUrl by viewModel.rtmpUrl.collectAsState()
    
    val streamState by streamService?.streamState?.collectAsState() ?: remember { mutableStateOf(StreamState.Idle) }
    val streamStats by streamService?.streamStats?.collectAsState() ?: remember { mutableStateOf(com.example.camera_rtmp.data.StreamStats()) }
    val isMuted by streamService?.isMuted?.collectAsState() ?: remember { mutableStateOf(false) }
    val isFlashOn by streamService?.isFlashOn?.collectAsState() ?: remember { mutableStateOf(false) }
    val currentCameraFacing by streamService?.currentCameraFacing?.collectAsState() ?: remember { mutableStateOf(com.example.camera_rtmp.data.CameraFacing.BACK) }
    
    var hasPermissions by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    
    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.values.all { it }
    }
    
    // 请求权限
    LaunchedEffect(Unit) {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
    
    // 根据设置控制屏幕常亮
    val activity = context as? Activity
    DisposableEffect(settings.keepScreenOn, streamState) {
        if (activity != null) {
            val isStreaming = streamState is StreamState.Streaming || 
                              streamState is StreamState.Connecting ||
                              streamState is StreamState.Reconnecting
            
            if (settings.keepScreenOn && isStreaming) {
                // 推流时保持屏幕常亮
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Log.d(TAG, "Screen keep on: enabled (streaming)")
            } else if (!settings.keepScreenOn) {
                // 用户关闭了屏幕常亮设置
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                Log.d(TAG, "Screen keep on: disabled (user setting)")
            }
        }
        
        onDispose {
            // 离开页面时，如果不在推流则清除屏幕常亮
            if (activity != null) {
                val isStreaming = streamState is StreamState.Streaming || 
                                  streamState is StreamState.Connecting ||
                                  streamState is StreamState.Reconnecting
                if (!isStreaming) {
                    activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    Log.d(TAG, "Screen keep on: cleared on dispose")
                }
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 主内容区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // Context 模式：显示状态信息而不是摄像头预览
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A1A)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    // 状态图标
                    Icon(
                        imageVector = when (streamState) {
                            is StreamState.Streaming -> Icons.Default.Videocam
                            is StreamState.Connecting, is StreamState.Preparing -> Icons.Default.Sync
                            is StreamState.Reconnecting -> Icons.Default.Refresh
                            is StreamState.Error -> Icons.Default.Error
                            else -> Icons.Default.VideocamOff
                        },
                        contentDescription = null,
                        tint = when (streamState) {
                            is StreamState.Streaming -> Color.Red
                            is StreamState.Connecting, is StreamState.Preparing, is StreamState.Reconnecting -> Color.Yellow
                            is StreamState.Error -> Color.Red.copy(alpha = 0.7f)
                            else -> Color.White.copy(alpha = 0.4f)
                        },
                        modifier = Modifier.size(120.dp)
                    )
                    
                    // 状态文字
                    Text(
                        text = when (streamState) {
                            is StreamState.Streaming -> "正在推流"
                            is StreamState.Connecting -> "正在连接..."
                            is StreamState.Preparing -> "准备中..."
                            is StreamState.Reconnecting -> "重连中..."
                            is StreamState.Error -> (streamState as StreamState.Error).message
                            else -> "后台推流模式"
                        },
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // 推流中显示详细信息
                    if (streamState is StreamState.Streaming) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 码率
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Speed,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "${streamStats.bitrate / 1000} kbps",
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 16.sp
                                )
                            }
                            
                            // 时长
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Timer,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = formatDuration(streamStats.streamTime),
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 16.sp
                                )
                            }
                            
                            // 摄像头信息
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CameraFront,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = if (currentCameraFacing == com.example.camera_rtmp.data.CameraFacing.FRONT) "前置摄像头" else "后置摄像头",
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                    
                    // 提示信息
                    if (streamState is StreamState.Idle) {
                        Text(
                            text = "无摄像头预览，推流在后台运行",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
            
            // Top overlay - Status info
            TopStatusBar(
                streamState = streamState,
                rtmpUrl = rtmpUrl,
                streamStats = streamStats,
                onEditUrl = { showUrlDialog = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            )
            
            // Bottom overlay - Controls
            BottomControlBar(
                streamState = streamState,
                isMuted = isMuted,
                isFlashOn = isFlashOn,
                hasPermissions = hasPermissions,
                onStartStop = {
                    if (streamService != null && hasPermissions) {
                        // 任何非 Idle 状态都应该能够停止
                        if (streamState is StreamState.Streaming || 
                            streamState is StreamState.Connecting ||
                            streamState is StreamState.Reconnecting ||
                            streamState is StreamState.Preparing ||
                            streamState is StreamState.Error) {
                            Log.d(TAG, "Stopping streaming (current state: $streamState)")
                            streamService.stopStreaming()
                            StreamService.stopService(context)
                        } else {
                            if (rtmpUrl.isNotEmpty() && rtmpUrl != "rtmp://") {
                                Log.d(TAG, "Starting streaming (Context mode)")
                                viewModel.saveRtmpUrl()
                                StreamService.startService(context)
                                streamService.initializeStreaming(settings)
                                streamService.startStreaming(rtmpUrl)
                            } else {
                                showUrlDialog = true
                            }
                        }
                    } else if (!hasPermissions) {
                        val permissions = mutableListOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO
                        )
                        permissionLauncher.launch(permissions.toTypedArray())
                    }
                },
                onSwitchCamera = { streamService?.switchCamera() },
                onToggleMute = { streamService?.toggleMute() },
                onToggleFlash = { streamService?.toggleFlash() },
                onSettings = onNavigateToSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            )
            
            // No permission overlay
            if (!hasPermissions) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "需要摄像头和麦克风权限",
                            color = Color.White,
                            fontSize = 18.sp
                        )
                        Button(
                            onClick = {
                                val permissions = mutableListOf(
                                    Manifest.permission.CAMERA,
                                    Manifest.permission.RECORD_AUDIO
                                )
                                permissionLauncher.launch(permissions.toTypedArray())
                            }
                        ) {
                            Text("授予权限")
                        }
                    }
                }
            }
        }
    }
    
    // RTMP URL 输入对话框
    if (showUrlDialog) {
        RtmpUrlDialog(
            currentUrl = rtmpUrl,
            onUrlChange = { viewModel.updateRtmpUrl(it) },
            onDismiss = { showUrlDialog = false },
            onConfirm = {
                viewModel.saveRtmpUrl()
                showUrlDialog = false
            }
        )
    }
}

@Composable
private fun TopStatusBar(
    streamState: StreamState,
    rtmpUrl: String,
    streamStats: com.example.camera_rtmp.data.StreamStats,
    onEditUrl: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .statusBarsPadding()
        ) {
            // Status indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Recording indicator
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(
                            when (streamState) {
                                is StreamState.Streaming -> Color.Red
                                is StreamState.Connecting, is StreamState.Preparing -> Color.Yellow
                                is StreamState.Reconnecting -> Color.Yellow
                                is StreamState.Error -> Color.Red.copy(alpha = 0.5f)
                                else -> Color.Gray
                            }
                        )
                )
                
                Text(
                    text = when (streamState) {
                        is StreamState.Streaming -> "正在推流"
                        is StreamState.Connecting -> "正在连接..."
                        is StreamState.Preparing -> "准备中..."
                        is StreamState.Reconnecting -> "重连中..."
                        is StreamState.Error -> "错误"
                        else -> "就绪"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                
                // Context 模式标识
                Surface(
                    color = Color.White.copy(alpha = 0.2f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "后台模式",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Edit URL button
                IconButton(
                    onClick = onEditUrl,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "编辑 RTMP 地址",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            
            // RTMP URL preview
            if (rtmpUrl.isNotEmpty() && rtmpUrl != "rtmp://") {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = rtmpUrl.take(40) + if (rtmpUrl.length > 40) "..." else "",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun BottomControlBar(
    streamState: StreamState,
    isMuted: Boolean,
    isFlashOn: Boolean,
    hasPermissions: Boolean,
    onStartStop: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleFlash: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 是否正在推流（用于控制静音、闪光灯、切换摄像头按钮）
    val isStreaming = streamState is StreamState.Streaming
    
    // 是否处于活动状态（用于显示停止按钮）
    val isActive = streamState is StreamState.Streaming || 
                   streamState is StreamState.Connecting ||
                   streamState is StreamState.Reconnecting ||
                   streamState is StreamState.Preparing ||
                   streamState is StreamState.Error
    
    Surface(
        modifier = modifier,
        color = Color.Black.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            // Control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mute button
                IconButton(
                    onClick = onToggleMute,
                    enabled = isStreaming
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isMuted) "取消静音" else "静音",
                        tint = if (isStreaming) {
                            if (isMuted) Color.Red else Color.White
                        } else Color.Gray
                    )
                }
                
                // Flash button
                IconButton(
                    onClick = onToggleFlash,
                    enabled = isStreaming
                ) {
                    Icon(
                        imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "闪光灯",
                        tint = if (isStreaming) {
                            if (isFlashOn) Color.Yellow else Color.White
                        } else Color.Gray
                    )
                }
                
                // Start/Stop button
                FloatingActionButton(
                    onClick = onStartStop,
                    containerColor = if (isActive) Color.Red else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        imageVector = if (isActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = if (isActive) "停止" else "开始",
                        modifier = Modifier.size(36.dp)
                    )
                }
                
                // Switch camera button
                IconButton(
                    onClick = onSwitchCamera,
                    enabled = isStreaming
                ) {
                    Icon(
                        imageVector = Icons.Default.Cameraswitch,
                        contentDescription = "切换摄像头",
                        tint = if (isStreaming) Color.White else Color.Gray
                    )
                }
                
                // Settings button
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "设置",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun RtmpUrlDialog(
    currentUrl: String,
    onUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var url by remember { mutableStateOf(currentUrl) }
    val focusManager = LocalFocusManager.current
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("设置 RTMP 地址") },
        text = {
            Column {
                Text(
                    text = "请输入 RTMP 推流地址",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { 
                        url = it
                        onUrlChange(it)
                    },
                    label = { Text("RTMP URL") },
                    placeholder = { Text("rtmp://server/live/key") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            focusManager.clearFocus()
                            onConfirm()
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = url.startsWith("rtmp://") && url.length > 10
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    val hours = millis / (1000 * 60 * 60)
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
