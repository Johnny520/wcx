# 欢迎

## 二次开发衍生声明

本WCX模块基于上游开源项目 WeKit 进行Fork二次修改与功能拓展，属于衍生二改项目。

上游原项目仓库：https://github.com/Ujhhgtg/WeKit

上游所有代码、UI、逻辑版权归属原开源作者，本项目完整遵循上游开源协议。上游相关介绍、致谢、依赖内容全部保留不作删减。

---

WCX 是一个功能丰富的微信增强模块, 支持通过 Xposed 框架或 Zygisk 模块加载, 提供大量微信增强功能。

[![CI 状态](https://github.com/Johnny520/wcx/actions/workflows/ci.yml/badge.svg)](https://github.com/Johnny520/wcx/actions/workflows/ci.yml)

## 导航

* [🚀 快速开始](/wcx-docs/getting-started.md)
* [📥 安装指南](/wcx-docs/installation.md)
* [🧩 Zygisk 模式](/wcx-docs/zygisk.md)
* [⚙️ 配置指南](/wcx-docs/configuration.md)
* [❓ 常见问题](/wcx-docs/faq.md)
* [🛠 开发指南](/wcx-docs/development.md)

## 修改内容 (相比 [上游](https://github.com/cwuom/WeKit))

* 添加 Auxiliary 与 NewMiko 目前公开源代码中的部分功能
* 移除全部校验, 减少模块体积, 避免不必要性能开销
* 移植 UI 至 Jetpack Compose
* 添加, 修复, 增强若干闭源模块部分功能
* 移植其他模块的一些功能
* AGP 升级至 9.X
* 反射移植至 reflekt
* 原生库移植至 Rust
* 修复问题
* 无须禁用「Xposed API 调用保护」
* 大量新功能

## WCX 二次开发新增功能

* 群成员变动提醒（入群/退群/改昵称/被踢监控，支持本地观察与群广播模式）
* AI自动回复增强（私聊黑白名单、调试日志、API兼容性优化）
* 对话分组优化（自定义分组修复、实时刷新、滑动流畅度优化）
* 美化功能增强（四Tab主题背景、沉浸式全屏渲染、主题导入导出分享）
* 红包功能增强（链路提速优化、企业微信互通群适配、私聊群聊分离延迟）
* 自动同意好友申请（支持延迟、欢迎语、黑名单）
* 关键词自动回复（私聊/群聊独立规则、冷却机制）
* 消息类型过滤屏蔽（模板管理、黑白名单、拦截日志）
* LibXposed API 101/102 双向兼容（运行时检测、热重载支持）
* 朋友圈互动消息精确时间展示
* 多处BUG修复与性能优化

## 联系

[GitHub 仓库](https://github.com/Johnny520/wcx)

[Telegram 超级群组](https://t.me/+7j5dJ6g16B43OWVl)

## 致谢

[WeKit 上游](https://github.com/cwuom/WeKit)

[WeKit 二改上游](https://github.com/Ujhhgtg/WeKit)

[WAuxiliary](https://github.com/HdShare/WAuxiliary_Public)

[NewMiko](https://github.com/Ujhhgtg/NewMiko/)

[QAuxiliary](https://github.com/cinit/QAuxiliary)

[FingerprintPay](https://github.com/eritpchy/FingerprintPay)

[WADN](https://github.com/Ujhhgtg/wauxv_deobf_new) [WAD](https://github.com/Ujhhgtg/wauxv_deobf)

[FunBox](https://github.com/Ujhhgtg/funbox_deobf)

[LSPlant](https://github.com/LSPosed/LSPlant)

---

# Agent Instructions
This documentation is published with GitBook. GitBook is the documentation platform designed so that both humans and AI agents can read, navigate, and reason over technical content effectively. Learn more at gitbook.com.

## Querying This Documentation
If you need additional information that is not directly available in this page, you can query the documentation dynamically by asking a question.

Perform an HTTP GET request on the current page URL with the `ask` query parameter, and the optional `goal` query parameter:

```
GET https://johnny520.gitbook.io/wcx-docs/huan-ying.md?ask=<question>&goal=<endgoal>
```

`ask` is the immediate question: it should be specific, self-contained, and written in natural language.
`goal` is optional and describes the broader end goal you are ultimately trying to accomplish on behalf of the user. GitBook uses it to tailor the answer towards what is most useful for that goal.

The response will contain a direct answer to the question and relevant excerpts and sources from the documentation.

Use this mechanism when the answer is not explicitly present in the current page, you need clarification or additional context, or you want to retrieve related documentation sections.