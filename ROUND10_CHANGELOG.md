# WCX 8.0.79 静态修复记录（第十轮）

本轮只记录能够从源码直接确认的修复，不代表已完成微信 8.0.79 的全部功能适配。

## 修复

1. `AutoAdaptationManager.retryFeature()` 原先把单功能重试结果按请求的 `BaseFeature` 名称写入共享 `scanResults`，而批量扫描按 `ClassFeature.id` 存储。两种键空间不一致会让后续按扫描特征 ID 查找的逻辑失效。现统一使用 `classFeature.id`。
2. `startAllFeatures()` 原先只用 `feature.name` 判断扫描失败，但扫描注册表的 ID 可能对应稳定 `technicalId`。现仅按名称或非空 `technicalId` 精确比较，避免漏掉可明确归属的失败记录；没有明确映射时仍不把失败归给无关功能。

## 验证

- 对修改文件执行了差异检查与关键代码路径检查。
- ZIP 完整性将在打包后检查。
- 本环境尚未具备项目要求的 Gradle 发行包，因此未宣称 Kotlin 编译通过；也未生成可验证的安装 APK。
- 全部 Hook 目标仍需逐项核对，并在微信 8.0.79 进程内回归测试。
