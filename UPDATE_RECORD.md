# WCX 更新记录

> 构建日期：2026-08-06
> 构建输出：Standard / Legacy 双版本 APK
> 签名：release.jks (正式签名，环境变量注入)
> 混淆：R8 代码混淆压缩 (isMinifyEnabled=true, isShrinkResources=true)
> 构建约束：所有修改互不干涉，仅改动对应功能模块

---

## 第一部分：移植WEX首页小组件 问题修复&交互新增

### 1.1 深色模式适配修复
**文件**：`WexHomeCardsFeature.kt`、`WexBeautifyFeature.kt`

- 修复小组件全部控件（日期栏、每日一图、音乐播放器、顶部搜索栏）深色模式适配
- 通过 `isSystemInDarkTheme()` 动态检测系统主题，自动切换背景、文字、边框配色
- 新增 `cardBgColor()`、`titleTextColor()`、`subtitleTextColor()`、`dividerColor()` 等动态配色方法
- 解决深色模式下白底控件显示错乱、配色不匹配问题

### 1.2 每日一图空白不显示修复
**文件**：`WexHomeCardsFeature.kt`

- 完整排查图片加载全链路：网络请求、本地缓存读取、存储权限校验、图片解码渲染
- 新增 `loadDailyImage()` 实现缓存优先加载策略
- 新增图片加载失败兜底占位图 (`android.R.drawable.ic_menu_gallery`)
- 增加详细异常日志记录 (`WeLogger`)

### 1.3 每日一图交互新增
**文件**：`WexHomeCardsFeature.kt`、`WexBeautifyFeature.kt`

- 单击：弹出大图预览弹窗，底部【取消】、【保存图片】按钮
  - 保存：将图片写入本地存储（含权限判断，权限不足弹出提示）
  - 取消：关闭弹窗
- 长按：唤起API配置弹窗，可修改图片API链接、刷新间隔
  - 配置本地持久化 (`SharedPreferences`)
  - 支持一键恢复默认API
- 单击/长按手势隔离互不混淆

---

## 第二部分：新增【微信主页侧滑侧边栏】完整功能

### 2.1 基础架构
**文件**：`HomeSidePanelFeature.kt`（新增）

- WCX美化页面新增总开关【微信主页侧滑侧边栏】，关闭则功能全部失效
- 左上角唤起按钮避让逻辑：实时检测首页左上角原生控件，自动右移安全间距
- 全部卡片、文本支持长按唤起配置弹窗，短按执行业务，长短按隔离
- 配置本地持久化，支持单项重置、全功能一键恢复默认

### 2.2 侧边栏布局（从上至下）
1. 顶部头部：头像、在线状态标签、时间日期、语录问候文本
2. 天气信息卡片模块（默认开启）
3. 横向4快捷按钮：扫一扫、收付款、收藏 + 第四个默认按钮
4. 功能条目：朋友圈、视频号、清空未读、WCX设置 + 自定义功能
5. 底部：每日一言模块

### 2.3 设置页
- 侧边栏右上角设置图标，进入独立设置页
- 每个可见组件都拥有独立开关，可单独开启关闭
- 自定义新增功能：可填写显示名称、图标、跳转目标，支持编辑、删除、排序
- 配套天气设置子页面：城市选择、自动检测、个人资料读取

### 2.4 全局约束
- 全部组件适配深浅色模式，跟随系统切换配色
- 弹窗、视图不与微信原生、模块原有功能手势/层级冲突
- 兼容主流安卓、主流微信版本

---

## 第三部分：功能页面「近期更新」板块交互优化

**文件**：`FeaturesPager.kt`

- 「近期更新」默认自动折叠收起，只保留标题栏+箭头
- 点击标题栏/箭头切换展开/收起
- 展开/折叠状态本地持久保存 (`WePrefs`)
- 原有条目文字、点击跳转逻辑完全不变
- 深浅色适配正常，无错位遮挡

---

## 第四部分：DexKit Hook专项修复

### 4.1 禁止主页下滑进入最近页面
**文件**：`DisableMainPagePullDown.kt`

- 重新定位 LauncherUI 触摸事件真实方法签名
- 更新 DexKit 匹配规则：指定包名 `com.tencent.mm.ui`、类名 `MicroMsg.LauncherUI`、参数类型 `android.view.MotionEvent`
- 添加 `allowFailure=true` 参数，查找失败不崩溃
- 在 `onEnable()` 中添加 Hook 逻辑拦截 `MotionEvent.ACTION_MOVE` 事件

### 4.2 朋友圈禁止视频自动播放增强
**文件**：`DisableVideosAutoPlay.kt`

- 优化 `methodVideoStartPlay` 和 `methodVideoPrepare` 匹配规则，使用 `addUsingString` 匹配关键词
- 新增 `methodVideoViewStartPlay` 委托，实现多重 Hook 拦截
- 覆盖预加载 + 自动播放全路径

### 4.3 DexKit全局查找失败防护
**文件**：`DexDelegates.kt`

- 为 `DexClassDelegate.find()`、`DexFieldDelegate.find()`、`DexMethodDelegate.find()`、`DexConstructorDelegate.find()` 添加全局异常捕获
- `allowFailure=true` 的委托：查找失败仅输出 WARN 级别日志，设置占位符描述符，跳过当前 Hook
- 禁止抛出 `IllegalStateException`、禁止打印完整异常堆栈
- 查找成功的原有 Hook 逻辑完全保留，不做修改

---

## 构建配置变更

**文件**：`app/build.gradle.kts`

- 添加 `aboutLibraries { offlineMode = true }` 配置，禁用网络请求，解决构建时 `rate_limit` 网络超时

---

## 输出文件

| 文件 | 大小 | 路径 |
|------|------|------|
| app-standard-arm64-v8a-release.apk | 33MB | `app/build/outputs/apk/standard/release/` |
| app-standard-armeabi-v7a-release.apk | 32MB | `app/build/outputs/apk/standard/release/` |
| app-legacy-arm64-v8a-release.apk | 33MB | `app/build/outputs/apk/legacy/release/` |
| app-legacy-armeabi-v7a-release.apk | 32MB | `app/build/outputs/apk/legacy/release/` |

---

## 变更文件清单

| 文件 | 类型 | 变更说明 |
|------|------|----------|
| `WexHomeCardsFeature.kt` | 修改 | WEX三卡深色模式适配、每日一图加载修复、交互新增 |
| `WexBeautifyFeature.kt` | 修改 | 新增每日一图API配置参数 |
| `HomeSidePanelFeature.kt` | 新增 | 微信主页侧滑侧边栏完整功能 |
| `FeaturesPager.kt` | 修改 | 近期更新板块默认折叠及状态持久化 |
| `DexDelegates.kt` | 修改 | DexKit全局异常防护 |
| `DisableMainPagePullDown.kt` | 修改 | 修复禁止主页下滑Hook |
| `DisableVideosAutoPlay.kt` | 修改 | 修复朋友圈禁止视频自动播放Hook |
| `app/build.gradle.kts` | 修改 | aboutLibraries离线模式配置 |