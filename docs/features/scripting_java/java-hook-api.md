# 脚本 Hook 服务

> 提供 BeanShell 脚本可用的 Xposed Hook 能力

## 类别

API

## 类型

始终启用

## 描述

为 Java 脚本引擎提供 Xposed Hook API, 使 BeanShell 脚本能够使用 `hookBefore`、`hookAfter`、`hookReplace` 等方法拦截和修改 Java 方法的调用。脚本可以注册自定义 Hook 回调, 通过 `JavaHookApi` 与模块的 Hook 系统集成。

该功能始终启用, 是 Java 脚本引擎的基础服务。

## 使用方法

无需手动配置。Java 脚本可通过 `JavaHookApi` 提供的 API 直接使用
