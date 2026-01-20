package com.example.camera_rtmp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.camera_rtmp.data.StreamState

@Composable
fun StreamControls(
    streamState: StreamState,
    isMuted: Boolean,
    isFlashOn: Boolean,
    onStartPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleFlash: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 顶部控制栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 设置按钮
            ControlButton(
                icon = Icons.Default.Settings,
                contentDescription = "设置",
                onClick = onOpenSettings,
                enabled = streamState !is StreamState.Streaming
            )
            
            // 状态指示器
            StreamStatusIndicator(streamState = streamState)
            
            // 切换摄像头
            ControlButton(
                icon = Icons.Default.Cameraswitch,
                contentDescription = "切换摄像头",
                onClick = onSwitchCamera,
                enabled = streamState is StreamState.Previewing || streamState is StreamState.Streaming
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        // 底部控制栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 静音按钮
            ControlButton(
                icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = if (isMuted) "取消静音" else "静音",
                onClick = onToggleMute,
                isActive = isMuted,
                activeColor = Color.Red
            )
            
            // 主控制按钮（开始/停止推流）
            MainStreamButton(
                streamState = streamState,
                onStartPreview = onStartPreview,
                onStopPreview = onStopPreview,
                onStartStream = onStartStream,
                onStopStream = onStopStream
            )
            
            // 闪光灯按钮
            ControlButton(
                icon = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = if (isFlashOn) "关闭闪光灯" else "打开闪光灯",
                onClick = onToggleFlash,
                isActive = isFlashOn,
                activeColor = Color.Yellow
            )
        }
    }
}

@Composable
fun ControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isActive: Boolean = false,
    activeColor: Color = MaterialTheme.colorScheme.primary
) {
    val backgroundColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color.Gray.copy(alpha = 0.3f)
            isActive -> activeColor.copy(alpha = 0.8f)
            else -> Color.White.copy(alpha = 0.2f)
        },
        label = "backgroundColor"
    )
    
    val iconColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color.Gray
            isActive -> Color.White
            else -> Color.White
        },
        label = "iconColor"
    )
    
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(backgroundColor)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = iconColor
        )
    }
}

@Composable
fun MainStreamButton(
    streamState: StreamState,
    onStartPreview: () -> Unit,
    onStopPreview: () -> Unit,
    onStartStream: () -> Unit,
    onStopStream: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isStreaming = streamState is StreamState.Streaming
    val isPreviewing = streamState is StreamState.Previewing
    val isConnecting = streamState is StreamState.Connecting || streamState is StreamState.Reconnecting
    val isIdle = streamState is StreamState.Idle
    
    val buttonColor by animateColorAsState(
        targetValue = when {
            isStreaming -> Color.Red
            isConnecting -> Color.Yellow
            isPreviewing -> Color.Green
            else -> MaterialTheme.colorScheme.primary
        },
        label = "buttonColor"
    )
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FloatingActionButton(
            onClick = {
                when {
                    isStreaming -> onStopStream()
                    isConnecting -> onStopStream()
                    isPreviewing -> onStartStream()
                    isIdle -> onStartPreview()
                    streamState is StreamState.Error -> onStartPreview()
                    else -> {}
                }
            },
            modifier = modifier.size(72.dp),
            containerColor = buttonColor,
            contentColor = Color.White
        ) {
            Icon(
                imageVector = when {
                    isStreaming -> Icons.Default.Stop
                    isConnecting -> Icons.Default.HourglassTop
                    isPreviewing -> Icons.Default.PlayArrow
                    else -> Icons.Default.Videocam
                },
                contentDescription = when {
                    isStreaming -> "停止推流"
                    isConnecting -> "连接中"
                    isPreviewing -> "开始推流"
                    else -> "开始预览"
                },
                modifier = Modifier.size(36.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = when {
                isStreaming -> "停止推流"
                isConnecting -> "连接中..."
                isPreviewing -> "开始推流"
                isIdle -> "开始预览"
                streamState is StreamState.Reconnecting -> "重连中..."
                streamState is StreamState.Error -> "重试"
                else -> "准备中..."
            },
            style = MaterialTheme.typography.labelMedium,
            color = Color.White
        )
    }
}

@Composable
fun StreamStatusIndicator(
    streamState: StreamState,
    modifier: Modifier = Modifier
) {
    val (statusText, statusColor) = when (streamState) {
        is StreamState.Idle -> "待机" to Color.Gray
        is StreamState.Preparing -> "准备中" to Color.Yellow
        is StreamState.Previewing -> "预览中" to Color.Green
        is StreamState.Connecting -> "连接中" to Color.Yellow
        is StreamState.Streaming -> "推流中" to Color.Red
        is StreamState.Reconnecting -> "重连中" to Color.Orange
        is StreamState.Error -> "错误" to Color.Red
    }
    
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White
        )
        
        // 显示码率（如果正在推流）
        if (streamState is StreamState.Streaming && streamState.bitrate > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "${streamState.bitrate / 1000} kbps",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

private val Color.Companion.Orange: Color
    get() = Color(0xFFFF9800)
