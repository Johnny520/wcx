# WCX 微信 8.0.79 静态检查第七轮

## 本轮修复

1. `DynamicClassScanner.buildResult` 不再把“类命中、但所有已声明方法/字段特征均未命中”报告为成功。此类候选现在会被拒绝并继续尝试后续扫描策略。
2. 扫描结果置信度现在会乘以已匹配成员占比，并记录部分覆盖情况，避免类级别匹配的基础分数掩盖成员特征缺失。
3. `DynamicHookInjector.injectBatch` 支持通过功能 `name` 或非空 `technicalId` 做精确键匹配；不使用模糊子串猜测映射。
4. 单功能重试、扫描失败降级及缓存保存也支持精确 `technicalId` 匹配。没有精确映射时会记录诊断，而不是把通用扫描 ID 的失败错误归到另一个功能。

## 验证边界

本轮为静态源码修复。它不能补足仓库缺失的 `ClassFeature.id -> BaseFeature` 真实映射，也不能替代对微信 8.0.79 DEX 的逐项签名核对。未运行 Gradle/Kotlin 编译、未构建 APK、未在微信进程中进行 Hook 回归。
