# 开发指南

## 1. 克隆仓库

```bash
git clone https://github.com/Johnny520/wcx.git --recursive
cd wcx
```

## 2. 环境要求

当前项目使用:

| 依赖 | 版本或要求 |
| ----------- | --------------------------------- |
| JDK | 21 |
| Android SDK | compile SDK 37, target SDK 37 |
| Android NDK | `30.0.14904198` |
| Rust | 支持 Rust 2024 edition 的 stable 工具链 |
| adb | 安装 APK 或刷入 Zygisk ZIP 时需要 |

### Arch Linux

```bash
yay -Syu jdk21-openjdk rustup
rustup toolchain install stable
rustup default stable
rustup target add aarch64-linux-android armv7-linux-androideabi
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;$(sed -n 's/^ndk = "\(.*\)"/\1/p' gradle/libs.versions.toml)"
```

### Debian 系

JDK 21 和 `rustup` 的包名可能随发行版而异。安装 JDK 21 后, 还需要 Rust Android targets:

```bash
sudo apt update
sudo apt install rustup
rustup toolchain install stable
rustup default stable
rustup target add aarch64-linux-android armv7-linux-androideabi
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;$(sed -n 's/^ndk = "\(.*\)"/\1/p' gradle/libs.versions.toml)"
```

### Windows

建议全文背诵 [停止用 Windows 工作!](https://zhuanlan.fxzhihu.com/p/2024527609388627701)

### Android SDK 路径

`./x` 按以下顺序查找 Android SDK:
1. `ANDROID_HOME`
2. `ANDROID_SDK_ROOT`
3. 仓库根目录 `local.properties` 中的 `sdk.dir`

## 3. `./x`

仓库根目录的 `./x` 等价于 `cargo xtask`, 以下文档统一使用 `./x`:

```sh
#!/usr/bin/env sh
exec cargo xtask "$@"
```

可用的一级命令:

| 命令 | 用途 |
| --------------- | ----------------------------------------------- |
| `./x configure` | 生成 Rust Android linker 配置 |
| `./x build` | 构建 APK, 或仅构建 Rust native 库 |
| `./x run` | 通过 Gradle 安装 APK |
| `./x check` | 对 Rust native 库执行 `cargo check` |
| `./x clippy` | 对 Rust native 库执行 `cargo clippy -- -D warnings` |
| `./x zygisk` | 配置、构建、打包、安装或清理 Zygisk 模块 |

使用 `./x --help` 或 `./x <命令> --help` 查看当前支持的参数。

### Rust Android 配置

```bash
./x configure
```

该命令从已安装的 NDK 中选择版本号最高且主版本 >= 29 的版本, 为两种 ARM ABI 生成:

```none
app/src/main/rust/wekit-native/.cargo/config.toml
```

完整 APK 模式的 `./x build` 和 `./x run` 会自动执行该步骤。

## 4. APK

### 变体

模块通过 `entrypoint` flavor 提供两个变体:
* **standard**: 包含现代 libxposed API 入口 (`entry/lxp/*` 与 `META-INF/xposed/*`)。支持 API 101/102 双向兼容。大多数用户应使用此变体。
* **legacy**: 不包含 libxposed 入口和相关元数据, 使框架回退到传统 `de.robv.android.xposed` API (`Xp51HookEntry` 与 `assets/xposed_init`)。

两个变体使用同一个 `applicationId`, 不能同时安装。

### 构建

```bash
# 两个 flavor 的 debug APK
./x build
# 两个 flavor 的 release APK
./x build --release
# 只构建一个 flavor
./x build --flavor standard
./x build --flavor legacy --release
# 只构建 Rust native 库, 跳过 Gradle
./x configure
./x build --native-only
./x build --native-only --abi arm64-v8a
```

完整 APK 构建依次执行:
1. `./x configure`
2. 为所选 ABI 编译 release 模式的 `libwekit_native.so`
3. 将 native 库复制到 `app/src/main/jniLibs/<abi>/`
4. 执行对应的 Gradle `assemble` 任务

Gradle 为每个 flavor 输出一个同时包含 ARM64 和 ARM32 native 库的 universal APK:

```none
app/build/outputs/apk/standard/debug/app-standard-debug.apk
app/build/outputs/apk/legacy/debug/app-legacy-debug.apk
```

### 安装

连接 adb 设备后执行:

```bash
./x run
./x run --debug
./x run --flavor standard --release
./x run --flavor legacy
```

可选: 应用基准配置 (Baseline Profile):

```bash
adb shell cmd package compile -m speed-profile dev.ujhhgtg.wekit
```

## 5. Zygisk 模块

Zygisk 模块使用 standard APK payload, 支持 `arm64-v8a` 和 `armeabi-v7a`。

### 构建 ZIP

```bash
./x zygisk build
./x zygisk build --apk-release --release --force
./x zygisk build --apk-release --debug
./x zygisk build --skip-apk-build
```

模块 ZIP 输出到:

```none
wekit-zygisk/release/WeKit-<versionCode>-git+<commit>-<debug|release>.zip
```

## WCX 二次开发说明

WCX 基于上游 WeKit (https://github.com/Ujhhgtg/WeKit) 进行Fork二次开发, 在保留上游全部功能的基础上, 新增了大量功能与优化。

### 主要开发分支

* `master` — 主分支, 稳定版本
* 功能分支按 `feature/xxx` 命名

### CI/CD

GitHub Actions 自动构建, 推送/PR 到 `master` 时触发。构建产物自动发布到 Release 页面。

CI 仓库地址: https://github.com/Johnny520/wcx/actions

---

# Agent Instructions
This documentation is published with GitBook. GitBook is the documentation platform designed so that both humans and AI agents can read, navigate, and reason over technical content effectively. Learn more at gitbook.com.

## Querying This Documentation
If you need additional information that is not directly available in this page, you can query the documentation dynamically by asking a question.

Perform an HTTP GET request on the current page URL with the `ask` query parameter, and the optional `goal` query parameter:

```
GET https://johnny520.gitbook.io/wcx-docs/development.md?ask=<question>&goal=<endgoal>
```

`ask` is the immediate question: it should be specific, self-contained, and written in natural language.
`goal` is optional and describes the broader end goal you are ultimately trying to accomplish on behalf of the user. GitBook uses it to tailor the answer towards what is most useful for that goal.

The response will contain a direct answer to the question and relevant excerpts and sources from the documentation.

Use this mechanism when the answer is not explicitly present in the current page, you need clarification or additional context, or you want to retrieve related documentation sections.