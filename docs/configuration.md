# 配置指南

## 入口

模块大部分功能均可直接通过模块设置 UI 进行配置。可通过以下方式中任意一种打开模块设置。

### 从微信设置进入

微信首页 -> 「我」-> 「设置」-> 「WeKit 设置」

### 从微信主页加号进入

微信首页 -> 右上角 `+` 号 -> 菜单中点击「WeKit 设置」

### 从主页浮动按钮进入

如果已启用「界面美化 / 主屏幕添加 FAB」且添加了「模块设置」菜单项: 微信首页 -> 右下角浮动按钮 -> 「模块设置」

### 从模块应用进入

如果微信包名为默认的 `com.tencent.mm`: 模块应用 -> 「打开模块设置」

## 功能设置

功能设置按功能分类排列, 每个类别下是具体的功能。可点击的功能均排序于每个分类页面最底部, 在名称旁有齿轮图标表示可点击配置。

各设置项及分组的详细说明请参见 [功能设置说明](module-settings.md#功能设置)。

## 通用设置

通用设置位于全部功能分类下方, 可点击模块本体部分行为。

详见 [通用设置说明](module-settings.md#通用设置)。

## 模块数据目录

### 一般数据

模块运行时的文件存放在以下位置:

```none
/sdcard/Android/data/<宿主包名>/WeKit/
├── logs/                     # 运行日志
├── assets/                   # 媒体资源 (需手动创建)
├── scripts_js/               # JavaScript 脚本 (需手动创建)
├── scripts_java/             # Java 脚本 (需手动创建)
├── stickers/                 # 贴纸包 (需手动创建)
├── crashes/                  # 崩溃日志 (需启用「调试/崩溃拦截」)
├── dex_cache/                # DEX 缓存 (自动生成)
└── generated_proxy_classes/  # 新版微信设置注入辅助文件 (自动生成)
```

#### 媒体资源目录

「自定义消息气泡」所用的气泡文件存放于 `<模块数据>/assets/` 目录下, 命名为 `left_bubble.9.png` `right_bubble.9.png`。

#### 脚本目录

##### JavaScript

JavaScript 脚本文件存放于 `<模块数据>/scripts_js/` 目录下, 脚本文件直接置入文件夹内。

##### Java

Java 脚本目录存放于 `<模块数据>/scripts_java/` 目录下, 该目录下每个目录放置一个脚本, 每个脚本目录至少包含 `info.prop` `main.java`。

#### 贴纸目录

贴纸存放于 `<模块数据>/stickers/` 目录下, 每个贴纸包为一个子目录。

详见 [贴纸包同步](features/chat/stickers-sync.md)。

### KV 存储

模块的配置数据 (MMKV 文件) 存放在:

- **单用户**: `/data/data/<宿主包名>/files/mmkv/{wekit_prefs,wekit_prefs.crc}`
- **多用户 (如双开微信)**: `/data/user/<宿主安卓用户 ID>/<宿主包名>/files/mmkv/{wekit_prefs,wekit_prefs.crc}`

> 如果模块功能失效或设置异常, 可以尝试清除上述 MMKV 文件 (会丢失所有设置)。

## 下一步

- [常见问题](faq.md) — 遇到异常? 先看看是不是常见问题
- [问题反馈指南](bug-report-guide.md) — 了解如何提交问题报告
- [建议反馈指南](feature-request-guide.md) — 了解如何提交功能请求或建议反馈
