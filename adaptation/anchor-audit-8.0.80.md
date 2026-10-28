# 微信 8.0.80 适配体检报告

> 生成方式：离线复现 DexKit 匹配语义，对源码全部 DexKit 委托逐项验证（不是跑日志）。
> 工具：`adaptation/tools/`（`extract_props.py` → `verify_props.py` → `classify_fail.py` → `suggest2.py`）。

## 一、三版本总体对比

| 版本 | 委托数 | OK | WARN(类级命中但无单方法共现) | SIG(串命中但签名不符) | FAIL | SKIP(无串锚点) |
|---|---|---|---|---|---|---|
| 8.0.76 | 515 | 250 | 34 | 10 | 120 | 101 |
| 8.0.79 | 515 | 259 | 33 | 9 | 113 | 101 |
| 8.0.80 | 515 | 237 | 38 | 9 | 130 | 101 |

说明：`WARN` 按 DexKit 的方法级 AND 语义视为**实际会失败**（已由 `classChattingDataAdapter` 的线上日志实证）。

## 二、核心功能体检（8.0.80）

| 功能 | key | .76 | .79 | .80 |
|---|---|---|---|---|
| 发消息 | `WeMessageApi:classNetSceneSendMsg` | OK | OK | OK |
| 发消息 | `WeMessageApi:classMsgInfo` | FAIL | FAIL | OK |
| 发消息 | `WeMessageApi:classMsgInfoStorage` | FAIL | FAIL | OK |
| 数据库 | `WeMessageApi:classMmKernel` | FAIL | FAIL | WARN |
| 数据库 | `WeDatabaseApi:classMmKernel` | WARN | FAIL | WARN |
| 数据库 | `WeDatabaseApi:methodGetStorage` | FAIL | OK | FAIL |
| 朋友圈 | `WeMomentsApi:classUploadPackHelper` | OK | OK | OK |
| 朋友圈 | `WeMomentsApi:methodCommit` | OK | OK | OK |
| 朋友圈 | `WeMomentsApi:methodAddImageMediaObjByPath` | OK | OK | WARN |
| 朋友圈 | `WeMomentsApi:classSnsMediaObj` | OK | OK | OK |
| 会话 | `WeConversationApi:classConversationStorage` | WARN | OK | WARN |
| 消息 | `WeMessageApi:classChattingDataAdapter` | OK | OK | OK |
| 多视频 | `WeMessageApi:classImportMultiVideo` | OK | OK | OK |
| 平板 | `ForceTabletMode:methodIsTablet` | OK | OK | OK |
| 主题 | `Themes:classEnterpriseBizViewItem` | OK | OK | OK |
| 密友 | `HideContacts:methodNormalContactCount` | FAIL | WARN | WARN |
| 脚本 | `PipVoip:methodMultiTalkMic` | OK | FAIL | FAIL |

## 三、8.0.80 相对 8.0.79 的回归清单

共 **49** 项。根因分布：{'A_分散': 6, 'C_孤儿串': 43}

| key | 79 → 80 | 根因 | 缺失/孤儿串 |
|---|---|---|---|
| `BlockVoipRingtone:methodStartRing` | WARN → FAIL | C_孤儿串 | startRing() called with: username =  |
| `CustomSourceApp:methodSnsUploadUIInitView` | OK → FAIL | C_孤儿串 | customizeInputView |
| `DisableLowAvailableStorageDetection:classStaticValuesHolder` | OK → FAIL | A_分散 |  |
| `DisablePinnedChatsCollapsing:methodIfShouldAddCollapseChatItem` | OK → FAIL | C_孤儿串 | checkIfShowFoldItem, ifShow: |
| `DisplayDetails:classImproveInteractionLayout` | OK → FAIL | C_孤儿串 | MicroMsg.Improve.InteractionLayout |
| `HideContacts:methodFinderLikeDrawerLoadMore` | WARN → FAIL | A_分散 |  |
| `HideContacts:methodFinderLikeDrawerRefresh` | OK → FAIL | A_分散 |  |
| `HideContacts:methodVoipAcceptIncomingCall` | WARN → FAIL | C_孤儿串 | acceptIncomingCal, roomInfo: |
| `HideContacts:methodVoipMpLaunchIncomingCard` | OK → FAIL | C_孤儿串 | launchInComingCardAsync:  |
| `HideContacts:methodVoipMpStartRing` | WARN → FAIL | C_孤儿串 | startRing() called with: username =  |
| `HideContacts:methodVoipServiceExSetInviteContent` | OK → FAIL | C_孤儿串 | Failed to setInviteContent during calling, status = |
| `HideContacts:methodVoipStartAcceptVoip` | OK → FAIL | C_孤儿串 | startAcceptVoIP, roomInfo: |
| `MentionMembers:methodMergeMsgSourceNode` | OK → FAIL | C_孤儿串 | (?s)<alnode[^>]*>.*?</alnode> |
| `MonitorGroupMemberOperations:methodHandleSpanClick` | OK → FAIL | C_孤儿串 | LuckyMoneyUriSpanHandler handleSpanClick() clickCallback == null |
| `PipVoip:classFlutterVoipManager` | WARN → FAIL | C_孤儿串 | qipeng, enableMute. |
| `PipVoip:fieldVoipMuted` | OK → FAIL | C_孤儿串 | qipeng, enableMute.; qipeng, disableMute. |
| `PipVoip:methodFlutterVoipAttachedToActivity` | OK → FAIL | C_孤儿串 | init flutter voip mgr |
| `PipVoip:methodFlutterVoipReattachedToActivity` | OK → FAIL | C_孤儿串 | onReattachedToActivityForConfigChanges: |
| `PipVoip:methodSetVoipMuted` | OK → FAIL | C_孤儿串 | qipeng, enableMute.; qipeng, disableMute. |
| `QuotedMessageDirectJump:classEnumQuoteJumpToPositionSource` | OK → FAIL | C_孤儿串 | QuoteLongClickFromQuoteView; QuoteClickFromTextPreviewLocateView |
| `SpoofHostVersion:ctorCgiLaunchWxaAppFunc1122` | WARN → FAIL | C_孤儿串 | <init> cgiHash[%d], username[%s] appId[%s] sync[%b] sessionId[%s] inst |
| `SuperConversationPinning:classConversation` | OK → FAIL | C_孤儿串 | [%s}]get mmkv editmsg is [%s],get conv editmsg is [%s] |
| `UnlockCustomEmojiLimit:classCgiBack` | OK → FAIL | C_孤儿串 | CgiBack{errType= |
| `VoiceMessagePlaybackOptimization:classAutoPlay` | OK → FAIL | C_孤儿串 | onSensorEvent, isON: |
| `VoiceMessagePlaybackOptimization:methodSensorCallback` | OK → FAIL | C_孤儿串 | onSensorEvent, isON: |
| `WeAuthApi:classNetSceneJSLogin` | OK → FAIL | A_分散 |  |
| `WeChatMessageContextMenuApi:methodMultiSelectMenuItem` | OK → FAIL | C_孤儿串 | MicroMsg.ChattingForwardMultiMsgLogic; sendMultiMsg fromTalker: |
| `WeChatMessageViewApi:methodChatItemOnBindView` | OK → FAIL | A_分散 |  |
| `WeContactLabelApi:classNetSceneAddContactLabel` | OK → FAIL | C_孤儿串 | /cgi-bin/micromsg-bin/addcontactlabel |
| `WeContactLabelApi:classNetSceneModifyContactLabelList` | WARN → FAIL | C_孤儿串 | /cgi-bin/micromsg-bin/modifycontactlabellist |
| `WeConversationApi:methodBuildModContactOplog` | OK → FAIL | C_孤儿串 | oplog modContact user:%s remark:%s BitVal:%d BitValue2:%s isInConvBox: |
| `WeConversationApi:methodGetConversationByTalker` | OK → FAIL | C_孤儿串 | get null with username: |
| `WeConversationApi:methodSetNoDnd` | OK → FAIL | C_孤儿串 | OpenImOpLogLogic OpenIMModContactMuteOplog username:%s switch cancel |
| `WeConversationApi:methodUpdateUnreadByTalker` | OK → FAIL | C_孤儿串 | updateUnreadByTalker %s |
| `WeDatabaseApi:methodGetStorage` | OK → FAIL | C_孤儿串 | mCoreStorage not initialized! |
| `WeMessageApi:ctorNetSceneSendMsgLocation` | OK → FAIL | A_分散 |  |
| `WeMessageApi:methodMmKernelGetStorage` | OK → FAIL | C_孤儿串 | mCoreStorage not initialized! |
| `WeMessageApi:methodRunVoiceService` | OK → FAIL | C_孤儿串 | run() %s |
| `WeMomentsApi:classImproveInteractionLayout` | OK → FAIL | C_孤儿串 | MicroMsg.Improve.InteractionLayout |
| `WeMomentsApi:classSnsSourceScene` | OK → FAIL | C_孤儿串 | comment_detail |
| `WeMomentsApi:classSnsUploadUi` | WARN → FAIL | C_孤儿串 | customizeInputView |
| `WeMomentsApi:classXs4` | OK → FAIL | C_孤儿串 | musicOperationUrl |
| `WeServiceApi:methodSaveEmojiThumb` | OK → FAIL | C_孤儿串 | save emoji thumb error |
| `WeShortVideosShareMenuApi:methodCreateMenu1` | OK → FAIL | C_孤儿串 | pos is error  |
| `WeShortVideosShareMenuApi:methodCreateMenu3` | OK → FAIL | C_孤儿串 | getCreateSecondMoreMenuListener: username= |
| `WeShortVideosShareMenuApi:methodOnSelectMenuItem1` | OK → FAIL | C_孤儿串 | [getMoreMenuItemSelectedListener] feed  |
| `WeShortVideosShareMenuApi:methodOnSelectMenuItem2` | WARN → FAIL | C_孤儿串 | getMoreMenuItemSelectedListener feed  |
| `WeTextStatusApi:methodSetTextStatusIcon` | OK → FAIL | C_孤儿串 | Super calls with default arguments not supported in this target, funct |
| `WeTextStatusApi:methodTextStatusTopicInfo` | OK → FAIL | C_孤儿串 | Super calls with default arguments not supported in this target, funct |

## 四、根因专题：微信在持续裁剪日志串

自 8.0.76 起，微信大量**删除日志调用但残留 string_ids 条目**，形成「孤儿串」：
字符串在 dex 里查得到，但没有任何 `const-string` 指令引用它。

这直接打穿了所有 `TAG + 日志串` 形式的锚点（本项目绝大多数 matcher 都是这种写法）。

判定方法（必须照做）：
1. 先确认串在 `string_ids` 存在（旧做法到此为止，误判为有效）
2. **再确认串被某个方法的 `const-string` 引用**（`adaptation/tools/dumcls.py` / `verify_props.py`）
3. 只有「被引用」的串才能当锚点

已据此批量修复 20 处（删除孤儿串，仅保留存活串，且保证三版本仍唯一命中），另有 1 处因精简后
误命中无关 lambda 而回滚（`DisableResumeWatchingToast`）。

### 锚点写法建议（长期）

| 优先级 | 锚点类型 | 理由 |
|---|---|---|
| 高 | 协议/XML 关键字（`<finderFeed>`、`<msgsource>`、`/cgi-bin/xxx`） | 业务必需，不会被裁 |
| 高 | 方法名/字段名 + 签名（`paramTypes`/`returnType`/`addInvoke`） | 结构稳定，不受日志裁剪影响 |
| 中 | 类 TAG 单串 + 结构约束 | TAG 是类静态常量，通常保留 |
| 低 | TAG + 普通日志串 | 日志随时被裁，跨版本必坏 |

## 五、本轮已完成的修复

1. 朋友圈 8.0.79 整链修复（`classUploadPackHelper` 锚点被 `BackgroundVideoFinishUploader` 抢占）
2. `classNetSceneSendMsg`：`markMsgFailed for id:%d` 在 8.0.79 起消失 → 改 `MicroMsg.NetSceneSendMsg` + `Req MsgSource %s`
3. `classImportMultiVideo`：`RemuxInfo`/`OriginMediaInfo` 是孤儿串 → 改单 TAG + 多候选择优
4. `classChattingDataAdapter`：两串跨方法 AND 恒失败 + 旧串成孤儿 → 改 TAG + `[setShowHistoryMsgTipId]`
5. `ForceTabletMode`：`eebbk`/`isRoyoleFoldableDevice!!!` 孤儿 → 单串 `Lenovo TB-9707F`
6. `PipVoip`：`onMicClick, cur state: ` 孤儿 → `onMicClick, need notify iLink Engine`
7. 清理 `DisableMainPagePullDown` 两个死锚点（其中 `methodOnTouchEvent` 是从未使用的僵尸代码）
8. `CustomLocalFriendAvatars`：8.0.79 移除 `View.invalidate` → 改 allowMultiple 全候选
9. 20 处孤儿串批量精简（见台账 3.4 节）
10. 锚点迁移第一批（auto_probe 自动探查「存活串+原签名」三版唯一解）：19 项
    —— 朋友圈 SnsVideoFullPath / 导出相册、会话删除与 parentRef / UpdateUnread、主题地址项与聊天宫格、
    开屏广告跳过、WebView 特性、运动步数、进程检查、FlutterVoip 最小化、自定义头像 mvvm 加载、
    QueryByParent 真实 5 参签名、ContextMenu 创建、跳过广告等
11. 引擎修正 3 处系统性偏差（此前会批量误判）：
    · `iter_insns` 未跳过 switch/fill-array 的 payload 伪指令 → 指令流错位，const-string 批量漏检
    · `sig_params` 把描述符分隔符逗号当成参数类型 → 所有多参方法被误判「签名不符」
    · `declaredClass{}` / `superClass{}` 块内的串被当成方法体内串 → 类级约束被误用为方法级条件

## 六、待办（按优先级）

1. 8.0.80 回归 62 项：逐项找「存活串 + 结构约束」替代锚点（当前工具 `suggest2.py` 只能给出 8 项唯一解）
2. `WARN` 类项（40~46 个/版本）多为「串分散在不同方法」，需要拆成单串 + 签名约束
3. `SKIP` 76 项（无串锚点，全靠 `declaredClass` 链）需要单独建依赖图验证
4. `h/Hchat` 侧 DexFinder 特征串未纳入本报告（另一套定位链），需单独复核
