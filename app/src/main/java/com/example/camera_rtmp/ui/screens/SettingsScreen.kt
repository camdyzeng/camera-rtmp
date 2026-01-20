package com.example.camera_rtmp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.camera_rtmp.data.*
import com.example.camera_rtmp.viewmodel.StreamViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: StreamViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    var currentSettings by remember(settings) { mutableStateOf(settings) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("推流设置") },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.saveSettings(currentSettings)
                        onNavigateBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.saveSettings(currentSettings)
                            onNavigateBack()
                        }
                    ) {
                        Text("保存")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // RTMP 设置
            item {
                SettingsSection(title = "服务器设置", icon = Icons.Default.Cloud) {
                    OutlinedTextField(
                        value = currentSettings.rtmpUrl,
                        onValueChange = { currentSettings = currentSettings.copy(rtmpUrl = it) },
                        label = { Text("RTMP 地址") },
                        placeholder = { Text("rtmp://server/live/key") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            
            // 视频设置
            item {
                SettingsSection(title = "视频设置", icon = Icons.Default.Videocam) {
                    // 分辨率选择
                    DropdownSettingItem(
                        title = "分辨率",
                        selectedValue = "${currentSettings.videoWidth}x${currentSettings.videoHeight}",
                        options = VideoResolution.entries.map { it.displayName },
                        onOptionSelected = { index ->
                            val resolution = VideoResolution.entries[index]
                            currentSettings = currentSettings.copy(
                                videoWidth = resolution.width,
                                videoHeight = resolution.height
                            )
                        }
                    )
                    
                    // 码率选择
                    DropdownSettingItem(
                        title = "码率",
                        selectedValue = "${currentSettings.videoBitrate} kbps",
                        options = VideoBitratePreset.entries.map { it.displayName },
                        onOptionSelected = { index ->
                            currentSettings = currentSettings.copy(
                                videoBitrate = VideoBitratePreset.entries[index].bitrate
                            )
                        }
                    )
                    
                    // 帧率选择
                    DropdownSettingItem(
                        title = "帧率",
                        selectedValue = "${currentSettings.videoFps} FPS",
                        options = VideoFpsPreset.entries.map { it.displayName },
                        onOptionSelected = { index ->
                            currentSettings = currentSettings.copy(
                                videoFps = VideoFpsPreset.entries[index].fps
                            )
                        }
                    )
                    
                    // 编码器选择
                    DropdownSettingItem(
                        title = "视频编码",
                        selectedValue = currentSettings.videoCodec.displayName,
                        options = VideoCodec.entries.map { it.displayName },
                        onOptionSelected = { index ->
                            currentSettings = currentSettings.copy(
                                videoCodec = VideoCodec.entries[index]
                            )
                        }
                    )
                    
                    // 视频旋转方向选择
                    DropdownSettingItem(
                        title = "视频旋转",
                        selectedValue = currentSettings.videoRotation.displayName,
                        options = VideoRotation.entries.map { it.displayName },
                        onOptionSelected = { index ->
                            currentSettings = currentSettings.copy(
                                videoRotation = VideoRotation.entries[index]
                            )
                        }
                    )
                    
                    // 关键帧间隔
                    SliderSettingItem(
                        title = "关键帧间隔",
                        value = currentSettings.iFrameInterval.toFloat(),
                        valueRange = 1f..5f,
                        steps = 3,
                        valueText = "${currentSettings.iFrameInterval} 秒",
                        onValueChange = { currentSettings = currentSettings.copy(iFrameInterval = it.toInt()) }
                    )
                }
            }
            
            // 音频设置
            item {
                SettingsSection(title = "音频设置", icon = Icons.Default.Audiotrack) {
                    // 采样率
                    DropdownSettingItem(
                        title = "采样率",
                        selectedValue = "${currentSettings.audioSampleRate} Hz",
                        options = AudioSampleRatePreset.entries.map { it.displayName },
                        onOptionSelected = { index ->
                            currentSettings = currentSettings.copy(
                                audioSampleRate = AudioSampleRatePreset.entries[index].sampleRate
                            )
                        }
                    )
                    
                    // 音频码率
                    DropdownSettingItem(
                        title = "音频码率",
                        selectedValue = "${currentSettings.audioBitrate} kbps",
                        options = AudioBitratePreset.entries.map { it.displayName },
                        onOptionSelected = { index ->
                            currentSettings = currentSettings.copy(
                                audioBitrate = AudioBitratePreset.entries[index].bitrate
                            )
                        }
                    )
                    
                    // 立体声
                    SwitchSettingItem(
                        title = "立体声",
                        subtitle = "使用双声道录音",
                        checked = currentSettings.audioIsStereo,
                        onCheckedChange = { currentSettings = currentSettings.copy(audioIsStereo = it) }
                    )
                    
                    // 回声消除
                    SwitchSettingItem(
                        title = "回声消除",
                        subtitle = "减少回声干扰",
                        checked = currentSettings.audioEchoCanceler,
                        onCheckedChange = { currentSettings = currentSettings.copy(audioEchoCanceler = it) }
                    )
                    
                    // 噪声抑制
                    SwitchSettingItem(
                        title = "噪声抑制",
                        subtitle = "减少环境噪音",
                        checked = currentSettings.audioNoiseSuppressor,
                        onCheckedChange = { currentSettings = currentSettings.copy(audioNoiseSuppressor = it) }
                    )
                }
            }
            
            // 摄像头设置
            item {
                SettingsSection(title = "摄像头设置", icon = Icons.Default.CameraAlt) {
                    // 摄像头选择
                    DropdownSettingItem(
                        title = "默认摄像头",
                        selectedValue = currentSettings.cameraFacing.displayName,
                        options = CameraFacing.entries.map { it.displayName },
                        onOptionSelected = { index ->
                            currentSettings = currentSettings.copy(
                                cameraFacing = CameraFacing.entries[index]
                            )
                        }
                    )
                    
                    // 自动对焦
                    SwitchSettingItem(
                        title = "自动对焦",
                        subtitle = "启用自动对焦功能",
                        checked = currentSettings.autoFocus,
                        onCheckedChange = { currentSettings = currentSettings.copy(autoFocus = it) }
                    )
                }
            }
            
            // 滤镜设置 - Context 模式下不支持滤镜
            item {
                SettingsSection(title = "滤镜设置", icon = Icons.Default.FilterVintage) {
                    // Context 模式（无预览后台推流）不支持滤镜功能
                    // 滤镜需要 OpenGlView 才能应用
                    Text(
                        text = "当前使用后台推流模式，不支持滤镜功能。\n如需使用滤镜，请切换到预览模式。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // 其他设置
            item {
                SettingsSection(title = "其他设置", icon = Icons.Default.Settings) {
                    SwitchSettingItem(
                        title = "保持屏幕常亮",
                        subtitle = "推流时防止屏幕自动关闭",
                        checked = currentSettings.keepScreenOn,
                        onCheckedChange = { currentSettings = currentSettings.copy(keepScreenOn = it) }
                    )
                }
            }
            
            // 底部间距
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}

@Composable
fun SwitchSettingItem(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownSettingItem(
    title: String,
    selectedValue: String,
    options: List<String>,
    onOptionSelected: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        OutlinedTextField(
            value = selectedValue,
            onValueChange = {},
            readOnly = true,
            label = { Text(title) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onOptionSelected(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun SliderSettingItem(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    valueText: String,
    onValueChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = valueText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
