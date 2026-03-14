# ScrcpyHostForAndroid

`ScrcpyHostForAndroid` 是一个运行在 Android 设备上的远程控制宿主应用。

它把 `scrcpy-server` 和适配 Android/Termux 场景的 `adb` 一起打进 APK，在另一台 Android 设备已开启网络 ADB 的前提下，可以直接从手机或平板发起连接、启动 `scrcpy-server`、拉取视频流并把触控/按键事件回传到目标设备。

这个项目适合下面两类场景：

- 用一台 Android 设备直接控制另一台 Android 设备
- 用 Android 客户端配合桌面侧桥接服务，远程发起 `adb connect` 和 scrcpy 会话

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

目前项目有两种后端模式：

### 1. 本地桥接模式

后端地址填写 `local://bridge`。

这种模式下，应用直接使用 APK 内置的 Android 版 `adb`，不依赖电脑。应用会在本机启动本地 ADB 服务，然后连接目标设备并拉起 scrcpy 会话。

### 2. HTTP 桥接模式

后端地址填写成一个 HTTP 服务地址，例如 `http://192.168.1.20:8765`。

这种模式下，Android 客户端只负责发起 HTTP 请求，真正执行 `adb connect`、推送 `scrcpy-server` 和建立会话的是桌面侧桥接服务。仓库里已经提供了一个参考实现脚本 `scripts/adb_bridge_server.py`。

## 使用说明

### 使用前提

- 宿主设备：安装本应用的 Android 手机或平板，最低支持 Android 6.0
- 目标设备：需要被控制的 Android 设备，需开启开发者选项和 USB 调试，并已经具备网络 ADB 连接条件
- 两台设备最好在同一局域网内
- 如果使用 HTTP 桥接模式，还需要一台能运行 Python 和桌面版 `adb` 的电脑

### 用法一：直接在 Android 端使用内置桥接

1. 从 Releases 安装 APK。
2. 在目标设备上开启 USB 调试，并确保可以通过 `IP:PORT` 访问 ADB。
3. 打开应用。
4. 填写目标设备的 IP 和 ADB 端口，例如 `192.168.1.88` 和 `5555`。
5. 后端地址保留为 `local://bridge`。
6. 点击“连接并进入远程页”。

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
ADB 后端地址: local://bridge
```

### 用法二：使用桌面桥接服务

先在电脑上准备运行环境：

```bash
git clone --recurse-submodules https://github.com/XiaoTong6666/ScrcpyHostForAndroid.git
cd ScrcpyHostForAndroid
./gradlew assembleHostRuntimeDebug
./gradlew runAdbBridge -PbridgeHost=0.0.0.0 -PbridgePort=8765
```

然后在 Android 客户端中填写：

```text
设备 IP: 192.168.1.88
ADB 端口: 5555
ADB 后端地址: http://192.168.1.20:8765
```

其中 `192.168.1.20` 是运行桥接服务的电脑 IP。

### 其他命令示例

仅构建桌面侧 `adb`：

```bash
./gradlew assembleHostAdb
```

连接远程 ADB 设备：

```bash
./gradlew connectRemoteAdb -Ptarget=192.168.1.88:5555
```

手动推送并启动远程 `scrcpy-server`：

```bash
./gradlew launchRemoteScrcpyServer -Ptarget=192.168.1.88:5555
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

项目在构建 APK 时会自动做几件事：

- 编译适配 Android 的 `adb`
- 打包 `scrcpy-server`
- 将运行时文件放入 APK assets

如果缺少子模块或 NDK/CMake/Rust 环境，构建会失败。

## 上游开源项目分析

从当前仓库实际引用和打包方式看，这个项目的上游依赖基本都是宽松许可证，适合继续以宽松协议开源。

- `scrcpy` 顶层协议是 `Apache-2.0`，它是这个项目最核心的上游之一。
- `android-tools` 顶层协议也是 `Apache-2.0`，当前工程主要用它来构建 `adb`。
- `SDL` 使用 `zlib` 协议。
- `abseil-cpp` 使用 `Apache-2.0`。
- `brotli` 使用 `MIT`。
- `protobuf` 和 `zstd` 使用 `BSD-3-Clause`。
- `pcre2` 使用 `BSD-3-Clause WITH PCRE2-exception`。
- `lz4` 仓库是混合许可，但它的 `lib/` 部分是 `BSD-2-Clause`，而当前工程使用的是库部分而不是其 GPL 工具链部分。

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
- `android-tools/`
- `SDL/`
- `android-deps/`

## 代码参考

本项目不是从零实现 scrcpy 协议栈，而是在多个上游项目基础上做组合与适配。主要参考和依赖的子模块仓库如下：

- `scrcpy`：<https://github.com/Genymobile/scrcpy>
- `android-tools`：<https://github.com/XiaoTong6666/android-tools>
- `SDL`：<https://github.com/libsdl-org/SDL/>
- `abseil-cpp`：<https://github.com/abseil/abseil-cpp>
- `brotli`：<https://github.com/google/brotli>
- `lz4`：<https://github.com/lz4/lz4>
- `pcre2`：<https://github.com/PCRE2Project/pcre2>
- `protobuf`：<https://github.com/protocolbuffers/protobuf>
- `zstd`：<https://github.com/facebook/zstd>

另外，仓库中的 `patches/` 目录包含了针对 `scrcpy` 和 `android-tools` 的本地补丁，用于适配当前工程的 Android 构建和运行方式。
