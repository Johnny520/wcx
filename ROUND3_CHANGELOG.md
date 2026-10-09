# WCX 8.0.79 静态检查第三轮变更

- 从提供的微信 APK Manifest 确认 versionName=8.0.79、versionCode=3200，并新增 `WeChatVersions.MM_8_0_79` 常量（仅识别，不代表功能已验证）。
- 动态扫描策略异常隔离：一个 matcher 异常不再阻止其余策略和特征扫描。
- 修正普通对象参数描述符的反射加载方式，避免把带 `L` 前缀和分号的描述符直接传入 `Class.forName`。
- Hook delegate 注入状态更严格：只注入部分 delegate 时返回失败，避免错误报告功能已适配。
- retryFeature 在不存在显式映射时输出明确告警并安全退出，不再静默无操作。
- 延续前两轮构建元数据回退、UTF-8 日志、并发保护、占位云端地址检查与适配状态诊断。

## 限制

这是源码静态修复包，不是已验证的微信 8.0.79 兼容发行版。当前执行环境未能提供 Android SDK/Gradle 发行包/Dex 反编译器，因此未完成编译、DEX 符号对照和真机回归；不附带虚构 APK。详见 `WECHAT_8.0.79_REVIEW_ROUND3.md`。
