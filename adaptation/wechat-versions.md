# 微信安卓版 · 全功能适配资产台账

> 用途：每个微信版本出新包后，用户会甩一条 APK 直链过来，要求做**全功能适配**。
> 本目录保留：官方下载直链、逐版本验证结论、锚点矩阵结果、分析工具链。
> **维护规则：每新增一个版本，必须在此登记直链与验证结论，不允许只分析不留档。**

## 一、官方直链（dldir1v6.qq.com，arm64）

| 版本 | URL | 直链(200) | 分析深度 |
|---|---|---|---|
| 8.0.71 | `https://dldir1v6.qq.com/weixin/android/weixin8071android3080_0x28004733_arm64.apk` | ✅ | 锚点矩阵 |
| 8.0.72 | `https://dldir1v6.qq.com/weixin/android/weixin8072android3100_0x28004833_arm64.apk` | ✅ | 锚点矩阵 |
| 8.0.74 | `https://dldir1v6.qq.com/weixin/android/weixin8074android3120_0x28004a36_arm64.apk` | ✅ | 锚点矩阵 |
| 8.0.76 | `https://dldir1v6.qq.com/weixin/android/weixin8076android3141_0x28004c31_arm64.apk` | ✅ | **全套分析**（code 3141，用户在用） |
| 8.0.77 | `https://dldir1v6.qq.com/weixin/android/weixin8077android3160_0x28004d30_arm64.apk` | ✅ | 锚点矩阵 |
| 8.0.78 | `https://dldir1v6.qq.com/weixin/android/weixin8078android3180_0x28004e32_arm64.apk` | ✅ | 锚点矩阵 |
| 8.0.79 | `https://dldir1v6.qq.com/weixin/android/weixin8079android3200_0x28004f30_arm64.apk` | ✅ | **全套分析** |
| 8.0.80 | `https://dldir1v6.qq.com/weixin/android/weixin8080android3220_0x28005030_arm64.apk` | ✅ | **全套分析 + 全量委托校验** |

- 7 条直链已于 2026-10-29 逐条 `curl -I` 实测 200（不是推测）。
- **直链索引来源**（新版本出现时先来这里取，省去猜 hash）：
  - `github.com/marstrix/wechat-android-versions`（`version.json`，持续维护）
  - `github.com/DJB-Developer/wechat-android-history-versions`
  - URL 规律 `weixin{ver}android{build}_{0x hash}_arm64.apk`，build/hash 不可推测，**必须拿真链接**。

- 命名规律：`weixin{主版本}android{buildCode}_{0x hash}_arm64.apk`，**build 与 hash 每版本都变，不可推测，必须拿真链接**。
- 每版 APK 约 250~285MB，沙箱下载实测 2.6MB/s（约 2 分钟）。分析完即删，只留结论。
- 8.0.76 还有一份 `weixin8076android3140_0x28004c30_arm64.apk`（build 3140）。

## 二、锚点矩阵（1021 个 DexKit 特征串 × 7 版本）

2026-10-25 实测，工具 `verify_anchor.py`（workspace 根目录）：

| 版本 | 命中锚点数 |
|---|---|
| 8.0.71 | 696 |
| 8.0.72 | 696 |
| 8.0.74 | 697 |
| 8.0.76 | 696 |
| 8.0.77 | 683 |
| 8.0.78 | 682 |
| 8.0.79 | 677 |

- 7 版本全部存在：**675**；有版本差异：**23**
- 差异示例：`NearbyPersonUIC` / `showLiveBottomSheet create menu.` / `NearbySayHiListUI` 止于 8.0.78（自动添加附近的人）；`setSelection: %s` 止于 8.0.77（表情 Tab 美化）；`CopyVideoTask ori[%s]...`、`get video file name, dataString` 止于 8.0.78（视频链路已换方案）
- 结论：**97% 锚点全版本稳定**，23 个差异锚点需逐版本备选。

## 三、关键签名结论（跨版本）

### 3.1 ImportMultiVideo（多视频发送）
- **没有 Thread 版实现类**（8.0.76 / 8.0.79 均已重构），legacy 的 7 参 `createInstance` 路径在新版本**不存在**，由 Hchat 服务路径（`sendViaService`）接管。
- 旧特征 `MicroMsg.GetVideoMetadata` 会命中**两个类**（参数容器 + Uri 工具类）：

| 版本 | 参数容器（真目标） | 误命中 |
|---|---|---|
| 8.0.76 | `com.tencent.mm.pluginsdk.model.b3`（18 字段，VideoTransPara+ABAPrams，`<init>()V`+`a/b/c()Z`） | `gp.b`（Uri/缩略图工具，0 字段） |
| 8.0.79 | `com.tencent.mm.pluginsdk.model.f3`（15 字段，同构） | `mp.b` |

- 新特征（三串 AND）`MicroMsg.ImportMultiVideo` + `RemuxInfo` + `OriginMediaInfo` **唯一命中真目标**（8.0.76→b3、8.0.79→f3）；多命中场景另有 `allowMultiple` + 签名校验 + 干净跳过。

### 3.4 双版本失效锚点修复（2026-10-29，commit `5ec34e1d`）

用 8.0.76 与 8.0.79 两个真机 APK 双向对照，逐个修掉「解析失败」的 key：

| key | 8.0.76 | 8.0.79 | 根因 | 修法 |
|---|---|---|---|---|
| `WeMomentsApi:classUploadPackHelper` | ✅ `sns.model.y7` | ❌ 误定位 `sns.ui.fake` 的 `kk4/j` | 锚点串 `commit sns info ret %d, typeFlag %d sightMd5 %s` 被 `BackgroundVideoFinishUploader` 复用 | 改 `MicroMsg.UploadPackHelper` + `addImageMediaObjByUrl`（双版本均唯一命中） |
| `WeMomentsApi:methodAddSightObjectByPath` | `f(4×String)Z` | `f(5×String)Z` | 参数个数漂移 | 去掉 `paramCount/paramTypes`，调用处按 `parameterCount` 补空串 |
| `WeMessageApi:classChattingDataAdapter` | ❌ | ❌ | 两串在类中属**不同方法**，DexKit 类匹配是方法级 AND | 改单串 `[handleMsgChange] isLockNotify:` + `searchPackages(ui.chatting.adapter)` |
| `ForceTabletMode:methodIsTablet` | ❌ | ❌ | `eebbk`、`isRoyoleFoldableDevice!!!` 已成**孤儿串**（有串无引用） | 单串 `Lenovo TB-9707F`（76 → `ui.bk.B`、79 → `ui.j9.a`），补 allowFailure + isPlaceholder 守卫 |
| `PipVoip:classMultiTalkViewModel` / `methodMultiTalkMic` | ❌ | ❌ | `onMicClick, cur state: ` 是孤儿串；8.0.79 整个 multitalk UI 日志体系消失 | 改 `onMicClick, need notify iLink Engine`（76 → `multitalk.ui.b0.a7(Z)V`，79 干净降级） |
| `DisableMainPagePullDown:methodOnTouchEvent` | ❌ | ❌ | 声明后从未使用；8.0.79 LauncherUI 已无 `onTouchEvent` | 删除（僵尸代码） |
| `DisableMainPagePullDown:methodOpenMiniProgramPanel` | ❌ | ❌ | `openTaskList` 字样两版 dex 均消失 | 删除（保留「反射 openTaskList → 手势模拟」两段式） |
| `CustomLocalFriendAvatars:methodFeatureAvatarSimple1` | ✅ `avatar.w.lj` | ❌ | 8.0.79 移除内部 `View.invalidate` 调用，同形状方法变 2 个 | 去 `addInvoke`，`allowMultiple` + `candidateMethods()` 全候选 hook |

**朋友圈（WeMoments）在 8.0.79 上是整条链路连带失效**：`classUploadPackHelper` 定位错 → 构造器/`commit`/`setContentDes`/`setSdkId`/`setUploadList`/`addImageMediaObjByPath` 全部解析失败 → 发文字/图文/视频朋友圈全部不可用。修正类锚点后 7 个委托在两版均一一对应（`y7` ↔ `i8`）。

**三条硬教训（已写入源码注释）**：
1. **DexKit 类匹配的 `usingEqStrings` 是方法级 AND** —— 两串若位于同一个类的不同方法里，永远不命中（`classChattingDataAdapter` 就是这种）。
2. **「字符串存在于 dex」≠「被方法引用」** —— 版本重构后常留下孤儿串（`string_ids` 里有、但没有任何 `const-string` 指向它），只看存在性会误判锚点仍有效。判断必须扫 const-string 引用者。
3. **日志串会被别的类复用** —— 类锚点要用「该类独有」的串组合，否则跨版本会被新类抢走（`commit sns info ret ...` 被 `BackgroundVideoFinishUploader` 复用即此例）。

### 3.5 全量委托离线校验（2026-10-29，8.0.80 正式版）

**方法**：不再靠跑日志碰运气，改为把源码里全部 DexKit 委托的特征（串 / 包 / 签名 / 声明类）抽出来，
用自研 DEX 解析器在真机 dex 上**复现 DexKit 匹配语义**（方法级 AND），一次跑完 515 个委托。

- 工具链（`adaptation/tools/`）：`extract_props.py`（抽取委托）→ `verify_props.py`（复现匹配）→
  `classify_fail.py`（根因分类）→ `suggest2.py`（给出「只保留存活串」后的唯一解建议）
- 详细报告：**`adaptation/anchor-audit-8.0.80.md`**

三版本总体（OK / WARN / SIG / FAIL / SKIP，共 515）：

| 版本 | OK | WARN | SIG | FAIL | SKIP |
|---|---|---|---|---|---|
| 8.0.76 | 253 | 33 | 10 | 118 | 101 |
| 8.0.79 | 259 | 33 | 9 | 113 | 101 |
| 8.0.80 | 240 | 37 | 9 | 128 | 101 |

（表内是本轮修复**之后**的数字。本轮起点：8.0.76 OK 214 / 8.0.79 OK 226 / 8.0.80 OK 202。）

**本轮（2026-10-29）额外修掉的两条核心链路**：
- `classConversationStorage`：两串（`rconversation` / `PRAGMA table_info( rconversation)`）在类里属不同方法，
  方法级 AND 恒失败 → 改单 PRAGMA 串，三版唯一命中（76/79 `storage.l4`、80 `storage.m4`），
  连带 `methodDelChatContact` / `methodIsPlacedTop` / `methodUpdateUnreadByTalker`（76/79）恢复；
- 取库链 `mCoreStorage not initialized!` 已是孤儿串 → 换 `Kernel not initialized by MMApplication!`，
  `WeDatabaseApi:methodGetStorage` 与 `WeMessageApi:methodMmKernelGetStorage` 三版均恢复。

**核心结论：8.0.80 上关键链路可用** —— 发消息（`classNetSceneSendMsg`）、朋友圈
（`classUploadPackHelper` / `methodCommit` / `classSnsMediaObj`）、多视频、强制平板、主题
（`classEnterpriseBizViewItem`）全部 OK；回归集中在语音/视频号/浮窗等外围功能。

**8.0.80 相对 8.0.79 回归 62 项**，根因分布：孤儿串 54 项、串分散 8 项 —— 绝大多数又是「日志被裁」。

### ⚠ 微信日志裁剪趋势（重要）

自 8.0.76 起微信持续删除日志调用但**保留 string_ids 条目**，形成「孤儿串」。
这类串在 dex 里查得到、却没有任何 `const-string` 指向它，`TAG + 日志串` 形式的锚点必然失效。
**每版适配第一步就是跑一遍全量校验，不要相信「串存在」这种粗筛。**

锚点优先级（长期）：协议/XML 关键字 ≈ 方法名+签名 > 类 TAG 单串+结构约束 >> TAG+普通日志串。

### 3.2 notifyConversationChanged（会话变更通知）
- 目标形状 `(int, ConversationStorage超类, Object) → void`。
- 8.0.76：`ConversationStorage` = `com.tencent.mm.storage.l4`（classes9），超类 `s85.a1`，目标 = **`s85.a1.b(I Ls85/a1; Ljava/lang/Object;)V`** ✅ 特征描述仍然匹配。
- 该 key 在 8.0.76 反复抛 `Method not found` 的根因是**解析未跑（缓存未建立）时 descriptor=null 却未被判定为不可用**，已由 `isPlaceholder` 纳入「尚未解析」语义根治（commit 9146ce4a）。

### 3.3 多命中 key 清单（已全部兼容化）
| key | 命中数(8.0.76) | 处理 |
|---|---|---|
| `WeMessageApi:classImportMultiVideo` | 2 | 三串特征 + 签名校验择优 + 不支持则跳过 |
| `RemoveLimitsDuringCalls:methodIsCameraUsing` | 2 | allowMultiple + 每个 boolean 候选全 hook |
| `RemoveLimitsDuringCalls:methodCheckAppBrandVoiceUsing2` | 2 | 同上 |
| `BlockVoipRingtone:methodPlaySoundByLog` | 3 | 回退路径 3 个候选全 hook |

## 四、分析工具链（workspace）

| 文件 | 用途 |
|---|---|
| `verify_anchor.py` | 逐 dex 扫锚点串覆盖率（1021×N 矩阵） |
| `dexlib.py` | 自研 DEX 头/字符串/类型/方法表解析（不依赖 androguard，秒级） |
| `find_defs.py` | 类定义定位（哪个 dex 有某类） |
| `androguard 4.1.4` | 深度 dump 类成员/const-string 指令（需 pip 重装，沙箱重置后注意） |
| `scanstr.py` | 精确串 × 方法交叉定位（找出「同时引用指定精确串」的方法，支持包过滤） |
| `dumcls.py` | dump 指定类的全部方法签名 + 方法内 const-string |
| `extract_props.py` | 抽取源码全部 DexKit 委托特征（串/包/签名/declaredClass）→ `dex_props.json` |
| `verify_props.py` | 在真机 dex 上复现 DexKit 匹配语义，输出 OK/WARN/SIG/FAIL/SKIP |
| `classify_fail.py` | FAIL 根因分类：串消失 / 孤儿串 / 串分散 |
| `suggest2.py` | 给出「只保留存活串 + 原签名」后的唯一命中建议 |
| `/tmp/wx*.dex` | 解压后的 dex（临时，分析完删除） |

## 五、新版本适配 SOP（8.0.x 出新直链时照此走）

1. 下载 APK（curl -sSL，约 2 分钟）→ 解压 dex 到 `/tmp/wxNN`
2. **跑全量委托校验**：`python3 extract_props.py` → `python3 verify_props.py <版本>` → `python3 classify_fail.py`
   → 得到该版本逐项 OK/WARN/SIG/FAIL 与 FAIL 根因（孤儿串 / 串消失 / 串分散）
3. 逐个 dump 真目标，产出该版本的多候选/新特征；**锚点串必须先验「是否被方法引用」**（孤儿串要弃用）
4. 多命中一律走 `allowMultiple + 候选校验（择优或全 hook）+ allowFailure 跳过`，禁止 `error()` 抛出
5. 该版本 SDK 层「不支持」必须能干净降级，不阻断其它功能
6. 把**直链 + 逐项结论**回填本文件（保留）
