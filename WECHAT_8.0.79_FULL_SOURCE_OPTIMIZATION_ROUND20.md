# WCX 全源码审查与 APK 体积优化（第 20 轮）

## 本轮目标

对整个 Android 工程做一轮保守的静态审查，优先处理能够明确确认且低风险的 APK 体积问题；不对未经实机验证的功能作“已修复”承诺。

## 已完成修改

### 1. 解除 Compose 依赖的全量 keep

`app/proguard-rules.pro` 原先使用：

```proguard
-keep class androidx.compose.** { *; }
```

这会阻止 R8 对 Compose 依赖进行常规的不可达代码裁剪和优化。现在移除该全量 keep，依赖各 Compose 库自身的 consumer rules，并保留 `-dontwarn androidx.compose.**`。这属于本轮最直接的 APK 体积优化点。

### 2. 解除 Kotlin Serialization 运行库的全量 keep

移除 `-keep class kotlinx.serialization.** { *; }`。应用自身的生成 serializer、`Companion` 与 `serializer()` 入口仍有专门规则，运行时实际可达的库代码由 R8 保留，未使用的实现有机会被裁剪。

此项需要通过完整 release 构建及 JSON/Proto 功能回归验证；当前构建环境不能下载所需 Gradle 发行包，因此还没有得到 APK 体积前后对比，也不能宣称运行回归已通过。

### 3. 移除对 compileOnly 微信 stub 的无效 keep

微信 API stub 配置为 `compileOnly`，不会作为普通实现依赖打入 APK。移除 `-keep class com.tencent.mm.** { *; }`，避免误导后续维护者认为模块需要打包微信类。

### 4. 清理源码目录中的过期 `.bak_*` 快照

从 `app/src/main/java` 移除 13 个旧版 Kotlin 源码快照，总计 1,004,253 字节。它们不以 `.kt` 结尾，不属于 Kotlin 编译输入，也没有被源码引用；保留在源码目录只会增加维护噪声与源码包体积。历史交付包仍可作为旧版本留档。

## 体积相关静态审查结论

- Release 已启用 R8 混淆/优化和资源压缩（`isMinifyEnabled`、`isShrinkResources`）；正式构建不要使用 `-PdisableMinify`。
- ABI split 已启用，仅包含 `arm64-v8a` 与 `armeabi-v7a`，并设置 `isUniversalApk = false`。应分发匹配设备的单 ABI APK，不要把两个 ABI 合成 universal APK。
- `libwekit_native.so` 是主要单文件体积项：arm64 约 3.21 MB、armeabi-v7a 约 2.53 MB。ELF 文件已 stripped，未发现可通过简单删除调试符号获得的大幅空间。
- `app/src/main/assets/theme_store/wallpaper` 的 6 张壁纸合计约 1.3 MB；若未来需要进一步压缩，可在视觉验收后转为有损 WebP 并同步修改资源加载逻辑，但本轮不擅自降低内置主题图片质量。
- Eruda 调试脚本在构建时生成 Kotlin 常量并供可选功能使用；它可能贡献一定 DEX 体积，但不应在未确认该功能可选/可动态加载前直接删除。
- `kotlin.reflect` 的 keep 暂时保留：工程有 Kotlin callable/反射相关调用，现有源码也记录了反射内建表被裁剪可能导致运行时异常。应先通过 minified APK 回归，再考虑细化。
- `com.Johnny.wcx.features.**` 的 keep 范围较宽，可能是剩余 R8 优化空间之一，但模块通过生成的功能注册表、DexKit 委托和反射工作。没有构建与功能回归前，不直接放宽，以免功能静默失效。

## 本轮验证

- 过期快照清理：13 个文件已删除，合计 1,004,253 字节。
- 静态规则检查：已确认上述全量 Compose/Serialization/WeChat-stub keep 已移除，必要的应用 serializer 规则仍存在。
- APK 体积前后对比：未完成（需要成功构建 release APK）。
- 全工程 Gradle 编译：未完成（当前环境此前因 Gradle Wrapper 下载主机 DNS 失败，无法获取 Gradle 9.6.1）。
- 设备运行、功能回归：未完成。

## 建议的后续验证顺序

1. 分别构建 `standardRelease` 与 `legacyRelease` 的 `arm64-v8a`/`armeabi-v7a` 变体。
2. 对比 `app/build/outputs/apk` 中每个 release APK 的大小，并检查 `mapping.txt`、`usage.txt`、`seeds.txt`。
3. 回归 JSON/Proto 序列化、Room 数据库、Compose 设置页、脚本引擎、DexKit 特征解析、Xposed 两种入口及微信 8.0.79 Hook。
4. 若序列化与 Compose 回归通过，再评估是否能进一步缩小 feature keep 规则；此项不应仅凭静态分析贸然修改。
