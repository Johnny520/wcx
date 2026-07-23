# 脚本引擎

使用 JavaScript 编写脚本扩展微信功能, 支持消息处理、HTTP 请求、Xposed Hook 等。

## 功能列表

- [脚本引擎](js-scripting-hook.md) — 加载并执行 `<模块数据>/scripts/` 目录下的 JavaScript 脚本
- [触发器: 收到消息](on-message.md) — 收到消息时执行脚本的 `onMessage()` 函数
- [触发器: 发起请求](on-request.md) — 微信发起网络请求时执行 `onRequest()` 函数
- [触发器: 收到响应](on-response.md) — 收到网络响应时执行 `onResponse()` 函数
- [脚本引擎 API 参考](js-api-reference.md) — 完整的 JavaScript API 文档
