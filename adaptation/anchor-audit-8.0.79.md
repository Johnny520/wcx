# 锚点全盘校验 · 微信 8.0.79

> 生成方式：拉取 `app/src/main/java/com/Johnny/wcx/features/items/` 下全部 270 个功能文件，
> 抽出 222 个 `dexClass` / `dexMethod` 块的 **236 组** `usingEqStrings` 特征串（327 个唯一串），
> 再用 androguard 遍历 8.0.79 arm64 APK（18 个 dex、253229 个类、约 90 万个方法）建立
> 「字符串常量 → 引用它的方法」索引，按 DexKit 的真实匹配语义逐组判定：
>
> - 方法级 matcher：要求**同一个方法**内引用组内全部串
> - 类级 matcher（`dexClass` 或 `dexMethod` 里的 `declaredClass{}`）：要求**同一个类**内引用组内全部串
> - `searchPackages` 参与前缀过滤
>
> 结果：**236 组中 16 组不命中**，涉及 **9 个功能**。

## 结论速览

| 结论 | 数量 |
| --- | --- |
| 锚点可用 | 220 组 |
| 锚点失效，但已有 `allowFailure` 降级（功能降级、不崩、不影响其它 hook） | 14 组 |
| 锚点失效且无 `allowFailure` | 2 组（均为「灰度测试管理器」的空串/短串匹配，判定器无法评估，实际未失效） |

**没有任何一组会让 `onEnable()` 抛异常从而连带拖挂同功能其它 hook。**

## 失效清单与原因

| 功能 | 锚点 | 类型 | 失效原因 | 结论 |
| --- | --- | --- | --- | --- |
| 隐藏联系人 | `MicroMsg.VoipPluginManager` + `weixin://voip/callagain/?username=` | 类级 | 8.0.79 里该 URL 只以 HTML 片段内联在 `com.tencent.mm.plugin.voip.model.b2` 中，精确短串不存在 | **已修**：改 `usingStrings` 包含匹配 |
| 隐藏联系人 | `MicroMsg.VoIPMP.Launcher` + `insertMsg() called with: toUser = ` | 方法级 | 两串分处不同方法（`Ltx5/i3` 协程类），AND 恒不命中 | 已有 `MicroMsg.VoIPMP.CoreV2` 版候选命中，无需修 |
| 隐藏联系人 | `MicroMsg.MT.MultiTalkManager` + `onInviteMultiTalk ...` | 方法级 | 8.0.79 删除了 `MultiTalkManager` 日志体系 | 多人通话邀请屏蔽在 8.0.79 不可用（已知降级） |
| 自动同意好友申请 | `MicroMsg.VerifyUserUtil` + `verify ok clicked` | 方法级 | `VerifyUserUtil` 类在 8.0.79 已不存在（仅剩 repairer 配置项） | 需按「好友申请详情页」重新定位，见下 |
| 自动添加附近的人 | `NearbyPersonUIC` + `showLiveBottomSheet create menu.` | 方法级 | 8.0.79 起「附近的人」页面改版，两串均不存在（8.0.71~8.0.78 才有） | 功能在 8.0.79 无可用界面（已知降级） |
| 自动添加附近的人 | `com.tencent.mm.plugin.nearby.ui.NearbySayHiListUI`（点号） | 方法级 | DexKit 的串是 **dex 内斜杠形式**（`com/tencent/...`），点号永不命中 | 可改斜杠形式，但页面已改版，收益有限 |
| 音视频通话使用画中画 | 5 组 `MultiTalkUIViewModel` / `onMicClick` / `onCameraClick` / `onMiniMultiTalk` / `onExitMultiTalk` | 混合 | 微信 8.0.79 把多人通话 UI 整体搬进 `voipmp.v2`（Flutter + `ZIDL_*` native 桥），原 `multitalk.ui` 日志体系整体消失 | 需针对 `voipmp.v2` 重写，工作量大 |
| 移除通话时聊天限制 | `checkDeviceUsing` / `checkAudioDeviceUsing` / `checkSpeakerUsing` | 方法级 | 这 3 个占用检测 API 在 8.0.79 已删除 | 说明微信自己不再做这类限制，功能自然退出 |
| 灰度测试管理器 | `Int` / `Float` / `String` / `Long` / `""` | 类级 | 判定器无法评估空串，非真实失效 | 无需处理 |

## 另外发现：硬编码类名不存在的功能（反射路径静默降级）

`Class.forName` / `XposedHelpers.findClass` 用的类名在 8.0.79 已不存在，异常被 `runCatching` 吞掉 → 功能静默不生效：

| 功能 | 失效类名 | 8.0.79 现状 |
| --- | --- | --- |
| 批量删除朋友圈 / 自动刷新 | `com.tencent.mm.plugin.sns.model.SnsCore` | 已混淆为 `com.tencent.mm.plugin.sns.model.p4`（代码内已有 `delegateSnsCore` 二次筛选，可用） |
| 自定义尾巴 | `com.tencent.mm.plugin.sns.model.UploadPackHelper` | 已混淆为 `com.tencent.mm.plugin.sns.model.i8` |
| 朋友圈评论防撤回 | `SnsCommentStorage` / `SnsComment` / `SnsSqliteDB` | 三个类均不存在，需按 `SnsCommentDetailUI` + 现有 `SnsInfoStorage` 体系重写 |
| 点击空白处查看详情 | `ImproveTimelineItemClick` / `ImproveSnsInfo` | 均不存在，朋友圈 improve 组件已整体混淆 |
| 禁止主页下滑进入最近页面 | `com.tencent.mm.ui.widget.MMToolBar` | 不存在，需按 `MMActivity`/`ActionBar` 体系重新定位 |
| 移除消息批量转发限制 | `com.tencent.mm.ui.mvvm.MvvmSelectContactUI` | 不存在，选人页已重构 |
| 资料卡居中 | `SettingsPersonalInfoUI` | 不存在，设置页已迁到 `settings_new/settings/other/...` |
| 常驻互动消息入口 | `ImproveHeaderUIC$onNotifyChange$1$1` / `$1$4` | Kotlin lambda 类名，每次编译必变，不能作为锚点 |

## 可复用经验

1. **`usingEqStrings` 是「精确相等」+「同一方法内 AND」**，两串分处不同方法/类必不命中。
   日志串在版本升级后常常「短串 → 长串加参数」，此时精确匹配必失效。
2. **`usingStrings` 默认是 `Contains`（包含匹配）**，适合「前缀/后缀会漂移」的日志串，是低成本抗漂移手段。
3. **类名要写 dex 形式（斜杠）**，DexKit 的字符串常量是 `com/tencent/mm/...`，写点号永不命中。
4. **微信为自己埋的性能点（如 `SnsMethodCalculate.markStartTimeMs("<方法名>", "<类名>")`）把方法名当字符串常量放进方法体**，
   这是跨版本最稳的锚点来源，可用 `usingEqStrings("<方法名>", "<类名>")` 精确定位，完全不依赖混淆名。
