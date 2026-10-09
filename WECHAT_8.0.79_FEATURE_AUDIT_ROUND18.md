# 微信 8.0.79 静态功能审查（第十八轮）

## 本轮可确认的问题与修复

| 区域 | 静态发现 | 修改 | 验证边界 |
|---|---|---|---|
| 侧滑栏天气刷新 | 刷新按钮只调用普通加载，天气缓存有效期内仍返回旧数据 | 手动刷新先清空缓存并重置拉取时间 | 网络请求需要运行时验证 |
| 天气时间 | 多个天气源返回的时间格式不同，`observation_time` 可能为空或带 AM/PM | 统一以拉取时间显示本地 `HH:mm`，UI 显示“更新于 HH:mm” | 未验证实时 API 响应 |
| 清空未读 | 点击只显示“已尝试清空未读（占位）”，未执行操作 | 调用 `WeConversationApi.markAllAsRead()`，后台线程执行并反馈成功/失败 | 需要微信运行时验证 API 的 DexKit 解析 |
| 快捷目标 | 多个旧类名未在 APK 中找到：`ContactsUI`、`MeTabUI`、`SettingsUI`、`SearchMainUI`、`AppBrandMainUI` | 替换为 APK DEX 中可检索到的候选类名，并修正 WCX 设置 Activity 包名 | DEX 字符串存在不等于一定可作为 Activity 启动，需运行时验证 |
| 群成员变动提醒 | 快捷目标只有占位 Toast，没有实际设置页面 | 从可选目标列表移除；旧配置点击时显示明确的未接入提示 | 功能本身仍未实现 |
| Tab 主题透明度 | 四张背景图共用一个 alpha 值 | 为四个 Tab 添加独立透明度偏好与滑块 | 需要重启后视觉验证 |
| 聊天页背景覆盖 | 主题 Hook 没有限定宿主 Activity，背景层添加到 DecorView 顶部 | 只在 LauncherUI 安装，并将容器放到 DecorView 底层 | 需要微信内实际打开聊天会话回归测试 |
| 自动抢红包 | 上传 APK DEX 包含两个网络场景的类名；发送请求异常时去重状态可能残留 | 失败时清理缓存状态 | 类名命中不代表构造参数、方法签名和协议行为已经验证 |
| 抢到后自动回复 | `sendText` 异常可能中断后续通知处理 | 独立捕获异常并记录日志 | 需要真实红包流程验证 |

## 微信 APK 交叉核对

上传文件：`微信_8.0.79.apk`

- APK 包含 18 个 DEX 文件。
- `MicroMsg.NetSceneReceiveLuckyMoney` 与 `MicroMsg.NetSceneOpenLuckyMoney` 都能在 `classes14.dex` 中检索到。
- 侧滑栏原生目标中，`BaseScanUI`、`ImproveSnsTimelineUI`、`FavoriteIndexUI`、`WalletOfflineCoinPurseUI`、`FinderHomeAffinityUI`、`AddressUI`、`MoreTabUI`、`MainSettingsUI`、`FTSMainUI` 等名称可在 DEX 中找到。`AppBrandLauncherUI` 以 DEX 内部斜线形式存在。

## 不应据此宣称已修复

- 自动抢红包是否能命中 8.0.79 的 DexKit 方法签名。
- 红包领取请求是否成功，以及 `receiveStatus` / `amount` 在目标版本中的真实字段语义。
- 自动回复是否在群聊、私聊、异常响应和重复响应下都正确。
- 所有 700 余个 Kotlin 源文件中的每项功能是否有效。本轮仅对本次提到的侧滑栏、天气、Tab 主题和红包路径做了重点静态审查，不把局部审查冒充全项目回归测试。
- APK 构建、安装和实机兼容性。
