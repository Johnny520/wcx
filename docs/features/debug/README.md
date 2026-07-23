# 调试

调试功能用于排查问题、分析性能和崩溃诊断。

## 功能列表

- [崩溃拦截](crash-interceptor.md) — 拦截 Java 层崩溃并记录详细信息
- [崩溃拦截 (Native)](native-crash-interceptor.md) — 拦截 Native 层崩溃并记录详细信息
- [崩溃日志查看器](crash-logs-viewer.md) — 查看历史崩溃日志
- [重定向微信日志](redirect-host-logs.md) — 将微信内部日志输出到模块日志, 支持过滤
- [复制调试信息](copy-wechat-debug-info.md) — 一键复制模块运行信息, 反馈问题时使用
- [发包调试](send-packet.md) — 发送自定义数据包到微信服务器
- [启动微信内部 URL](launch-internal-urls.md) — 跳转 weixin:// 协议的内部页面
- [内存分析](profile-memory.md) — 分析微信内存占用组成
- [重置适配信息](reset-dex-cache.md) — 清除 DEX 适配缓存, 下次启动重新适配
- [测试](experiments.md) — 访问微信内部实验功能
- [测试崩溃](trigger-crash.md) — 手动触发测试崩溃, 验证崩溃拦截是否正常
