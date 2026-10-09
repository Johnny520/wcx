# WCX 微信 8.0.79 适配专项检查（第三轮）

## APK 元数据核实

已解析用户提供 APK 的二进制 AndroidManifest.xml：`package=com.tencent.mm`、`versionName=8.0.79`、`versionCode=3200`。已在 `WeChatVersions.kt` 增加 `MM_8_0_79 = 3200` 常量。该常量仅用于准确识别版本，**不代表相关 Hook 已适配**。APK 含 18 个 DEX，未完成逐方法反编译与真机验证。

## 本轮实际修改

1. `DynamicClassScanner.scan()`：每种扫描策略出现异常时记录日志并继续尝试后续策略，避免单个策略异常中止整项扫描。
2. `DynamicClassScanner.scanBatch()`：对每个 `ClassFeature` 单独隔离异常，失败项进入失败列表，其余特征继续扫描。
3. 修正上一轮描述符解析的对象类型分支：普通对象描述符先去掉 `L...;` 再交给宿主 ClassLoader；数组描述符继续使用 JVM 数组描述符加载，避免 `Ljava.lang.String;` 被错误传给 `Class.forName`。
4. `DynamicHookInjector.inject()`：只有全部 Dex delegate 都成功设置描述符时才返回成功；之前“只成功注入一个 delegate 就算成功”的判定会掩盖部分失效。
4. 保留前两轮的 UTF-8 日志、Git 元数据缺失回退、异常诊断、并发保护及错误适配状态修正。

## 已确认的适配阻塞点

- 微信源码级适配不能由版本号覆盖范围推导。仓库内大量功能实现注释明确绑定 8.0.65–8.0.76 的类名/方法名/签名；对 8.0.79 尚未有逐功能运行证据。
- 动态扫描特征注册表使用 `LauncherUI`、`ChattingUI` 等 `ClassFeature.id`，但实际功能注入器以 `BaseFeature.name` 作为扫描结果键。除非两者完全相同，否则没有结果可注入。当前项目没有足够的显式映射来证明这些通用类特征可以安全地映射到所有功能的 delegates。
- 通用扫描结果不能直接替代每个功能的精确方法/字段特征。模糊匹配后直接覆盖 delegate 可能把错误的方法描述符注入功能，因此不得将“扫描到一个类”视为“该功能已适配”。
- `CloudFeatureDB` 使用占位服务域名，不能提供可用的远端规则。当前修改仅避免无效请求，不会凭空生成云端适配能力。

仓库共检查到 672 个 Kotlin 源文件，其中 32 个文件的注释/实现提及 8.0.69、8.0.74 或 8.0.76（共 143 处），说明版本差异分散在多个功能模块，不能仅靠单个版本常量完成兼容。

## 验证边界

本环境没有 Android SDK、Gradle 分发包及可用的 Dex 反编译器，无法运行 Kotlin/Gradle 编译、对微信 DEX 做可靠的符号级反编译或进行真机 Hook 回归。提供的微信 APK 含 18 个 DEX 文件；仅靠 `strings` 或版本字符串不足以确定混淆类/方法的对应关系。因此本轮没有宣称“完美适配”，也没有伪造 APK。

## 构建与回归验收清单

1. 在具备 Android SDK、JDK 17 和网络/Gradle 缓存的环境运行 `./gradlew test`、`./gradlew lint`。
2. 构建 `standard` 与 `legacy` 两个 flavor 的 arm64-v8a / armeabi-v7a 变体。
3. 在隔离测试设备上确认微信 8.0.79 进程加载、DexKit 初始化、每项 delegate 的注入数量与目标签名。
4. 对每项功能分别记录：目标类、方法/字段签名、Hook 安装结果、启用/禁用行为、崩溃与 ANR。未验证项必须标记为“不支持/未验证”，不能标记“已适配”。
5. 在完成显式 `ClassFeature.id -> feature technicalId -> delegate key` 映射表并验证唯一性之前，不应将通用动态扫描结果强制写入任意功能的 delegate。
