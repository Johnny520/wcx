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
| `/tmp/wx*.dex` | 解压后的 dex（临时，分析完删除） |

## 五、新版本适配 SOP（8.0.x 出新直链时照此走）

1. 下载 APK（curl -sSL，约 2 分钟）→ 解压 dex 到 `/tmp/wxNN`
2. 跑 `verify_anchor.py` 比对锚点矩阵 → 列出失效/差异锚点
3. 逐个用 androguard dump 真目标，产出该版本的多候选/新特征
4. 多命中一律走 `allowMultiple + 候选校验（择优或全 hook）+ allowFailure 跳过`，禁止 `error()` 抛出
5. 该版本 SDK 层「不支持」必须能干净降级，不阻断其它功能
6. 把**直链 + 逐项结论**回填本文件（保留）
