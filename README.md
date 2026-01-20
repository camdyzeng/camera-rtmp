# Camera RTMP - Android 实时推流应用

一个基于 RootEncoder 库的 Android RTMP 推流应用，支持后台无预览推流和智能重连功能。

## ✨ 特性

- 🎥 **Context 模式推流** - 支持无预览后台推流，页面切换不影响推流
- 📱 **现代化 UI** - 基于 Jetpack Compose 构建的美观界面
- 🔄 **智能重连** - 指数退避算法，网络异常时自动重连
- 🎛️ **丰富设置** - 支持分辨率、码率、编码器等多种参数配置
- 📷 **摄像头控制** - 前后摄像头切换、闪光灯、静音等功能
- 🔧 **资源管理** - 完善的摄像头和编码器资源管理机制

## 📋 系统要求

- Android 7.0 (API 24) 及以上
- 摄像头权限
- 麦克风权限
- 网络权限

## 🚀 快速开始

### 1. 克隆项目

```bash
git clone https://github.com/camdyzeng/camera-rtmp.git
cd camera-rtmp
```

### 2. 编译安装

```bash
# Windows
gradlew.bat assembleDebug

# Linux/macOS
./gradlew assembleDebug
```

### 3. 安装 APK

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

## 📖 使用说明

### 基本使用

1. **启动应用** - 首次启动会请求摄像头和麦克风权限
2. **输入推流地址** - 在主界面输入 RTMP 服务器地址
3. **开始推流** - 点击"开始推流"按钮
4. **控制推流** - 使用界面上的控制按钮进行操作

### 推流控制

- 🎬 **开始/停止推流** - 主要的推流控制
- 🔄 **切换摄像头** - 前置/后置摄像头切换
- 🔇 **静音/取消静音** - 音频开关控制
- 💡 **闪光灯** - 后置摄像头闪光灯控制（仅支持后置）

### 设置选项

进入设置界面可以配置：

#### 视频设置
- **分辨率**: 720p, 1080p, 4K 等
- **帧率**: 15, 24, 30, 60 FPS
- **码率**: 500Kbps - 8Mbps
- **编码器**: H.264, H.265
- **关键帧间隔**: 1-5秒

#### 音频设置
- **码率**: 32-320 Kbps
- **采样率**: 8000-48000 Hz
- **声道**: 单声道/立体声
- **回声消除**: 开启/关闭
- **噪声抑制**: 开启/关闭

#### 摄像头设置
- **默认摄像头**: 前置/后置
- **自动对焦**: 开启/关闭

## 🏗️ 技术架构

### 核心技术栈

- **Kotlin** - 主要开发语言
- **Jetpack Compose** - 现代化 UI 框架
- **RootEncoder** - RTMP 推流核心库
- **Coroutines** - 异步编程
- **StateFlow** - 响应式状态管理

### 架构设计

```
├── service/
│   └── StreamService.kt          # 推流服务（核心）
├── ui/
│   ├── screens/
│   │   ├── MainScreen.kt         # 主界面
│   │   └── SettingsScreen.kt     # 设置界面
│   └── components/
│       └── StreamControls.kt     # 推流控制组件
├── data/
│   ├── StreamSettings.kt         # 推流设置数据类
│   ├── StreamState.kt           # 推流状态管理
│   └── SettingsRepository.kt    # 设置数据持久化
└── viewmodel/
    └── StreamViewModel.kt        # 视图模型
```

### Context 模式特点

本应用使用 RootEncoder 的 Context 模式，具有以下特点：

- **无需 UI 组件** - 不依赖 OpenGlView/SurfaceView
- **后台推流** - 支持息屏或切换应用后继续推流
- **资源优化** - 更低的内存和 CPU 占用
- **分辨率处理** - 自动处理横竖屏分辨率适配

## 🔧 重连机制

应用实现了智能的指数退避重连算法：

- **基础延迟**: 1秒
- **最大延迟**: 1小时
- **最大重连时间**: 30天
- **随机抖动**: 避免多客户端同时重连

重连延迟序列：1s → 2s → 4s → 8s → 16s → 32s → 1分钟 → 2分钟 → ...

## 📱 界面预览

### 主界面
- RTMP 地址输入
- 推流状态显示
- 实时码率监控
- 推流控制按钮

### 设置界面
- 视频参数配置
- 音频参数配置
- 摄像头选项
- 实时预览设置效果

## 🐛 故障排除

### 常见问题

1. **推流连接失败**
   - 检查网络连接
   - 验证 RTMP 服务器地址
   - 确认服务器支持的编码格式

2. **H.265 推流失败**
   - H.265 需要 Enhanced RTMP 服务器支持
   - 建议使用 H.264 以获得更好的兼容性

3. **权限问题**
   - 确保已授予摄像头和麦克风权限
   - 检查系统设置中的应用权限

4. **后台推流中断**
   - 检查系统电池优化设置
   - 将应用加入白名单

### 日志查看

使用 ADB 查看详细日志：

```bash
adb logcat -s StreamService
```

## 🤝 贡献

欢迎提交 Issue 和 Pull Request！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 打开 Pull Request

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 🙏 致谢

- [RootEncoder](https://github.com/pedroSG94/RootEncoder) - 优秀的 RTMP 推流库
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - 现代化 Android UI 工具包

## 📞 联系方式

如有问题或建议，请通过以下方式联系：

- GitHub Issues: [提交问题](https://github.com/camdyzeng/camera-rtmp/issues)
- Email: [你的邮箱]

---

⭐ 如果这个项目对你有帮助，请给个 Star！