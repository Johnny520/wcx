# 微信 8.0.80 委托锚点校验报告（离线全量）

> 校验对象：WCX `app/src/main/java` 下全部 `dexClass / dexMethod / dexField / dexConstructor` 委托（515 项）
> APK：`weixin8080android3220_0x28005030_arm64.apk`（285,880,160 B，18 dex）
> 工具链：`adaptation/tools/`（extract_props → verify_props → classify_fail）

## 1. 结果统计

| 版本 | OK | WARN | SIG | FAIL | SKIP |
|---|---|---|---|---|---|
| 8.0.79 | 365 | 20 | 10 | 19 | 101 |
| 8.0.80 | 365 | 20 | 10 | 19 | 101 |

- **OK**：存在满足全部条件的目标方法（锚点有效）
- **WARN**：类级命中但无单方法满足 / `addInvoke` 跨方法约束离线不可判定
- **SIG**：字符串命中但参数/返回类型不符
- **FAIL**：无任何候选
- **SKIP**：无方法级字符串锚点（按结构定位，需运行时验证）

## 2. 本轮修掉的引擎/工具缺陷（5 个）

| # | 缺陷 | 影响 | 修复 |
|---|---|---|---|
| 1 | `iter_insns` 把 WIDTH（code unit）当字节数递增 | 指令流整体错位一半 | `p += WIDTH[op] * 2` |
| 2 | WIDTH 表 `0x0a–0x12`（move-result / return 系列）误设为 2 unit | 方法末对齐率仅 23.8% | 重建完整宽度表，对齐率 → **100%** |
| 3 | payload 伪指令未按 4 字节边界对齐 | packed/sparse-switch、fill-array-data 之后错位 | 跳过时向上对齐到 4 字节 |
| 4 | Kotlin `${'$'}name` 转义未还原 | 这类锚点永远匹配不上（朋友圈菜单 3 项误报 FAIL） | 提取时还原为 `$` |
| 5 | `addInvoke{}` / `addMethod{}` 跨方法约束未建模 | 3 项误报 FAIL | `addInvoke` 且串全存活 → WARN，不判 FAIL；`addMethod` 分组成 `orGroups` 做 OR 判定 |

> 修复前 8.0.80 报 128 FAIL，其中约 100 项为上述缺陷造成的假阴性。

## 3. 本轮修复的锚点（已合并 dev）

| 委托 | 原因 | commit |
|---|---|---|
| `RemoveMessageSelectionLimit:classChatItemQuickSelect` | TAG 与 `initViews:` 串被拆到同一类的不同方法（TAG 命中 4 个类，`initViews:` 仅 f1） | `df2eae77db` |
| `SwipeConversationOperations:classConversationAdapter` | `ConversationWithCacheAdapter` TAG 移出 `ui.conversation` 包 | `0a45700f90` |
| `WeConversationListViewApi:methodLegacyGetView` | Adapter 迁到 `cp5` 包，原包过滤把它排掉 | `1c2c757d04` |
| `WeMomentsApi:classVfs` | `readFileAsString` 日志串已删除（0 引用） | `2268c7fc61` |
| `Themes:classSmileyTabAdapter` | `setSelection: %s` 已删除，且无 `allowFailure` 存在装挂风险 | `c78844b45f` |
| `WeMomentsContextMenuApi` ×3 | 实现类被 R8 混淆进 `zk4` 包（原包名只剩字符串常量） | `1191d8b800` |
| `RemoveMessageBatchForwardLimit` | hook 的 `MvvmSelectContactUI` 在 8.0.80 不存在，功能一直失效 | `2bc5e80202` |

## 4. 剩余 19 项 FAIL 的逐项结论

**全部项均带 `allowFailure = true`，在 8.0.80 上静默降级，不会崩溃。**

| 项 | 结论 |
|---|---|
| `WeServiceApi:classStorageFeatureService` | 工具受限（`addMethod{ returnType{ usingEqStrings } }` 嵌套子句），功能实际正常 |
| `AutoAcceptFriendRequests:methodVerifyOkClick` | 串已消失（`MicroMsg.VerifyUserUtil` / `verify ok clicked`），作者已留占位降级 |
| `AutoAddNearbyFriends:methodCreateMenu / methodMenuOnClick` | 「附近的人」8.0.79 起改版，特征串已不存在（源码注释已写明） |
| `HideContacts:methodFinderLikeDrawerRefresh` | 喜欢抽屉 presenter 不再引用 `FinderLikeDrawerBuilder`；数据加载已迁走 |
| `HideContacts:methodVoipMpInsertMsg` | `MicroMsg.VoIPMP.Launcher` 随 VoIPMP 重构消失 |
| `HideContacts:methodMultiTalkOnInvite / methodExitMultiTalk` | MultiTalk 模块被 Flutter 实现替换 |
| `DisableShareScreenshotToast:methodDisplayToast` | `showShareTongue...` 串消失；TAG 在 10 个类内分散 |
| `PipVoip:classMultiTalkViewModel` 等 7 项 | 见下节 |
| `RemoveLimitsDuringCalls:methodCheckDeviceUsing` 等 3 项 | 源码注释：「老版本微信（8.0.7x 早期）才有，新版本已删除，允许失败」 |

## 5. 微信 8.0.80 通话架构变化（PipVoip 失效根因）

```
旧（≤8.0.79）                          新（8.0.80）
com.tencent.mm.plugin.multitalk        com.tencent.mm.voipmp.v2
  ├ MultiTalkMainUI     ← 已删除          ├ VoIPMPCoreV2               核心控制器
  └ MultiTalkUIViewModel ← 已删除         ├ floatcard/subfloat/        31 类，应用内悬浮窗
      ├ onMicClick                        │   ├ VoIPMPFloatSlotContainer
      ├ onCameraClick                     │   └ VoIPMPSubFloatWindowView
      └ mic/camera 状态字段                ├ render/VoIPMPVoIPVideoView 视频渲染
                                          ├ audio/backend/OboeAudio*   音频
                                          └ multitalk/ui/  仅剩加成员/选联系人
com.tencent.pigeon.voipmp              ← Flutter 桥（Pigeon 代码生成）
  ├ VoIPMPV2DeviceFlutterApi            Native→Flutter 状态通知
  ├ VoIPMPScreenProjectPipHostApi       屏幕共享 PiP
  ├ VoIPMPGestureHostApi                手势/折叠屏
  └ VoIPMPRingtoneHostApi               铃声
```

- 多人通话界面**不再是独立 Activity**，改为聊天页内嵌 Flutter View，原 PiP 载体消失；
- mic/camera 控制**不在 Pigeon 桥**里（桥里只有手势、铃声、屏幕共享 PiP、语音增强），在 native 控制器；
- 服务获取方式：`fi5/n0->c(Class)` 服务定位器（`MultiTalkRoomPopupNav.a` 内可见）。

**保留可用**：`com.tencent.mm.plugin.voip.ui.VideoActivity`（47 方法）仍在，**单聊视频通话的 PiP 路径正常**。

## 6. 微信 8.0.80 普遍性失效模式

1. **孤儿串**：自 8.0.76 起微信持续删除日志调用但保留 `string_ids` 条目——串"查得到"但无 `const-string` 引用。锚点优先级应为：协议/XML 关键字 ≈ 方法名+签名 > 类 TAG 单串+结构约束 >> TAG+普通日志串。
2. **R8 包迁移**：实现类被混淆到短包名（如 `zk4`），原包名只留在字符串常量里——`searchPackages` 会反向过滤掉正确目标。
3. **模块边界重构**：功能整体搬迁（MultiTalk → voipmp.v2 + Flutter），旧 hook 目标被物理删除。
4. **方法/构造器签名漂移**：参数个数变化（`sendXmlAppMsg` 12→13 参、`NetSceneSendMsg` 5→6 参）。

## 7. 复现方式

```bash
# 1. 解包 APK 的 dex
mkdir -p /tmp/wx80 && python3 -c "
import zipfile,os
z=zipfile.ZipFile('wx80.apk')
for e in z.namelist():
    if e.startswith('classes') and e.endswith('.dex'):
        open('/tmp/wx80/'+os.path.basename(e),'wb').write(z.read(e))"

# 2. 抽取委托 + 离线校验
python3 adaptation/tools/extract_props.py
python3 adaptation/tools/verify_props.py 8.0.80
python3 adaptation/tools/classify_fail.py
```
