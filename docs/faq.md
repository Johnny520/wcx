# 常见问题

## 1. 模块不加载, 日志也没有报错

按照 [此处说明](installation.md#修复微信热更新导致的模块不加载) 进行修复。

## 2. 我的微信突然卡得要死, 狂吃内存

尝试禁用「Xposed API 调用保护」和「隐藏应用列表」。

## 3. 模块数据在哪?

`/sdcard/Android/data/<宿主包名>/WeKit`

## 4. 不受支持的旧版本启动一直弹 DEX 缓存更新怎么办?

模块通用设置启用 「禁用版本适配」 或更新到 >= 8.0.69。

## 5. 「聊天/发送卡片消息」在哪里?

合并进了 [「聊天/聊天输入栏增强」](features/chat/chat-input-bar-enhancements.md)。

## 6. LSPosed 提示「此模块是为较新的 Xposed 版本设计的, 因此某些功能可能无法使用」

忽略即可; 模块支持全部 Xposed API 版本。

## 7. 怎么让我的界面恢复正常???? (愚人节彩蛋)

「模块设置 -> 投降」。

## 8. 模块出现问题 (例如找不到入口, 功能失效) 怎么办?

请参考 [问题反馈指南](bug-report-guide.md) 提交 Issue。

## 9. 如何解密微信数据库?

1. 开启「模块设置 -> 调试 -> 详细日志」并重启微信
2. 在日志中寻找:

    ```none
    WeDatabaseApi: openDatabase() called with: name=/data/user/0/com.tencent.mm/MicroMsg/xxxxxxxxx/EnMicroMsg.db, password=xxxxxxx, cipherSpec=0,false,0,4000,1024
    ```

3. 使用 `sqlcipher` 逐行执行 (不要连续输入多行):

    ```bash
    sqlcipher ./EnMicroMsg.db

    PRAGMA key = 'xxxxxxx';
    PRAGMA cipher_compatibility = 1;

    ATTACH DATABASE 'decrypted_wechat.db' AS decrypted KEY '';

    SELECT sqlcipher_export('decrypted');
    DETACH DATABASE decrypted;

    .exit
    ```

4. 用 `DB Browser for SQLite` 或类似工具打开 `decrypted_wechat.db`

## 10. 「指纹支付」在分身微信里加解密崩溃

原因为 ROM 提供的应用分身功能不兼容指纹, 不会修复

## 11. 微信频繁提示「无法连接到服务器(1,-15)，请检查你的网络或稍后重试」

这是**微信自己**的网络层提示（错误类型 `1`、错误码 `-15`），不是模块弹出的：模块源码里没有这句文案，也**没有任何功能 hook 微信的网络层**（ConnectivityManager、Socket、MMTLS、DNS、网络状态判断均未涉及）。

按可能性排序，常见原因有：

1. 系统 / ROM 的省电策略、智能省流量、后台联网限制
2. VPN、代理、广告拦截或 DNS 过滤工具（含系统自带的「私人 DNS」）
3. 网络切换不稳定：WiFi 与移动数据并存、IPv6 时好时坏、信号弱时反复重连
4. 微信服务端瞬时波动，稍后自动恢复

需要排除模块因素时，按下面顺序验证：

1. 关闭 **「系统与隐私 / 省电模式」**。这个功能会拦截微信申请 CPU 唤醒锁，后台或熄屏时可能导致长连接保不住，表现就是消息延迟、偶发「无法连接到服务器」
2. 关闭 **「系统与隐私 / 环境伪装」** 与 **「阻止 Native 检测」**（两者正常不影响联网，仅用于对比）
3. 在 LSPosed 里取消勾选微信、重启微信后再观察一段时间

如果做完上面三步仍然频繁出现，可以判定与模块无关，按上面 1~4 排查系统与网络环境即可。

## 12. 某个功能在新版微信上开了也没反应

部分功能依赖微信内部实现，而微信会在版本更新里直接删掉整个模块。这种情况模块无法"修"，表现是开关开着但没有任何效果。

模块的应对方式：

1. 开关会拒绝开启并提示「当前微信版本不支持……」（例如群通话拆分）
2. 「模块自检 → 一键修复」的报告里，这类项目标为 `❌ 需开发者适配`，并说明重试无效

已知的微信侧删除（实测 8.0.71 ~ 8.0.79 七个版本 APK 得出）：

| 微信版本 | 微信侧变化 | 受影响的功能 |
| --- | --- | --- |
| 8.0.77 起 | 删除多人通话模块（MultiTalk / ILink） | 群通话拆分、通话小窗里的多人通话部分、密友功能的通话隐藏 |
| 8.0.78 起 | 删除表情面板的 `setSelection: %s` | 美化 - 表情面板 Tab 图标 |
| 8.0.79 起 | 「附近的人」页面改版（`NearbyPersonUIC`、`NearbySayHiListUI` 均已不存在） | 自动添加附近的人 |

排查顺序：

1. 先看开关是否直接提示不支持 —— 提示了就不要再反复开关
2. 再跑一次「一键修复」看报告，标 `❌ 需开发者适配` 的项目就是微信侧变了
3. 需要继续用这类功能，只能停留在仍支持的微信版本

## 13. 其他问题

[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Johnny520/wcx)
