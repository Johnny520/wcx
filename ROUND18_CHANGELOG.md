# WCX 微信 8.0.79 第十八轮修改记录

## 侧滑栏
- 手动刷新现在清空天气缓存和上次拉取时间，避免点击刷新后仍命中缓存。
- 天气更新时间统一显示本机 24 小时制 `HH:mm`，并显示“更新于 HH:mm”。
- 侧滑栏“清空未读”从占位 Toast 改为调用 `WeConversationApi.markAllAsRead()`；放入后台线程执行，避免同步遍历会话数据库阻塞主线程。
- 将旧的微信原生快捷目标替换为在上传的微信 8.0.79 DEX 中找到的类名：通讯录 `AddressUI`、设置 `MainSettingsUI`、搜一搜 `FTSMainUI`、小程序 `AppBrandLauncherUI`、我页候选 `MoreTabUI`、视频号 `FinderHomeAffinityUI`。
- 修正 WCX 设置快捷目标的错误包名。
- 从新快捷目标选择列表中移除尚未接入的“群成员变动提醒”占位项；旧配置点击时明确提示该入口尚未实现，不再伪装成成功打开。

## 四 Tab 主题
- 新增四个独立透明度设置项，分别控制主页、通讯录、发现、我四张背景图。
- 保留旧全局透明度作为旧配置迁移的回退值。
- 主题 Hook 仅允许在 `com.tencent.mm.ui.LauncherUI` 创建背景层，防止在聊天 Activity 中再次创建全屏背景。
- 背景容器置于 DecorView 子视图底层；若发现旧版容器仍在顶层，会重新放到底层。

## 红包自动处理
- 上传的微信 8.0.79 APK 的 18 个 DEX 中，确认存在 `MicroMsg.NetSceneReceiveLuckyMoney` 和 `MicroMsg.NetSceneOpenLuckyMoney` 字符串（均位于 `classes14.dex`），说明源码中的两个网络场景类名与 APK 的静态字符串一致。
- 初始领取请求创建或发送异常时，清理 sendId 的去重/状态缓存，避免失败请求永久占用该红包 ID。
- 自动回复发送增加独立异常捕获和日志，自动回复失败不会中断后续抢红包通知逻辑。
- `$amount` 占位符改用传统转义字符串写法，避免依赖新版 Kotlin 字符串模板语法。

## 验证边界
- 完成源码静态检查、目标类名与上传 APK DEX 字符串交叉核对、ZIP 完整性检查。
- 未完成整个 Android 工程编译；本地单文件 Kotlin 检查因缺少 Android/Compose/项目依赖，不能作为工程编译结果。
- 未在微信 8.0.79 运行时验证 Hook 命中、网络请求、聊天页背景隔离或快捷 Activity 启动结果。
