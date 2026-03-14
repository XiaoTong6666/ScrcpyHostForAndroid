# ScrcpyHostForAndroid

`ScrcpyHostForAndroid` 是一个运行在 Android 设备上的远程控制宿主应用。

它把 `scrcpy-server` 和适配 Android/Termux 场景的 `adb` 一起打进 APK，在另一台 Android 设备已开启网络 ADB 的前提下，可以直接从手机或平板发起连接、启动 `scrcpy-server`、拉取视频流并把触控/按键事件回传到目标设备。

这个项目适合用一台 Android 设备直接控制另一台 Android 设备。

## 下载

安装包请直接从 GitHub Releases 获取：

- <https://github.com/XiaoTong6666/ScrcpyHostForAndroid/releases>

如果你只是想安装使用，优先下载 Releases 中提供的 APK，不需要自己编译。

## 项目说明

这个项目的核心能力包括：

- 在 APK 中内置 Android 版 `adb`
- 在 APK 中内置 `scrcpy-server`
- 通过网络 ADB 连接目标设备
- 自动推送并启动 `scrcpy-server`
- 在应用内解码并显示 scrcpy 视频流
- 通过 scrcpy 控制通道回传触控、按键等输入事件
- 控制通道失败时回退到纯视频模式，保证基本可用

从实现方式上看，它不是“在 Android 上直接运行桌面版 scrcpy”，而是把几个能力拼起来：

- 内置 `adb` 负责建立到目标设备的调试连接、端口转发、推送 `scrcpy-server`
- `scrcpy-server` 运行在目标设备上，负责采集屏幕和接收控制命令
- Android 客户端自己负责视频解码、显示和输入事件封包
- SDL 负责远程显示页的渲染宿主和 Native/JNI 桥

## 项目结构

当前仓库最重要的目录如下：

- `app/`：Android 客户端主模块，包含 Compose 首页、远程显示页、内置 ADB 桥接、视频解码、控制通道等代码
- `app/src/main/jni/`：SDL/JNI Native 入口，负责把 Java/Kotlin 侧解码出的帧桥接到 SDL 渲染层
- `sdl-android-java/`：SDL Android Java 封装模块
- `scrcpy/`：`scrcpy` 上游源码子模块，当前主要消费其中的 `server/`
- `patches/scrcpy/`：对 `scrcpy/server` 的本地补丁
- `scripts/`：辅助脚本
- `.github/workflows/`：主仓库 CI，负责构建 APK 并发布 nightly

构建产物和运行时 staging 主要落在：

- `build/outputs/host-tools/scrcpy/scrcpy-server`：主仓库 staging 后的 `scrcpy-server`

## 源码结构分析

按运行链路看，代码可以分成 5 层。

### 1. 连接入口和参数输入

- [MainActivity.kt](app/src/main/java/io/github/xiaotong6666/scrcpy/MainActivity.kt) 是首页入口
- 首页允许输入目标设备 `host:port` 和后端地址
- 当前默认通过应用内置桥接直接建立会话

### 2. ADB / scrcpy 会话编排层

- [BackendBridgeClient.kt](app/src/main/java/io/github/xiaotong6666/scrcpy/BackendBridgeClient.kt) 是统一的桥接客户端抽象
- 它把“请求 adb connect / 启动 scrcpy / 停止 scrcpy”封装成统一接口
- 当后端是 `local://` 时，调用 [LocalAdbBridge.kt](app/src/main/java/io/github/xiaotong6666/scrcpy/LocalAdbBridge.kt)

本地桥接模式下，[LocalAdbBridge.kt](app/src/main/java/io/github/xiaotong6666/scrcpy/LocalAdbBridge.kt) 会负责：

- 确保 APK assets 中的 adb 和 `scrcpy-server` 已经落到可执行位置
- 启动 adb server
- `adb connect` 到目标设备
- `adb push` `scrcpy-server.jar`
- `adb forward tcp:... localabstract:...`
- 通过 `adb shell app_process` 拉起 `com.genymobile.scrcpy.Server`

### 3. 视频流接收和解码层

- [RemoteDisplayActivity.kt](app/src/main/java/io/github/xiaotong6666/scrcpy/RemoteDisplayActivity.kt) 是远程显示页核心
- 它在会话成功后打开视频 socket、控制 socket，并管理 Surface / overlay / fallback
- [ScrcpyVideoStreamClient.kt](app/src/main/java/io/github/xiaotong6666/scrcpy/ScrcpyVideoStreamClient.kt) 负责：
  - 读取 scrcpy 视频流头
  - 创建 `MediaCodec`
  - 处理帧队列、渲染节奏、统计信息
  - 把输出帧送往 Surface

### 4. 控制通道和输入注入层

- [ScrcpyControlClient.kt](app/src/main/java/io/github/xiaotong6666/scrcpy/ScrcpyControlClient.kt) 负责 scrcpy control socket 的连接和封包写入
- [ScrcpyInputController.kt](app/src/main/java/io/github/xiaotong6666/scrcpy/ScrcpyInputController.kt) 负责把触摸、滚轮、按键事件转换成 scrcpy control protocol 数据包
- 当前实现支持按键、触摸、滚动，并在控制链失败时回退到视频-only 会话

### 5. SDL / JNI 桥接层

- [SdlSessionBridge.kt](app/src/main/java/io/github/xiaotong6666/scrcpy/SdlSessionBridge.kt) 暴露 JNI 接口
- [remote_main.c](app/src/main/jni/src/remote_main.c) 是 Native 端主入口
- 这层负责：
  - 保存当前会话元数据
  - 接收 Java 侧提交的 RGBA 解码帧
  - 用 SDL 纹理渲染远程画面
  - 在无帧或等待阶段显示 placeholder

从职责划分上看，这个项目不是单纯的 UI 包装，而是已经把 `adb` 会话管理、scrcpy 会话编排、Android 硬解码、输入封包、SDL 渲染都收进了同一个 Android 客户端里。

## 使用说明

### 使用前提

- 宿主设备：安装本应用的 Android 手机或平板，最低支持 Android 6.0
- 目标设备：需要被控制的 Android 设备，需开启开发者选项和 USB 调试，并已经具备网络 ADB 连接条件
- 两台设备最好在同一局域网内

1. 从 Releases 安装 APK。
2. 在目标设备上开启 USB 调试，并确保可以通过 `IP:PORT` 访问 ADB。
3. 打开应用。
4. 填写目标设备的 IP 和 ADB 端口，例如 `192.168.1.88` 和 `5555`。
5. 点击“连接并进入远程页”。

应用会自动完成下面这些动作：

- 启动内置 ADB 服务
- 执行 `adb connect`
- 推送 `scrcpy-server` 到目标设备
- 启动 scrcpy 会话
- 进入远程显示页面

示例：

```text
设备 IP: 192.168.1.88
ADB 端口: 5555
```

构建可安装 APK：

```bash
git submodule update --init --recursive
./gradlew assembleRelease
```

## 源码构建说明

如果你要从源码完整构建，建议提前准备：

- Android Studio
- Android SDK
- Android NDK
- CMake
- Rust / Cargo
- Git 子模块

主仓库只负责 Android 客户端、`scrcpy-server` 打包和 APK 组装。构建前需要准备好 Android SDK/NDK、Rust/Cargo 以及必要子模块。

内置 `adb` 的产物来自独立的 `android-tools` 仓库。主仓库通过 Gradle 任务 `downloadNightlyAdb` 拉取 nightly 构建，并在打包 APK 时把这些二进制作为 assets 一起带上。这个任务会在需要时由构建链自动触发；如果你只想单独预取内置 `adb`，也可以手动执行：

```bash
./gradlew downloadNightlyAdb
```

## 构建与发布链路

### 本地构建

推荐顺序：

```bash
git submodule update --init --recursive
./gradlew :app:assembleDebug :app:assembleRelease
```

这条链路已经按下面顺序拆开：

1. 在需要时拉取内置 `adb` 的 nightly 产物
2. `stageScrcpyServerBinary` 打包并 staging `scrcpy-server`
3. `app` 模块把运行时依赖放入 assets
4. 生成最终 APK

### GitHub Actions

主仓库 CI 在 [.github/workflows/android.yml](https://github.com/XiaoTong6666/ScrcpyHostForAndroid/actions/workflows/android.yml)，用于构建 APK 并发布 nightly 预发布版本。

## 上游开源项目分析

从当前仓库实际引用和打包方式看，这个项目的上游依赖基本都是宽松许可证，适合继续以宽松协议开源。

- `scrcpy` 顶层协议是 `Apache-2.0`，它是这个项目最核心的上游之一。
- `SDL` 使用 `zlib` 协议。
- 内置 `adb` 来自单独的 `android-tools` 仓库：<https://github.com/XiaoTong6666/android-tools>。相关许可证还是按那个仓库和它依赖里的说明来。

基于这些上游组成，当前仓库没有明显必须整体切换到强 copyleft 协议的信号。为了和核心上游保持一致，也为了把专利授权和再分发条款写清楚，这个项目适合使用 `Apache License 2.0` 作为顶层协议。

## 开源协议

本仓库顶层自有代码现在采用 `Apache License 2.0`：

- 协议文件：`LICENSE`
- 第三方许可证说明：`THIRD_PARTY_NOTICES.md`

协议范围主要覆盖本仓库自行编写的内容，例如：

- `app/`
- `scripts/`
- `patches/`
- 顶层 Gradle 配置
- 本仓库文档

以下子模块和第三方源码目录仍然保持它们各自原始许可证，不会因为顶层 `LICENSE` 被重新授权：

- `scrcpy/`
- `SDL/`
- 单独仓库提供并打包进来的 `adb` 以及相关第三方代码

## 代码参考

本项目不是从零实现 scrcpy 协议栈，而是在多个上游项目基础上做组合与适配。主要参考和依赖的子模块仓库如下：

- `scrcpy`：<https://github.com/Genymobile/scrcpy>
- `SDL`：<https://github.com/libsdl-org/SDL/>
- `android-tools`：<https://github.com/XiaoTong6666/android-tools>

另外，仓库中的 `patches/` 目录包含了针对上游源码的本地补丁，用于适配当前工程的 Android 构建和运行方式。
