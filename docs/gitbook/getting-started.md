# 快速开始

## 什么是 WCX？

WCX 是一个微信增强模块, 基于上游开源项目 WeKit 进行Fork二次修改与功能拓展。可以通过 Xposed、Zygisk 或免 Root 注入框架加载, 为微信提供大量额外功能和界面定制选项。

## 需求

| 项目 | 要求 |
| ---------- | -------------------------------------------- |
| 宿主包名 | `com.tencent.mm` 或以 `com.tencent.mm` 开头的任意包名 |
| 微信版本 | 详见下方「宿主版本支持」 |
| Android 版本 | >= 9 (SDK >= 28) |
| 加载方式 | Xposed API 51~102 或 Zygisk |

### 宿主版本支持

#### 国内版

| 状态 | 版本 | 下载 |
| ------- | ---------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| ❌ 不支持 | < 8.0.65 | N/A |
| 🔧 维护 | 8.0.65 ~ 8.0.68 | [8.0.65 官方](https://dldir1v6.qq.com/weixin/android/weixin8065android2960_0x28004137_arm64.apk) [8.0.66 官方](https://dldir1v6.qq.com/weixin/android/weixin8066android2980_0x28004234_arm64.apk) [8.0.67 官方](https://dldir1v6.qq.com/weixin/android/weixin8067android3000_0x28004332_arm64.apk) [8.0.68 官方](https://dldir1v6.qq.com/weixin/android/weixin8068android3020_0x28004434_arm64.apk) |
| ✅ 支持 | 8.0.69 ~ 8.0.76 | [8.0.69 官方](https://dldir1v6.qq.com/weixin/android/weixin8069android3040_0x2800455a_arm64.apk) [8.0.70 官方](https://dldir1v6.qq.com/weixin/android/weixin8070android3060_0x28004634_arm64_1.apk) [8.0.71 官方](https://dldir1v6.qq.com/weixin/android/weixin8071android3080_0x28004734_arm64.apk) [8.0.72 官方](https://dldir1v6.qq.com/weixin/android/weixin8072android3100_0x28004835_arm64.apk) [8.0.74 官方](https://dldir1v6.qq.com/weixin/android/weixin8074android3120_0x28004a36_arm64.apk) [8.0.76 官方](https://dldir1v6.qq.com/weixin/android/weixin8076android3120_0x28004c10_arm64.apk) |
| 🧪 初步适配 | N/A | N/A |

#### 国际版 (Google Play)

| 状态 | 版本 | 下载 |
| ----- | ---------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 🔧 维护 | 8.0.68 ~ 8.0.69 | [8.0.68 APKMirror](https://www.apkmirror.com/apk/wechat/wechat/wechat-8-0-68-release/) [8.0.69 APKMirror](https://www.apkmirror.com/apk/wechat/wechat/wechat-8-0-69-release/) |

#### 状态说明

| 状态 | 说明 |
| ------- | ----------------------------------------------------------------- |
| ❌ 不支持 | 不适配该版本, 不接受任何来自该版本的问题反馈 |
| 🔧 维护 | 适配该版本, 但模块更新时不再主动在该版本上测试, 如遇问题请反馈, 优先级中等 |
| ✅ 支持 | 适配该版本, 模块更新新功能与修复时在该版本上测试, 如遇任何问题请及时反馈, 优先级最高 |
| 🧪 初步适配 | 正在适配该版本, 模块未在该版本上充分测试 |
| 🗑️ 废弃 | 曾适配过该版本, 但因故放弃, 不再维护 |

> 模块开始开发时针对的版本为国内版 8.0.65, 小于该版本即为「不支持」, 且若无特殊情况不会尝试适配。

## 功能概述

* **💬 聊天功能** — 消息撤回、引用、增强输入栏、自动回复、群成员变动提醒、关键词回复、消息屏蔽等
* **👥 联系人与群组** — 显示微信 ID、群成员消息历史、好友检测、自动同意好友申请、对话分组等
* **🎨 界面美化** — 圆角头像、自定义背景、导航栏美化、四Tab主题背景、沉浸式全屏渲染等
* **💰 红包与支付** — 自动抢红包、红包链路提速、企业微信互通群适配、指纹支付等
* **🛡️ 系统与隐私** — 广告拦截、环境伪装、隐私保护、缓存清理等
* **🔧 调试** — 崩溃拦截、日志重定向、调试信息等
* **📱 朋友圈** — 广告拦截、查询增强、点赞伪装、互动消息精确时间等
* **📦 小程序** — 广告移除、版本伪装等
* **🔔 通知** — 通知进化 (快速回复、MessagingStyle)
* **📹 视频号** — 媒体下载、评论限制移除
* **📜 脚本引擎** — JavaScript 脚本支持
* **👤 个人资料** — 透明头像、签名限制移除、昵称设置
* **📢 公众号** — 多开、旧版视图
* **🎮 娱乐** — 清空资料、彩蛋等

## 下一步

* [安装指南](/wcx-docs/installation.md) — 下载并安装模块

---

# Agent Instructions
This documentation is published with GitBook. GitBook is the documentation platform designed so that both humans and AI agents can read, navigate, and reason over technical content effectively. Learn more at gitbook.com.

## Querying This Documentation
If you need additional information that is not directly available in this page, you can query the documentation dynamically by asking a question.

Perform an HTTP GET request on the current page URL with the `ask` query parameter, and the optional `goal` query parameter:

```
GET https://johnny520.gitbook.io/wcx-docs/getting-started.md?ask=<question>&goal=<endgoal>
```

`ask` is the immediate question: it should be specific, self-contained, and written in natural language.
`goal` is optional and describes the broader end goal you are ultimately trying to accomplish on behalf of the user. GitBook uses it to tailor the answer towards what is most useful for that goal.

The response will contain a direct answer to the question and relevant excerpts and sources from the documentation.

Use this mechanism when the answer is not explicitly present in the current page, you need clarification or additional context, or you want to retrieve related documentation sections.