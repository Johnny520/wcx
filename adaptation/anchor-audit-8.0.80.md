# 8.0.80 锚点审计（对 236 组 DexKit 锚点的全量离线校验）

> 承接 [anchor-audit-8.0.79.md](./anchor-audit-8.0.79.md)。上一轮按 8.0.79 arm64 APK 做的校验，
> 但用户实机日志显示跑的是 **微信 8.0.80（versionCode 3220）**，于是用 8.0.80 APK 重跑一遍。
>
> APK：`weixin8080 arm64`（285 MB，18 dex，254508 类，943179 个方法，876378 个字符串常量）

## 结论

| | 8.0.79 | 8.0.80 |
|---|---|---|
| 失效锚点组 | 16 | **57** |
| 新增失效 | — | **41**（另有 0 组「在 8.0.80 恢复」） |

**41 组新增失效全部不是「类/方法没了」，而是「用来定位的字符串没了」。**
所有被判失效的串在 8.0.80 的 string 表里**都还在**（孤儿串），只是**没有任何方法再引用它**。

这是因为微信自 8.0.76 起持续做「删日志调用、保留常量池条目」的裁剪：R8/D8 只删调用点，
字符串常量仍被 string_ids 引用。DexKit 的 `usingEqStrings` 按「方法内 const-string 引用」匹配，
所以对孤儿串永远是 0 命中。**判断锚点是否有效不能用「串是否存在」，必须看「有没有方法引用它」。**

## 四类根因

1. **日志文案改格式**（最好修）
   例：`updateMainTabUnread %d` → `[updateMainTabUnread] unread : `
   （与同类 Friend/Contact/Settings 三个未读更新方法统一格式）
   例：`put stack into pool: `（孤儿）→ `MicroMsg.AvatarGetContactServiceHelper`（仍被引用）
2. **日志串彻底删除**（需重新逆向，找同类另一条仍在的串或结构化约束）
   例：`speakerOn has been set %s`、`switchSpeaker, isSpeakerOn: %b, isPlaying: %b`、
   `jacks dealNotify, ...`、`updateStatusBar %s`、`setCommentDelFlag`、`gotoNotifyMsgUI`、
   `openSpecificUI, context is null`、`update device Step time: %s stepCount: %s`
3. **类被整体混淆改名**（类名字符串直接失效）
   8.0.80 已不存在的类：`SnsComment`、`SnsInfoStorage`、`SnsUtil`、`NetSceneSnsSync`、
   `UploadPackHelper`、`MMProgressDialog$Builder`、`MMToolBar`、`SettingsPersonalInfoUI`、
   `MvvmSelectContactUI`、`FinderLikeDrawerBuilder`、`LauncherUITabView`（→ `LauncherUIBottomTabView`）
4. **TAG 串被多个类共用**（单串做 `declaredClass` 会多命中）
   例：`MicroMsg.AutoPlay` 在 8.0.80 被 20+ 个聊天页类引用；
   `MicroMsg.AvatarDrawable` 被 3 个类/方法引用。
   必须叠加「该类独有的另一条串」收窄。

## 本次已修

| 功能 | 锚点 | 8.0.80 新锚点 | commit |
|---|---|---|---|
| 对话归拢 / 对话分组 / 隐藏联系人 | `methodSqliteWrapperRawQuery` | `usingEqStrings("sql is null ", "DB IS CLOSED ! {%s}")` → 只用后者（前者成孤儿串，后者仍被 22 个方法引用） | 03ce0544 |
| 解除风险文件拦截 | `methodRiskFileFlag` | 16 串 AND 里去掉已成孤儿串的 `url-click-type`、`unread-media-expired`；补 `allowFailure` + placeholder 守卫 | 03ce0544 |
| 解除单个表情数量上限 | `methodComputeEmojiStorageState` | 去掉孤儿串 `normal_custom_size`，单串 `MicroMsg.EmojiStorageState`（void+1 参唯一收敛 `tr/w.a(Z)V`） | 03ce0544 |
| 美化首页底部导航栏 | `methodUpdateTabUnread` | `[updateMainTabUnread] unread : `（`LauncherUIBottomTabView.k(I)V`） | 5354034c |
| 自定义好友本地头像 | `methodMvvmLoadAvatar1/2` | 孤儿串 `put stack into pool: ` → `MicroMsg.AvatarGetContactServiceHelper`（`zs1.s.Gj(ImageView,String,String,float)V`） | 5354034c |
| 自定义好友本地头像 | `methodConversationAvatar` | 去掉漂走的 `imageView is null`，单串 `MicroMsg.AvatarDrawable`；4 参 + void + `View.invalidate` 三个约束在 `pluginsdk.ui.u.b` 上仍全部满足（已 dump 字节码确认） | 5354034c |
| 语音消息播放逻辑优化 | `methodSetSpeakerOn` / `methodSwitchSpeaker` | 两条旧串全成孤儿串。真 AutoPlay = `com.tencent.mm.ui.chatting.x0`，用类内独有串 `isHeadsetPlugged: %b, isBluetoothOn: %b` 锁类；`setSpeakerOn` = `x0.G(Z)V`（调 `platformtools.Log.c` 与同类 d/F/J 区分），`switchSpeaker` = `x0.K()V`（调 `modelbase.d1.g(Z)`） | b2925d2d |

## 待修清单（按影响面排序）

| 功能 | 失效锚点 | 现状 / 下一步线索 |
|---|---|---|
| 通知进化 | `methodDealNotify` | 串 `jacks dealNotify, ...` 已删；8.0.80 只剩 `hasTryDealNotifyByLightPush`，需重找通知聚合入口 |
| 朋友圈评论防撤回 | `methodSnsCommentSetCommentDelFlag` | `setCommentDelFlag` 无引用且 `SnsComment` 类已混淆改名，需从 `sns.storage` 包内找「(String,...)→」评论 DAO |
| 常驻互动消息入口 | `methodShowNotifyBar` / `methodHideNotifyBar` / `methodGotoNotifyMsgUI` | 依赖的 lambda 类名（`ImproveHeaderUIC$onNotifyChange$1$1`）已变；`ImproveHeaderUIC` 类本身还在（classes6） |
| 自定义尾巴 | `methodSetSdkAppId` / `methodSetSdkAppName` / `methodSnsUploadUIInitView` | `UploadPackHelper` 已混淆（8.0.80 ≈ `sns.model.i8`）；`SnsUploadUI` 类仍在 classes6 |
| 隐藏联系人（6 项） | `methodFinderLikeDrawerRefresh` / `methodSnsInfoToSnsStruct` / `methodSnsSyncUpdateRedDotCache` / `methodVoipMpStartFgs` / `methodVoipShowFinishCard` / `methodVoipStartAcceptVoip` | `SnsUtil`、`NetSceneSnsSync`、`FinderLikeDrawerBuilder` 类名全部消失；VoIP 两条日志串被删 |
| 主题 | `methodHomeUiUpdateStatusBar` | `HomeUI` 类仍在（classes15），但类内已无任何 `updateStatusBar` 相关串，只剩 `actionBarContainerMarginTarget35Fix, statusBarHeight: %d` |
| 恢复旧版「我」界面卡包 | `methodGetOrderAndCardEntranceInfo` / `methodMoreTabUIHandlePrefOnClick` | `MicroMsg.EcsOrderService`、`MoreTabUI` 相关串需重新定位（`MoreTabUI` 类仍在 classes9） |
| 音视频通话使用画中画 | `classFlutterVoipPlugin` / `methodFlutterVoipMinimize` | `MicroMsg.FlutterVoipPlugin` 与两条提示串均无引用 |
| 移除通话时聊天限制 | `methodIsVoiceUsing1/2` | `isVoiceUsing` 无引用 |
| 修改运动步数 | `methodUploadSteps` | 日志串被删，需从 `com.tencent.mm.plugin.sport` 里找上传入口 |
| 禁用微信进程状态检测器 | `methodPerformProcessCheck` | `pass this check,because request is null! ????` 无引用 |
| 启用内部 URL | `methodOpenUrl` | `MMURIJumpHandler` 类已混淆（`openSpecificUI, context is null` 无引用） |
| 禁用「刚刚在看」提醒 / 「转发截图」提示 | `methodShowRecoveryToast` / `methodDisplayToast` | 两组都是多串 AND，其中一条成孤儿串 |
| 自动启用合并发送媒体 | `methodUpdateSendAsMediaGroupViews` | 纯方法名锚点，8.0.80 方法名已混淆 |
| 对话框窗口级背景模糊 | `classMmProgressDialog` | `MMProgressDialog$Builder` 类已混淆 |

> 以上各组均带 `allowFailure` 或已有 placeholder 守卫，**不会因为解析失败拖挂同功能其它 hook**，
> 表现是「该功能静默不生效」，不会崩。

## 复用工具

`anchor/tools/` 下（沙箱内）：

- `clsdex80.py` — 建「类名 → dex 文件」索引（39 秒）
- `scanfull80.py` — 建全量方法/字符串 sqlite 索引 `/tmp/wx80.db`（89 秒，943179 方法）
- `qs.py <串...>` — 按精确串查引用方法（类/方法/描述符/dex）
- `qk.py <关键词...>` — 按关键词模糊查串（找新日志文案最快的手段）
- `dump80.py <类> [--sig]` — dump 类的全部方法 + 方法内 const-string
- `inv80.py <类> [方法]` — dump 方法的 invoke 目标与 const-string（判断 addInvoke 约束是否还成立）

典型流程：`qk.py <旧串关键词>` 找新文案 → `qs.py <新串>` 拿引用方法 → `inv80.py` 验证签名/invoke 约束
→ 写进 `dexMethod` matcher（多串 AND 里剔除孤儿串，必要时换类内独有串收窄）。
