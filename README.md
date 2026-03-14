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
./android-tools/scripts/build-host-adb.sh
./gradlew stageScrcpyServerBinary
python3 scripts/adb_bridge_server.py --host 0.0.0.0 --port 8765
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
./android-tools/scripts/build-host-adb.sh
```

连接远程 ADB 设备：

```bash
./android-tools/scripts/build-host-adb.sh
./scripts/connect_remote_adb.sh 192.168.1.88:5555
```

手动推送并启动远程 `scrcpy-server`：

```bash
./android-tools/scripts/build-host-adb.sh
./gradlew stageScrcpyServerBinary
./scripts/run_remote_scrcpy_server.sh 192.168.1.88:5555
```

构建可安装 APK：

```bash
git submodule update --init --recursive
./gradlew downloadNightlyAdb
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

项目把 `adb` 的实际编译完全放在 `android-tools/` 子仓库里，主仓库不再触发 `adb ELF` 构建。

构建 APK 前，推荐先直接拉取 `android-tools` 的 `nightly` release：

```bash
./gradlew downloadNightlyAdb
```

这个任务会做三件事：

- 从 `XiaoTong6666/android-tools` 的 `nightly` tag 对应 release 下载 `adb-arm64-v8a` 和 `adb-armeabi-v7a`
- 下载同一份 release 里的 `SHA256SUMS` 和 `nightly.json`
- 校验 SHA-256 后再写入 `android-tools/out/termux-adb/<abi>/adb`

如果你不想走 release，也可以继续手动本地编译：

```bash
./android-tools/scripts/build-termux-adb.sh arm64-v8a
./android-tools/scripts/build-termux-adb.sh armeabi-v7a
```

然后主仓库构建只会做几件事：

- 打包 `scrcpy-server`
- 将 `android-tools/out/termux-adb` 下已有的 `adb` 二进制放入 APK assets

其中 `adb` 的构建职责都在 `android-tools/` 内部：

- `android-tools/scripts/build-host-adb.sh` 负责桌面侧 `adb`
- `android-tools/scripts/build-termux-adb.sh ABI` 负责 Android/Termux `adb`
- 主仓库只消费这些预编译产物，不再直接编排 `adb` 的 CMake/补丁细节，也不会自动调用这些脚本

如果缺少预编译 `adb`、子模块或 NDK/CMake/Rust 环境，构建会失败。

## 上游开源项目分析

从当前仓库实际引用和打包方式看，这个项目的上游依赖基本都是宽松许可证，适合继续以宽松协议开源。

- `scrcpy` 顶层协议是 `Apache-2.0`，它是这个项目最核心的上游之一。
- `android-tools` 顶层协议也是 `Apache-2.0`，当前工程主要用它来构建 `adb`。
- `SDL` 使用 `zlib` 协议。
- `android-tools` 的依赖链里还会用到 `abseil-cpp`、`brotli`、`lz4`、`pcre2`、`protobuf`、`zstd` 等宽松许可组件，但它们已经不再作为主仓库子模块存在。

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

## 代码参考

本项目不是从零实现 scrcpy 协议栈，而是在多个上游项目基础上做组合与适配。主要参考和依赖的子模块仓库如下：

- `scrcpy`：<https://github.com/Genymobile/scrcpy>
- `android-tools`：<https://github.com/XiaoTong6666/android-tools>
- `SDL`：<https://github.com/libsdl-org/SDL/>

`android-tools` 内部仍会继续消费这些上游项目，但它们现在属于 `android-tools` 的构建依赖，不再作为主仓库一级子模块存在。

另外，仓库中的 `patches/` 目录包含了针对 `scrcpy` 和 `android-tools` 的本地补丁，用于适配当前工程的 Android 构建和运行方式。
