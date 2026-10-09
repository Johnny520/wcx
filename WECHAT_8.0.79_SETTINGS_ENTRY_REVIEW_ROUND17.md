# WCX / WeChat 8.0.79 设置入口兼容性审查（第十七轮）

## 本轮直接检查的输入

- 用户上传的微信 APK：`微信_8.0.79.apk`
- APK 中检测到 18 个 DEX 文件。
- DEX 字符串中存在 `8.0.79`、`com.tencent.mm.plugin.setting.ui.setting_new.settings.SettingGroupMain`、`SettingAdditionHeaderSearch`、`SettingGroupPersonalInfo` 和 `SettingGroup_Main_AccountInfo` 等与设置页有关的标识。
- 说明：当前容器没有 jadx/apktool/dexdump 等完整反编译工具，因此本轮通过 APK 的 DEX 字符串与 WCX 源码进行静态交叉检查；这不等同于完整反编译或运行验证。

## 修复

1. 旧版设置入口 key 从共享命名 `wekit_settings_entry` 改为 WCX 专属 `wcx_settings_entry_v1`，降低与基于相同代码的其他模块点击拦截冲突。
2. 新版设置入口 key 从测试/WeKit 风格的 `SettingGroup_Main_WeKitTest1` 改为 `SettingGroup_Main_WCX_Settings_v1`。
3. 移除将 `SettingGroupPersonalInfo` 作为插入锚点的额外 Hook。多个模块同时 Hook 同一个内置设置项的 `SettingLocation` 方法时，可能出现后注册者覆盖前者的定位结果；现在 WCX 通过自身注册项加入 `SettingGroupMain`，不再修改该内置类的定位方法。
4. 保留 WCX 自己的 `SettingGroupMain` 注册项、点击回调和设置页面打开逻辑。

## 验证

- 静态断言：旧 key 与测试 key 在活动源码中不再使用。
- 静态断言：WCX 新旧设置入口 key 均唯一且仍连接原点击处理逻辑。
- 静态断言：新入口不再设置 `childClass = SettingGroupPersonalInfo::class.java`。
- ZIP 完整性检查通过。

## 未验证 / 已知限制

- 没有可用的完整 Android/Gradle 构建环境，尚未完成整个项目编译或 APK 打包。
- 没有在微信 8.0.79 实机上与其他模块并装验证。
- 由于没有完整反编译工具，本轮不能保证微信 8.0.79 的设置页面内部排序逻辑与所有第三方模块实现完全兼容；只消除了可从 WCX 源码直接确认的共享 key 与共享锚点 Hook 风险。
