# Hchat 脚本插件接口

本文档给插件作者使用。Hchat 脚本插件采用 WA 风格，脚本文件是 BeanShell 写法，文件名固定为 `main.java`。少量接口会比 WA 多返回 `boolean`；脚本不接收返回值时仍可直接调用。

插件 Agent 只能把本文档、内置开发指南及当前运行时/工具结果明确确认的内容当作事实。能力、可用性或限制没有明确依据时，必须说明未知或需要运行时验证，不得猜测。

设置页中的 `插件 Agent` 会把这份文档中的公开接口摘要作为内置开发指南。Agent 根据对话和本地插件清单自动判断新建、修改或删除插件，需要修改时会读取对应插件的 `main.java` / `info.prop`，不要求用户预选任务类型。每次进入 Agent 都从临时新对话开始，发送首条用户消息后才把会话写入脚本根目录同级的 `Agent/sessions`；空白会话退出后不进入历史，旧会话可从历史列表主动继续。当前代码状态会随对话继续传给模型，并显示实际代码 diff。Agent 还可使用联网搜索、当前微信 APK 的资源表/XML 检索与类/方法 Smali 导出，以及用户配置的多个远程 Streamable HTTP MCP 服务器补充资料；每个 MCP 服务器可单独启用或关闭。聊天可通过系统文件管理器添加文件或图片，也可在消息中直接提供 Android 绝对路径，Agent 会读取用户明确给出的路径或其子项；文本、代码和日志作为上下文，图片使用兼容接口的图像内容。配置页支持多份模型配置档案、测试连接以及模型列表拉取，新建配置从空白开始：API 地址、密钥、模型 ID 和 MCP 列表均为空，接口类型及推理强度等选项恢复初始默认值，不复制当前配置；原配置保留，切换回去可继续使用。OpenAI 兼容、OpenAI、DeepSeek、OpenRouter、硅基流动、Gemini 和 Anthropic 模式会按各自协议自动补全请求地址，其中 Anthropic 使用原生 `/v1/messages`，Gemini 使用原生 `generateContent` / `streamGenerateContent`，只有 `自定义请求链接` 完全按用户填写的 URL 请求。聊天支持手动压缩或按阈值自动压缩上下文；压缩会生成 Codex 式结构化交接摘要并开启新的模型缓存周期，同时保留本地完整聊天、Diff 与工具结果文件。普通对话请求使用持久化的只追加协议转录，用户消息、模型回复、函数调用和工具结果不会在后续请求中被重排或改写；退出页面、切换会话和进程恢复继续使用原转录。生成请求支持 SSE 流式输出，正文到达前独立显示 `Working (耗时)`，同一轮首次出现正文后状态不会闪回，正文片段只追加且不会被后续累计响应覆盖；如果接口返回 `reasoning_content`，聊天会展示其实际内容而不使用客户端伪造的摘要。内置逆向工具允许模型以 `local_tool` 或兼容的工具状态调用完整的 `hchat.reverse.*` 工具名。输入框右侧的发送图标在运行时切换为停止图标，停止会取消当前请求并保存已收到内容，重进会话仍能看到“已中断”记录。长按聊天记录可复制、朗读、回滚、删除、创建分支和查看信息；用户消息可编辑并重发，直接覆盖该消息后的当前会话记录并建立新的缓存周期。快捷选项可把插件文件修改确认设为“每次询问”或“始终允许”：前者会在每次写入工具调用和最终提交时显示实际 Diff，关闭弹窗后可从待确认入口重新打开；工具级和最终确认弹窗也可勾选“始终允许”，确认后立即保存设置并放行本轮剩余写入与最终提交；后者在静态检查通过后跳过全部确认并自动创建、修改或删除插件。写入后的插件默认禁用，不会自动执行。聊天页不再提供代码预览和底部返回按钮，通过左上角箭头返回。需要完整接口语义时，以本文档为准，不要依赖模型猜测微信混淆实现。

模型选择页可直接手动填写模型 ID 并应用，无需先获取列表；列表获取失败或缺少目标模型时仍可手动使用。聊天页快捷选项、模型选择页与 Agent 配置页均提供“推理强度”，聊天页选择后立即保存并用于下一次请求，可选择默认或当前接口与模型对应的档位，设置按配置档案保存。默认不额外指定推理参数；非默认档位需模型和服务端支持，不支持时会显示接口错误。运行中切换只影响后续请求。

Agent 的联网函数工具分为 `hchat_web_search` 和 `hchat_web_fetch`：前者搜索关键词或 GitHub `owner/repo`，后者读取已经给出的完整 HTTP(S) 网页或 GitHub 文件地址。已知网址时应直接读取正文，不再把网址当作关键词搜索。

Agent 内置逆向工具还支持资源 ID 使用方法定位，以及目标类/方法的 Java 语义导出和 Smali 原始导出；大文本通过 `offset` / `nextOffset` 分页。Java 导出只加载目标类所在的单个 Dex，完成后立即释放。

Agent 的回合、原生工具调用、工具结果、思考、Working、中断和上下文压缩规则见本文档后面的“Agent 交互架构”章节。支持原生工具的接口使用标准 `assistant.tool_calls` / `tool` 消息；旧接口仍兼容 JSON 控制协议，控制字段不会显示为聊天正文。

## 快速开始

先记住这些最常用的入口：

- 插件主文件固定为 `main.java`；
- 常用生命周期和回调是 `onLoad()`、`onHandleMsg(Object msgInfoBean)`、`onClickSendBtn(String text)`、`onLongClickSendBtn(String text)`；
- 给当前聊天发消息通常使用 `getTargetTalker()`；
- 临时文件、下载文件和生成图片通常放在 `cacheDir`，插件自带文件通常从 `pluginDir` 读取；
- `onClickSendBtn` 和 `onLongClickSendBtn` 在微信主线程执行，插件解释器忙碌时本次回调会跳过并放行原始事件，网络、文件和长循环操作必须异步执行。

最小自动回复示例：

```beanshell
void onHandleMsg(Object msgInfoBean) {
    if (msgInfoBean.isSend()) return;
    if (!msgInfoBean.isText()) return;

    String talker = msgInfoBean.getTalker();
    String content = msgInfoBean.getContent();
    if ("在吗".equals(content)) {
        sendText(talker, "在");
    }
}
```

最小发送拦截示例：

```beanshell
boolean onClickSendBtn(String text) {
    if ("/ping".equals(text)) {
        sendText(getTargetTalker(), "pong");
        return true;
    }
    return false;
}
```

## 插件目录

每个插件一个文件夹：

```text
Hchat/脚本插件/
└─ 插件名称/
   ├─ main.java
   ├─ info.prop
   ├─ README.md
   └─ config.prop
```

必需文件：

- `main.java`: 插件代码。

可选文件：

- `info.prop`: 插件信息。
- `README.md`: 插件说明，点击插件名称右侧带 `单击` 标记的标题区域时弹出显示。
- `config.prop`: 插件配置文件，由配置接口自动生成。

`info.prop` 示例：

```properties
name=测试插件
author=作者
version=1.0.0
updateTime=2026-06-18
process=main
```

`process` 控制插件在哪类微信进程执行：

- `main`: 只在微信主进程执行，未填写时默认使用该值，现有插件行为不变。
- `appbrand`: 只在 `:appbrand*` 小程序进程执行。
- `all`: 同时在主进程和小程序进程分别执行一份独立实例。

也可以用英文逗号组合 `main,appbrand`。显式填写其它值会拒绝加载并写入模块错误日志，不会静默回退主进程。修改小程序进程插件的代码、启用状态或 `process` 后，需要重新打开对应小程序进程才会应用。不要把普通消息监听、定时任务或自动回复插件无条件设成 `all`，否则它会在多个进程重复执行。

## 内置导入

下面这些类已经由 Hchat 自动导入，插件里可以直接使用，不需要再手动写 `import`：

| 脚本里使用 | 完整类名 | 用途 |
| --- | --- | --- |
| `File` | `java.io.File` | 文件和目录操作。 |
| `Map` | `java.util.Map` | 键值表。 |
| `Set` | `java.util.Set` | 字符串集合配置等场景。 |
| `View` | `android.view.View` | 原生界面控件。 |
| `ContentValues` | `android.content.ContentValues` | 数据库插入和更新参数。 |
| `Cursor` | `android.database.Cursor` | 数据库原始查询结果。 |
| `Consumer` | `java.util.function.Consumer` | 异步回调和 Hook 回调。 |
| `Function` | `java.util.function.Function` | 替换 Hook 回调。 |
| `Field` | `java.lang.reflect.Field` | Java 反射字段。 |
| `Method` | `java.lang.reflect.Method` | Java 反射方法。 |
| `Constructor` | `java.lang.reflect.Constructor` | Java 反射构造函数。 |
| `Member` | `java.lang.reflect.Member` | 反射成员基类。 |
| `XposedBridge` | `de.robv.android.xposed.XposedBridge` | Xposed 原生接口。 |
| `XposedHelpers` | `de.robv.android.xposed.XposedHelpers` | Xposed 辅助接口。 |
| `XC_MethodHook` | `de.robv.android.xposed.XC_MethodHook` | Xposed Hook 回调类。 |
| `WeChatApis` | `h.Hchat.hooks.api.core.WeChatApis` | Hchat 内部微信 API 入口，进阶用法。普通插件优先使用本文档里的 WA 风格函数。 |
| `KavaReflector` | `h.Hchat.utils.KavaReflector` | Hchat 反射封装，进阶用法。 |
| `DexKitBridge` | `org.luckypray.dexkit.DexKitBridge` | DexKit 原始桥接类，进阶用法。 |
| `DexFinder` | `h.Hchat.dexkit.DexFinder` | Hchat DexKit 定位器，进阶用法。 |
| `DexBridgeHolder` | `h.Hchat.dexkit.DexBridgeHolder` | Hchat DexKit 持有器，进阶用法。 |
| `PluginCallBack` | `me.hd.wauxv.plugin.api.callback.PluginCallBack` | WA 同款 HTTP/下载回调。 |

同时还注入了这些变量，可以直接使用：

| 变量 | 说明 |
| --- | --- |
| `context` / `hostContext` | 微信 `Context`。 |
| `classLoader` | 微信进程的 `ClassLoader`。 |
| `bridge` | Hchat 脚本桥，普通插件一般不用直接调用。 |
| `wa` / `waBridge` | WA 风格接口桥，普通插件一般直接调用全局函数，不需要写 `wa.xxx()`。 |
| `http` / `httpClient` | HTTP 接口桥，普通插件一般直接调用 `get/post/download`。 |
| `audio` / `audioBridge` | 音频转换接口桥，普通插件一般直接调用本文档的音频全局函数。 |
| `apis` | Hchat 内部 `WeChatApis` 实例。 |
| `dexKit` | Hchat 封装后的 DexKit 脚本接口。 |
| `dexKitBridge` | DexKit 原始桥接对象。 |
| `dexFinder` | Hchat DexFinder 实例。 |
| `dexBridgeHolder` | DexKit 持有器。 |
| `XposedBridgeClass` / `XposedHelpersClass` / `XC_MethodHookClass` | 对应 Xposed 类对象。 |
| `WeChatApisClass` / `KavaReflectorClass` | 对应 Hchat 内部类对象。 |
| `DexKitBridgeClass` / `DexFinderClass` / `DexBridgeHolderClass` | 对应 DexKit 相关类对象。 |
| `FieldClass` / `MethodClass` / `ConstructorClass` | 对应 Java 反射类对象。 |
| `ScriptAudioBridgeClass` / `SilkCodecClass` / `AacCodecClass` / `ConversionClass` | 对应音频桥和编解码类对象。 |
| `startedAt` | 当前插件解释器创建时间。 |

注意事项：

- 只要上表已经列出，就不要再手动导入，避免 WA 旧插件迁移时因为类名或包名不同导致加载失败。
- Hchat 已兼容 `me.hd.wauxv.data.bean.info.FriendInfo` 和 `me.hd.wauxv.data.bean.info.GroupInfo`。旧 WA 插件如果强转这两个类型，`getFriendList()` 和 `getGroupList()` 可以直接使用。
- Hchat 已内置 `com.alibaba.fastjson2:fastjson2`，旧插件可以继续使用 `JSON`、`JSONObject`、`JSONArray`、`JSONPath` 等 fastjson2 写法。
- 脚本里自定义 `class Foo { ... }` 且没有显式构造函数时，Hchat 会自动补一个空构造，兼容 `new Foo()` 这类旧 WA 插件写法。
- `java.lang` 里的常用类可以直接使用，例如 `String`、`Long`、`Runnable`。
- 表里没有列出的 Java 类，如果脚本确实要用，可以继续自己导入，例如 `java.util.HashMap`、`java.util.ArrayList`、`org.json.JSONObject`。

## 生命周期

接口：

| 代码 | 说明 |
| --- | --- |
| `onLoad()` | 插件开启或微信启动加载插件时调用。 |
| `onUnload()` | 插件关闭或重载前调用。 |
| `openSettings()` | 插件设置入口。定义后，插件列表右侧会出现设置图标，点击后调用。 |

示例：

```java
void onLoad() {
    log("插件已加载");
}

void onUnload() {
    log("插件已卸载");
}

void openSettings() {
    toast("打开插件设置");
}
```

### 回调别名

如果 `main.java` 只是入口包装，真实方法定义在 `loadJava()`、`eval()` 或 `evalSnapshot()` 加载的脚本里，且方法名不是 Hchat 标准回调名，可以在顶层绑定回调别名，不需要再手写 `onLoad()` 包装。

接口：

| 代码 | 说明 |
| --- | --- |
| `useCallback(String callbackName, String methodName)` | 把标准回调绑定到指定方法名。 |
| `useOnLoad(String methodName)` | 把插件加载回调绑定到指定方法名。 |
| `useOnUnload(String methodName)` | 把插件卸载回调绑定到指定方法名。 |
| `useOpenSettings(String methodName)` | 把插件设置入口绑定到指定方法名。 |
| `useOnClickSendBtn(String methodName)` | 把发送按钮回调绑定到指定方法名。 |
| `useOnLongClickSendBtn(String methodName)` | 把长按发送按钮回调绑定到指定方法名。 |
| `useOnHandleMsg(String methodName)` | 把消息监听回调绑定到指定方法名。 |
| `useOnImageDownload(String methodName)` | 把图片自动下载回调绑定到指定方法名。 |
| `useOnVideoDownload(String methodName)` | 把普通视频自动下载回调绑定到指定方法名。 |
| `useOnFinderMediaDownload(String methodName)` | 把视频号媒体自动下载回调绑定到指定方法名。 |
| `useOnProtobufPacket(String methodName)` | 把 Protobuf 数据包监听回调绑定到指定方法名。 |
| `useOnMemberChange(String methodName)` | 把群成员变动回调绑定到指定方法名。 |
| `useOnNewFriend(String methodName)` | 把好友申请回调绑定到指定方法名。 |

`callbackName` 可用值：

```text
onLoad
onUnload
openSettings
onClickSendBtn
onLongClickSendBtn
onHandleMsg
onImageDownload
onVideoDownload
onFinderMediaDownload
onProtobufPacket
onMemberChange
onNewFriend
```

示例：

```java
loadJava("iqfk_support.java");

useOnLoad("iqfkSupportOnLoad");
useOnUnload("iqfkSupportOnUnload");
useOnHandleMsg("iqfkOnHandleMsg");
```

## 常用变量

可用变量：

| 代码 | 说明 |
| --- | --- |
| `pluginDir` | 当前插件目录路径，字符串类型，例如 `/storage/.../Hchat/脚本插件/测试插件`。 |
| `pluginDirFile` | 当前插件目录 `File` 对象，适合创建插件自己的文件。 |
| `cacheDir` | 全局缓存目录路径，字符串类型，例如 `/storage/.../Hchat/Cache`。 |
| `cacheDirFile` | 全局缓存目录 `File` 对象，适合保存临时文件。 |
| `scriptDir` | 脚本插件根目录路径，也就是所有插件文件夹所在目录。 |
| `scriptDirFile` | 脚本插件根目录 `File` 对象。 |
| `pluginId` | 插件文件夹名。 |
| `pluginName` | 插件显示名，优先读取 `info.prop` 里的 `name`。 |
| `pluginAuthor` | 插件作者，来自 `info.prop` 的 `author`。 |
| `pluginVersion` | 插件版本，来自 `info.prop` 的 `version`。 |
| `pluginUpdateTime` | 插件更新时间，来自 `info.prop` 的 `updateTime`。 |
| `processName` | 当前 Android 进程的完整名称，例如 `com.tencent.mm:appbrand0`。 |
| `pluginProcess` | 当前插件实例所在的进程类型，固定为 `main` 或 `appbrand`。 |
| `isMainProcess` | 当前是否为微信主进程，布尔类型。 |
| `isAppBrandProcess` | 当前是否为小程序进程，布尔类型。 |
| `hostContext` | 微信的 `Context` 对象。 |
| `hostVerName` | 微信版本名，例如 `8.0.49`。 |
| `hostVerCode` | 微信版本号，数字类型。 |
| `hostVerClient` | 微信客户端/热更新小版本字符串。 |
| `moduleVer` | Hchat 模块版本。 |

示例：

```java
log(pluginName);
log(pluginDir);
log(cacheDir);
log(hostVerName);
```

## 日志和提示

接口：

| 代码 | 说明 |
| --- | --- |
| `log(Object msg)` | 写入当前插件目录的 `log.txt`，同时输出到 LSPosed 日志。 |
| `toast(Object msg)` | 显示 Toast，会自动带插件名前缀。 |

补充说明：

- 已启用插件修改 `main.java` 后会自动重载最新代码，不需要手动关闭再打开插件。
- 如果新代码加载失败，Hchat 会自动关闭该插件，并提示 `加载[插件名]失败，已自动关闭`。

示例：

```java
log("写入插件日志");
toast("显示提示");
```

## 模块弹窗

插件需要普通消息、确认、输入、单选或多选弹窗时，默认使用下面的模块接口。它们显示与 Hchat 一致的 Miuix 弹窗；除非确实需要复杂自定义布局，不要直接创建 Android `Dialog` 或 `AlertDialog`。

| 代码 | 说明 |
| --- | --- |
| `showModuleDialog(String title, String message)` | 显示消息弹窗。 |
| `showModuleDialog(String title, String message, String position)` | 在指定位置显示消息弹窗。 |
| `showModuleConfirmDialog(String title, String message, Consumer callback)` | 显示确认弹窗；确认回调值为 `true`，取消、返回或点击空白处为 `false`。 |
| `showModuleConfirmDialog(String title, String message, String position, Consumer callback)` | 在指定位置显示确认弹窗。 |
| `showModuleInputDialog(String title, String summary, String initialValue, String placeholder, Consumer callback)` | 显示单行输入弹窗；确认后回调输入文本，允许提交空文本。 |
| `showModuleInputDialog(String title, String summary, String initialValue, String placeholder, String position, Consumer callback)` | 在指定位置显示单行输入弹窗。 |
| `showModuleChoiceDialog(String title, String summary, List choices, Consumer callback)` | 显示单选弹窗；回调从 `0` 开始的选项索引。 |
| `showModuleChoiceDialog(String title, String summary, List choices, String position, Consumer callback)` | 在指定位置显示单选弹窗。 |
| `showModuleMultiChoiceDialog(String title, String summary, List choices, Set initialSelected, Consumer callback)` | 显示多选弹窗；初始值和回调值都是从 `0` 开始的索引集合，允许清空选择。 |
| `showModuleMultiChoiceDialog(String title, String summary, List choices, Set initialSelected, String position, Consumer callback)` | 在指定位置显示多选弹窗。 |

以上接口都返回 `boolean`。带 `position` 的重载支持 `top`、`center`、`bottom`，同时识别“顶部”“居中”“底部”；省略参数或传入未知值时保持默认底部。微信不在前台、当前没有可用 Activity，或单选/多选列表为空时返回 `false`。输入、单选和多选在用户取消时不调用回调；所有回调都在主线程执行，回调内不要直接运行网络、数据库遍历或其它耗时操作。

示例：

```java
void openSettings() {
    showModuleConfirmDialog("清空配置", "确定清空当前插件配置？", new Consumer() {
        public void accept(Object confirmed) {
            if (Boolean.TRUE.equals(confirmed)) {
                putString("content", "");
                toast("已清空");
            }
        }
    });
}
```

## 原生悬浮玻璃底栏

插件已经取得某个页面真实的原生底栏 `View` 后，可以交给模块转换成悬浮液态玻璃样式。模块保留目标 View 现有的子项、点击事件和选中状态，只负责重新挂载、背景效果与恢复原布局；微信不同版本的底栏定位仍由插件自行完成。

| 代码 | 说明 |
| --- | --- |
| `Object applyModuleFloatingGlassBar(View bottomBar)` | 使用默认参数转换目标底栏，成功返回恢复句柄，失败返回 `null`。 |
| `Object applyModuleFloatingGlassBar(View bottomBar, Map options)` | 使用指定参数转换目标底栏。 |
| `handle.restore()` | 主动撤销转换并把目标底栏放回原父容器、索引和布局参数。 |
| `handle.isApplied()` | 返回当前转换是否仍然生效。 |

`options` 支持：

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `glass` | `true` | Android 13 及以上启用模块液态玻璃；低版本自动回退普通悬浮底栏。 |
| `clearBackground` | `true` | 暂时清除目标底栏自身背景，让玻璃背景可见；恢复时还原。 |
| `horizontalMarginDp` | `12` | 左右悬浮边距，范围 `0-48` dp。 |
| `bottomMarginDp` | `12` | 底部悬浮边距，范围 `0-48` dp。 |

目标 View 必须已经挂到当前 Activity 的 `android.R.id.content` 页面树，且必须已有父容器。同一个 Activity 内容根同一时刻只允许托管一个底栏；无法安全重挂、没有有效 Activity 或已有底栏正在托管时返回 `null`。模块会在插件关闭、重载、原父容器离开窗口或 Activity 销毁时自动恢复，插件无需依赖 `onUnload()` 才能避免残留。不要传 `SurfaceView`、`TextureView` 或弹出窗口中的临时 View。

```java
Object floatingBarHandle;

void applyFloatingBar(View wechatBottomBar) {
    Map options = new java.util.HashMap();
    options.put("glass", true);
    options.put("horizontalMarginDp", 12);
    options.put("bottomMarginDp", 10);
    floatingBarHandle = applyModuleFloatingGlassBar(wechatBottomBar, options);
    if (floatingBarHandle == null) {
        log("底栏转换失败");
    }
}

void restoreFloatingBar() {
    if (floatingBarHandle != null && floatingBarHandle.isApplied()) {
        floatingBarHandle.restore();
    }
    floatingBarHandle = null;
}
```

## 配置读写

配置会保存在当前插件目录的 `config.prop`。

读取：

| 代码 | 说明 |
| --- | --- |
| `getString(String key, String defValue)` | 读取字符串配置；没有配置时返回 `defValue`。 |
| `getStringSet(String key, Set defValue)` | 读取字符串集合；没有配置时返回 `defValue`。 |
| `getBoolean(String key, boolean defValue)` | 读取开关配置；没有配置时返回 `defValue`。 |
| `getInt(String key, int defValue)` | 读取整数配置；没有配置时返回 `defValue`。 |
| `getFloat(String key, float defValue)` | 读取小数配置；没有配置时返回 `defValue`。 |
| `getLong(String key, long defValue)` | 读取长整数配置；没有配置时返回 `defValue`。 |

写入：

| 代码 | 说明 |
| --- | --- |
| `putString(String key, String value)` | 写入字符串配置。 |
| `putStringSet(String key, Set value)` | 写入字符串集合配置。 |
| `putBoolean(String key, boolean value)` | 写入开关配置。 |
| `putInt(String key, int value)` | 写入整数配置。 |
| `putFloat(String key, float value)` | 写入小数配置。 |
| `putLong(String key, long value)` | 写入长整数配置。 |

参数说明：

| 参数 | 说明 |
| --- | --- |
| `key` | 配置名，例如 `enabled`、`keyword`。 |
| `defValue` | 默认值。配置不存在或读取失败时返回这个值。 |
| `value` | 要写入的新值。 |

示例：

```java
String text = getString("text", "默认值");
boolean enabled = getBoolean("enabled", false);
int count = getInt("count", 1);

putString("text", "你好");
putBoolean("enabled", true);
putInt("count", 10);
```

## 发送按钮

接口：

| 代码 | 说明 |
| --- | --- |
| `onClickSendBtn(String text)` | 点击微信发送按钮时调用。`text` 是输入框当前文字。 |
| `onLongClickSendBtn(String text)` | 长按微信发送按钮时调用。`text` 是输入框当前文字；模块确认长按后会按系统触觉设置提供一次长按震动反馈。 |

返回值：

| 返回值 | 说明 |
| --- | --- |
| `true` | 单击回调拦截本次发送；长按回调消费本次长按并阻止随后单击发送。两者都会清空输入框。 |
| `false` | 单击回调继续微信正常发送；长按回调继续微信原长按流程，原流程未消费时松手可能继续触发普通单击。 |

两个发送按钮回调都运行在微信主线程。若该插件解释器正被 `onHandleMsg`、`onMemberChange`、`onNewFriend` 或其它脚本调用占用，本次事件会跳过该插件回调并直接放行，避免等待解释器导致聊天界面卡顿。插件需要稳定拦截命令时，所有其它回调都应把网络、文件和长循环任务放到异步线程。

长按检测直接跟踪真实发送按钮的 `DOWN/MOVE/UP/CANCEL` 触摸序列，按系统 `ViewConfiguration` 的长按时间与移动阈值判定，不依赖发送按钮是否启用 Android 原生 `longClickable`。移动超出阈值、多指或取消手势不会触发回调；回调返回 `true` 时模块会取消微信原触摸状态并消费本次手势剩余事件，避免松手后再次普通发送。

示例：

```java
boolean onClickSendBtn(String text) {
    if ("/id".equals(text)) {
        sendText(getTargetTalker(), "当前账号: " + getLoginWxid());
        return true;
    }
    return false;
}

boolean onLongClickSendBtn(String text) {
    if ("/settings".equals(text)) {
        openSettings();
        return true;
    }
    return false;
}
```

## 消息监听

接口：

| 代码 | 说明 |
| --- | --- |
| `onHandleMsg(Object msg)` | 收到或发送消息时调用。`msg` 是消息对象。 |

补充说明：

- Hchat 的脚本普通消息监听只订阅微信 `message` 数据库变化，不再使用 PB/AddMsg 实时层作为 `onHandleMsg` 来源。
- 数据库层回调可能比 PB 实时层略晚，但字段更稳定，`getMsgId()` 通常可直接拿到本地消息 ID，适合引用回复、去重和记录消息。
- Hchat 会在调用插件前做短窗口去重；`getMsgId()` 命中时会使用较长窗口，防止同一条 DB 消息 insert/update 分两次触发。
- `getMsgSvrId()` 和内容去重仍保留 1 秒窗口；内容窗口会使用归一化后的会话、发送者、类型、内容等信息，群聊里 `wxid:\n内容` 这种前缀差异也会归一化处理。
- `onMemberChange(...)`、`onNewFriend(...)` 等其它脚本回调来源不随普通消息监听改变。
- 如果插件会发积分、红包、抽奖、签到奖励等强副作用，仍建议按自己的业务 ID 再做一次幂等保护。

常用方法：

| 代码 | 说明 |
| --- | --- |
| `getMsgId()` | 本地消息 ID，通常用于本地查询或撤回相关判断；脚本普通消息监听来自数据库层时一般会稳定返回。 |
| `getType()` | 消息类型，字符串形式，和 `getMsgType()` 一样。 |
| `getMsgType()` | 消息类型，字符串形式；常见值：`1` 文本、`3` 图片、`34` 语音、`43/62` 视频、`47` 表情、`48` 位置、`49` 应用消息、`10000/10002` 系统消息。 |
| `getCreateTime()` | 消息创建时间，可能是毫秒或秒，取决于微信内部数据。 |
| `getCreateTimeSeconds()` | 消息创建时间，固定按秒返回。 |
| `getMsgSvrId()` | 服务端消息 ID；没有服务端 ID 时可能返回 `0`。 |
| `getTalker()` | 会话 ID。私聊时是好友 wxid；群聊时是 `xxx@chatroom`。回复消息一般用这个作为 `sendText` 的第一个参数。 |
| `getTalkerId()` | WA 旧别名，等同于 `getTalker()`。 |
| `getSender()` | 发送者 wxid。私聊通常等于对方 wxid；群聊时是群成员 wxid；自己发送时通常是自己的 wxid。 |
| `getSendTalker()` | 发送者 wxid，WA 风格命名。通常和 `getSender()` 一样。 |
| `getSenderId()` | WA 旧别名，等同于 `getSendTalker()`。 |
| `getContent()` | 消息正文。文本消息是文字内容；部分语音/图片/应用消息可能是 XML 或内部格式。 |
| `getText()` | 文本内容，当前等同于 `getContent()`。 |
| `getXml()` | XML 内容。应用消息、红包、转账、引用等消息常用。 |
| `getMsgSource()` | 微信消息来源字段，常用于解析 @ 列表、群消息来源等。 |
| `getAtUserList()` | 被 @ 的 wxid 列表。 |
| `getSelfWxId()` | 当前登录账号 wxid。 |
| `getNativeUrl()` | 红包等消息里的 `nativeUrl`，没有时为空字符串。 |
| `getSource()` | 消息来源标识。 |
| `getKind()` | 消息分类。 |
| `getMessage()` | 原始消息对象。 |
| `getStoredMessage()` | 数据库消息对象。 |
| `getImageMsg()` | 图片消息结构。不是图片消息时可能为 `null`。 |
| `getVideoMsg()` | 视频消息结构。支持消息类型 `43/62`，不是视频消息时可能为 `null`。 |
| `getQuoteMsg()` | 引用消息结构。不是引用消息时可能为 `null`。 |
| `getPatMsg()` | 拍一拍消息结构。不是拍一拍时可能为 `null`。 |
| `getFileMsg()` | 文件消息结构。不是文件消息时可能为 `null`。 |
| `getTransferMsg()` | 转账消息结构。不是转账消息时可能为 `null`。 |

WA 兼容字段：

Hchat 传给 `onHandleMsg(...)` 的消息对象兼容 `me.hd.wauxv.data.bean.MsgInfoBean` 参数类型；旧插件如果直接读取字段，也可以使用 `msg.content`、`msg.text`、`msg.talker`、`msg.talkerId`、`msg.sender`、`msg.senderId`、`msg.sendTalker`、`msg.xml`、`msg.msgId`、`msg.msgType`、`msg.type`、`msg.createTime`、`msg.msgSvrId`、`msg.msgSource`、`msg.selfWxId`、`msg.source`、`msg.kind`、`msg.nativeUrl`。新插件仍推荐优先使用 `getContent()`、`getTalker()`、`getSendTalker()` 等 getter。

常用判断：

| 代码 | 说明 |
| --- | --- |
| `isSend()` | 是否自己发送的消息。 |
| `isSelf()` | 是否自己发送的消息，等同于 `isSend()`。 |
| `isPrivateChat()` | 是否私聊消息。 |
| `isGroupChat()` | 是否群聊消息。 |
| `isChatroom()` | 是否普通微信群聊。 |
| `isImChatroom()` | 是否企业微信群聊。 |
| `isOfficialAccount()` | 是否公众号消息。 |
| `isOpenIM()` | 是否企业微信私聊。 |
| `isText()` | 是否文本消息。 |
| `isImage()` | 是否图片消息。 |
| `isVoice()` | 是否语音消息。 |
| `isVideo()` | 是否视频消息。 |
| `isEmoji()` | 是否表情消息。 |
| `isLocation()` | 是否位置消息。 |
| `isApp()` | 是否应用消息。 |
| `isAppMsg()` | 是否应用消息，等同于 `isApp()`。 |
| `isSystem()` | 是否系统消息。 |
| `isRedPacket()` | 是否红包消息。 |
| `isRedBag()` | 是否红包消息，等同于 `isRedPacket()`。 |
| `isTransfer()` | 是否转账消息。 |
| `isQuote()` | 是否引用消息。 |
| `isFile()` | 是否文件消息。 |
| `isLink()` | 是否链接消息。 |
| `isMusic()` | 是否音乐消息。 |
| `isNote()` | 是否接龙/笔记类消息。 |
| `isShareCard()` | 是否名片消息。 |
| `isPat()` | 是否拍一拍消息。 |
| `isRecalled()` | 是否撤回消息。 |
| `isVoip()` | 是否通话消息。 |
| `isVoipVoice()` | 是否语音通话。 |
| `isVoipVideo()` | 是否视频通话。 |
| `isAtMe()` | 是否单独 @ 我；@ 全体和群公告全体不会同时返回 true。 |
| `isNotifyAll()` | 是否 @ 全体，兼容服务端把全体名单展开成当前账号 wxid 的消息。 |
| `isAnnounceAll()` | 是否群公告类全体提醒。 |
| `isVideoNumberVideo()` | 是否视频号视频。 |

常见子结构：

| 代码 | 常用字段 |
| --- | --- |
| `getImageMsg()` | `getMd5()` 图片 MD5；`getBigImgUrl()` 高清图链接；`getMidImgUrl()` 普通图链接；`getThumbUrl()` 缩略图链接；`getKey()` / `getAesKey()` 图片密钥；`getCdnUrl()` 缩略图 CDN；`getBigLength()` / `getMidLength()` / `getThumbLength()` 图片长度。 |
| `getVideoMsg()` | `getMd5()` / `getNewMd5()` 视频 MD5；`getCdnVideoUrl()` CDN 地址；`getAesKey()` 解密密钥；`getLength()` 文件长度；`getPlayLength()` 播放时长。部分聊天视频正文没有 CDN XML，下载时优先把整条消息对象传给 `downloadVideo(...)`。 |
| `getQuoteMsg()` | `getTitle()` 引用标题；`getMsgSource()` 消息来源；`getSendTalker()`/`getSenderId()` 原消息发送者 wxid。模块优先按引用 `svrid` 回查原消息，并按原消息的发送方向返回真实 wxid（私聊引用自己时返回自己的 wxid）；表情等引用 `svrid` 不完整时，再按引用会话、消息类型、时间和内容回查本地原消息。查询不到时只接受引用 XML 中的真实非群聊 ID，不会把群 ID 当作发送者返回；`getDisplayName()` 显示名；`getTalker()`/`getTalkerId()` 原消息所在会话 ID；`getType()` 消息类型；`getContent()` 原消息内容；`getSvrId()` 服务端消息ID；`getStrId()` 字符串ID；`getCreateTime()` 创建时间。 |
| `getPatMsg()` | `getTalker()` 聊天Id；`getFromUser()` 发起者；`getPattedUser()` 被拍者；`getTemplate()` 展示模板；`getCreateTime()` 创建时间。 |
| `getFileMsg()` | `getTitle()` 文件标题；`getSize()` 文件字节；`getExt()` 文件后缀；`getMd5()` 文件MD5；`getUrl()` 文件链接；`getKey()` 文件密钥；`getAttachId()` 附件ID；`getFileName()` 文件名。 |
| `getTransferMsg()` | `getTransactionId()` / `getTransId()` 转账交易 ID；`getTransferId()` 转账 ID；`getPayerUsername()` / `getPayer()` 付款方账号；`getReceiver()` 收款方账号；`getInvalidTime()` 失效时间；`getFee()` 金额；`getDescription()` 描述；`getRawXml()` 原始 XML。对象也提供 `transactionId`、`transferId`、`payerUsername` 字段。 |

联系人对象兼容方法：

```beanshell
FriendInfo {
    String getWxid();// wxid
    String getName();// 显示名
    String getNickname();// 昵称
    String getRemark();// 备注
    String getRemarkName();// 备注
}

GroupInfo {
    String getRoomId();// 群 ID
    String getName();// 群名称
    String getNickname();// 群聊昵称
    String getRemark();// 群备注
    String getRemarkName();// 群备注
    String getDisplayName();// 显示名
    List<String> getMemberList();// 成员列表
    int getMemberCount();// 成员数量
}
```
示例：

```java
void onHandleMsg(Object msg) {
    if (msg.isText()) {
        log(msg.getTalker() + " -> " + msg.getContent());
    }

    if (msg.isGroupChat() && msg.isAtMe()) {
        sendText(msg.getTalker(), "收到");
    }
}
```

`void` 回调需要提前结束时应写 `return;`，不要写 `return true;`、`return false;` 或其它带值返回。为兼容已有 WA 脚本，Hchat 会忽略 `void` 方法误带的返回值，但该返回值不会产生布尔拦截语义；只有声明为 `boolean` 的 `onClickSendBtn(...)` 和 `onLongClickSendBtn(...)` 会使用返回值。

## 图片、视频和视频号媒体自动下载回调

接口：

```java
void onImageDownload(Object msg, String imagePath, String talker, String senderWxid);
void onVideoDownload(Object msg, String videoPath, String talker, String senderWxid);
void onFinderMediaDownload(Object msg, String mediaPath, String talker, String senderWxid);
```

| 参数 | 说明 |
| --- | --- |
| `msg` | 与 `onHandleMsg(...)` 相同的 `MsgInfoBean` 兼容消息对象。 |
| `imagePath` | 已完整下载的本地图片绝对路径，位于 `Hchat/Cache`。 |
| `videoPath` | 已完整下载的普通聊天视频绝对路径，位于 `Hchat/Cache`。 |
| `mediaPath` | 已完整下载的视频号图片或视频绝对路径，位于 `Hchat/Cache`。 |
| `talker` | 当前会话 ID。 |
| `senderWxid` | 媒体发送者 wxid。 |

只有至少一个已启用插件实际声明对应回调时，Hchat 才会下载该类媒体。媒体消息与 `onHandleMsg(...)` 共用消息数据库入口，但使用独立队列和完成态判断：同一消息先以 `msgSvrId=0` 插入、随后补齐时，媒体队列会重新读取最新记录，不会被普通消息去重提前拦截。图片、普通视频与视频号分享另有任务级去重；视频号任务执行期间永不过期，完成后保留 10 分钟，避免慢速多媒体下载期间因数据库再次更新而重复回调。三类媒体各使用 2 个工作线程和最多 32 个等待任务，队列满时会丢弃新事件并写入限频错误日志。普通视频优先复制微信已经完整落地的 MP4；接收视频只有取得长度元数据后才物化本地 VFS 文件，否则等待 `VideoInfo` 并走与 `downloadVideo(...)` 相同的 CDN 链路。

这些回调表示“发现新消息后由 Hchat 自动下载完成”，不是监听用户在微信界面手动发起的任意下载任务。它们只来自微信主进程的消息数据库监听，小程序进程插件不会收到。`onFinderMediaDownload(...)` 是 `alt-entry` 专属回调，结构化解析聊天分享 XML 的 `objectId`、`objectNonceId` 与 `finderFeed/mediaList/media`；XML 未携带解密信息时会调用微信原生视频号详情请求补齐 `decodeKey`、规格和 PCDN 地址。视频只有完成解密并通过 MP4 文件头校验后才会回调。多媒体动态每下载完成一个文件调用一次，`msg`、`talker` 和 `senderWxid` 保持原消息信息。视频号临时文件会在所有订阅插件的本次回调结束后自动删除，需要保留时必须在回调内复制。

多个插件收到的是同一个缓存文件路径。回调中不要删除或修改共享文件；普通视频会在所有订阅插件的本次回调结束后自动删除，需要长期保存或异步处理时必须在回调内复制到当前插件目录。回调应尽快返回，避免长期占用下载工作线程。

入口脚本加载外部方法时，也可以使用：

```java
useOnImageDownload("myImageHandler");
useOnVideoDownload("myVideoHandler");
useOnFinderMediaDownload("myFinderHandler");
```

## Protobuf 数据包监听

接口：

| 代码 | 说明 |
| --- | --- |
| `onProtobufPacket(Object packet)` | 捕获微信 Protobuf 请求或响应时调用。 |

数据包方法：

| 代码 | 说明 |
| --- | --- |
| `getDirection()` | 方向，固定为 `request` 或 `response`。 |
| `getUri()` | 请求 URI；微信未提供时为空字符串。 |
| `getCgiId()` | CGI 类型 ID；无法读取时为 `-1`。 |
| `getLength()` | 原始数据字节数。 |
| `getData()` | 原始 Protobuf `byte[]`；每次读取都返回副本。 |
| `getJson()` | 模块解码得到的 JSON；解码失败时为 `{}`。 |
| `getJsonObject()` | 模块解码得到的新 `JSONObject`；可直接用 `optString()`、`optInt()` 等方法读取，解码失败时为空对象。 |
| `getTimestamp()` | 捕获时间，Unix 毫秒时间戳。 |
| `isRequest()` | 是否请求包。 |
| `isResponse()` | 是否响应包。 |

抓包回调不受模块“抓包”页的总开关、请求/响应开关和 CGI 屏蔽列表影响。Hchat 只在微信网络 Hook 中复制稳定数据，随后通过有界单线程队列异步执行插件回调，不阻塞微信网络线程；插件处理速度持续落后时会丢弃新的回调并写入限频日志。插件应尽早按方向和 CGI ID 过滤，耗时解析、网络和文件操作仍应放到自己的后台线程。

```java
void onProtobufPacket(Object packet) {
    if (packet.isRequest() && packet.getCgiId() == 123) {
        JSONObject json = packet.getJsonObject();
        log(packet.getUri() + "\n" + json.toString());
    }
}
```

### 主动发送 Protobuf 数据包

接口同时接受 JSON 文本和 `JSONObject`：

```java
boolean sendProtobufPacket(String uri, int cgiId, String json);
boolean sendProtobufPacket(String uri, int cgiId, String json, Consumer callback);
boolean sendProtobufPacket(String uri, int cgiId, JSONObject json);
boolean sendProtobufPacket(String uri, int cgiId, JSONObject json, Consumer callback);
boolean sendProtobufPacket(String uri, int cgiId, int funcId, int routeId, String json);
boolean sendProtobufPacket(String uri, int cgiId, int funcId, int routeId, String json, Consumer callback);
boolean sendProtobufPacket(String uri, int cgiId, int funcId, int routeId, JSONObject json);
boolean sendProtobufPacket(String uri, int cgiId, int funcId, int routeId, JSONObject json, Consumer callback);
```

简单重载会把 `funcId` 和 `routeId` 设为 `0`。模块会先签名并编码 JSON，再依次尝试微信原生网络场景、通用发包和已抓取的同类请求重放；当前版本没有可用发送路径时返回 `false`。带回调重载会向 `Consumer` 传入结果对象：`isSuccess()` 返回是否成功，`getMessage()` 返回使用的发送路径或失败原因。回调可能同步或异步触发，插件不要依赖固定线程。

```java
JSONObject body = new JSONObject();
body.put("field", "value");

sendProtobufPacket("/cgi-bin/micromsg-bin/example", 123, body,
    new java.util.function.Consumer() {
        public void accept(Object result) {
            log(result.isSuccess() + ": " + result.getMessage());
        }
    });
```

主动发送的数据包也可能被 `onProtobufPacket(...)` 捕获。不要在抓包回调里无条件原样发回同一个 URI/CGI，否则会形成循环。

## 成员变动监听

接口：

| 代码 | 说明 |
| --- | --- |
| `onMemberChange(String type, String groupWxid, String userWxid, String userName)` | 群成员加入或退出时调用，WA 同款签名。 |

参数：

| 参数 | 说明 |
| --- | --- |
| `type` | 事件类型。`join` 表示加入，`left` 表示退出。 |
| `groupWxid` | 群聊 ID，一般是 `xxx@chatroom`。 |
| `userWxid` | 变动成员的 wxid。 |
| `userName` | 变动成员显示名。优先系统消息里的昵称、群昵称、备注、昵称，取不到时返回 wxid。 |

说明：

- `join` 会优先使用群系统消息/XML 里的被邀请人信息补昵称，并保留 `chatroom.memberlist` 差集兜底。
- `left` 仍然只依赖微信 `chatroom` 表成员列表差集；普通成员退群时，微信通常没有稳定系统提示。
- 插件开启后第一次看到某个群会建立成员快照，不会把已有成员全部当成 `join`。
- 内部会按 `type + groupWxid + userWxid` 做短时间去重，避免系统消息和差集同时命中时重复触发。

示例：

```java
void onMemberChange(String type, String groupWxid, String userWxid, String userName) {
    if ("join".equals(type)) {
        sendText(groupWxid, "[AtWx=" + userWxid + "] 欢迎加入");
    } else if ("left".equals(type)) {
        sendText(groupWxid, userName + " 退出了群聊");
    }
}
```

## 好友申请监听

接口：

| 代码 | 说明 |
| --- | --- |
| `onNewFriend(String wxid, String ticket, int scene)` | 收到新的好友申请时调用，WA 同款签名。 |

参数：

| 参数 | 说明 |
| --- | --- |
| `wxid` | 申请人的账号 ID。优先返回同意后可直接聊天的真实 `wxid_xxx` / 自定义微信号；如果微信申请记录只提供临时验证用户名，则可能是 `v3_...@stranger`。 |
| `ticket` | 好友申请携带的验证票据，可直接传给 `verifyUser(...)`。 |
| `scene` | 申请来源场景值。 |

说明：

- 该回调优先监听微信 `fmessage_msginfo` 好友申请表插入，处理 `isSend=0` 的新申请，并从 `msgContent` 等字段解析 `encryptusername/fromusername`、`ticket/antispamticket` 和 `scene`。`fromusername/username` 会优先作为回调 `wxid`，`encryptusername` 会保留给 `verifyUser(...)` 内部发起同意请求。
- 消息观察入口仍作为补充来源，但只处理归一化后的微信好友申请消息类型 `37`；联系人名片类型 `42/66` 即使 XML 含有 `antispamticket` 等相似字段也不会触发好友申请回调。
- 内部按 `wxid + ticket + scene` 做短时间去重，避免同一条申请重复触发。
- 自动通过好友申请时，直接调用 `verifyUser(wxid, ticket, scene)` 即可；如果回调 `wxid` 是真实联系人 ID，模块会自动映射回微信同意请求需要的临时验证用户名。

示例：

```java
void onNewFriend(String wxid, String ticket, int scene) {
    log("收到好友申请: " + wxid + " scene=" + scene);
    verifyUser(wxid, ticket, scene);
}
```

## 联系人和会话

接口：

| 代码 | 说明 |
| --- | --- |
| `getLoginWxid()` | 返回当前登录账号 wxid。内部会依次读 `userinfo id=2`、微信原生 `notify_key_pref_no_account/login_weixin_username`、旧登录偏好，并在必要时用 `getLoginAlias()` 到 `rcontact.alias` 反查真实 `username`；微信资料库刚初始化时会短暂重试，减少插件加载阶段瞬时返回空值。 |
| `getLoginAlias()` | 返回当前登录账号微信号；没有微信号时返回 wxid。 |
| `getTargetTalker()` | 返回当前聊天窗口会话 ID。私聊是好友 wxid，群聊是 `xxx@chatroom`。 |
| `boolean deleteConversation(String talker)` | 调用微信原生会话存储删除指定会话的本地首页会话项并触发会话列表刷新。会话项已不存在时也返回 `true`；该接口不删除消息历史，不删除联系人或群资料，也不会退出群聊。 |
| `getTopActivity()` | 返回当前微信顶部 `Activity`，没有时返回 `null`。 |
| `getOfficialList()` | 返回公众号列表。列表元素支持 `getWxid()`、`getName()`、`getNickname()` 等方法。 |
| `getFriendList()` | 返回好友列表。列表元素支持 `getWxid()`、`getName()`、`getNickname()`、`getRemarkName()`。 |
| `getFriendListInfo()` | 返回好友 `Map` 列表，适合批量读取和搜索。 |
| `getGroupList()` | 返回群聊列表。列表元素支持 `getRoomId()`、`getName()`、`getRemarkName()`、`getDisplayName()`、`getMemberList()`、`getMemberCount()`。 |
| `getGroupListInfo()` | 返回群聊 `Map` 列表，适合批量读取和搜索。 |
| `getContactLabelList()` | 返回联系人标签列表。列表元素支持 `getLabelId()`、`getLabelName()`、`getName()`、`getUserNameList()`。 |
| `getContactLabelListInfo()` | 返回联系人标签 `Map` 列表，适合批量读取和搜索。 |
| `getContactByLabelId(String labelId)` | 通过标签 ID 返回联系人 wxid 列表。 |
| `getContactByLabelName(String labelName)` | 通过标签名称返回联系人 wxid 列表。 |
| `addContactLabel(String labelName)` | Hchat 便利接口：增加联系人标签，返回标签 ID；同名标签已存在时返回已有 ID。 |
| `modifyContactLabelList(String username, String labelName)` | 修改好友标签为单个标签。 |
| `modifyContactLabelList(String username, List<String> labelNames)` | 修改好友标签为多个标签；传空列表会清空标签。 |
| `verifyUser(String wxid, String ticket, int scene)` | 通过好友申请。来自 `onNewFriend(...)` 的真实联系人 ID 会自动映射回微信验证用户名。 |
| `verifyUser(String wxid, String ticket, int scene, int privacy)` | 通过好友申请，并传入隐私参数。来自 `onNewFriend(...)` 的真实联系人 ID 会自动映射回微信验证用户名。 |
| `addChatroomMember(String chatroomId, String addMember)` | 添加群成员。 |
| `addChatroomMember(String chatroomId, List<String> addMemberList)` | 批量添加群成员。 |
| `inviteChatroomMember(String chatroomId, String inviteMember)` | 邀请群成员。 |
| `inviteChatroomMember(String chatroomId, List<String> inviteMemberList)` | 批量邀请群成员。 |
| `delChatroomMember(String chatroomId, String delMember)` | 移除群成员。 |
| `delChatroomMember(String chatroomId, List<String> delMemberList)` | 批量移除群成员。 |
| `getGroupMemberList(String groupWxid)` | 返回群成员 wxid 列表。会合并群聊 API、联系人表和 `roomdata` 已解析成员，并在微信资料库刚初始化时短暂重试。 |
| `getGroupMemberListInfo(String groupWxid)` | 返回群成员 `Map` 列表，包含 wxid、昵称、群昵称、备注等。 |
| `getGroupMemberCount(String groupWxid)` | 返回群成员数量。 |
| `getGroupName(String groupWxid)` | 返回群聊名称。 |
| `getChatroomName(String chatroomId)` | 返回群聊名称，和 `getGroupName` 作用相同。 |
| `getGroupRemarkName(String groupWxid)` | 返回群聊备注名。 |
| `getFriendNickName(String friendWxid)` | WA 同款：返回好友昵称。 |
| `getFriendRemarkName(String friendWxid)` | WA 同款：返回好友备注名。 |
| `getFriendGender(String friendWxid)` | 返回好友性别。好友资料优先按微信联系人 `lvbuff` 解析；自己账号走 `userinfo`。 |
| `getFriendProvince(String friendWxid)` | 返回好友省份。好友资料优先按微信联系人 `lvbuff` 解析；自己账号走 `userinfo`。 |
| `getFriendCity(String friendWxid)` | 返回好友城市。好友资料优先按微信联系人 `lvbuff` 解析；自己账号走 `userinfo`。 |
| `getFriendRegion(String friendWxid)` | 返回好友地区。优先拼接成“省 市”；好友资料优先按微信联系人 `lvbuff` 解析，自己账号走 `userinfo`。 |
| `getFriendDisplayName(String friendWxid, String roomId)` | WA 同款：好友/成员 wxid 在前，群 ID 在后；优先返回群内显示名，取不到时只回退好友昵称或微信号，不混入备注。 |
| `getFriendName(String friendWxid)` | WA 同款：返回综合名称，优先备注，其次昵称。 |
| `getFriendName(String friendWxid, String roomId)` | WA 同款：好友/成员 wxid 在前，群 ID 在后；返回综合名称。 |
| `getGroupMemberName(String groupWxid, String memberWxid)` | Hchat 便利方法：群 ID 在前，成员 wxid 在后；返回群内显示名（可能带备注）。新脚本优先用 WA 同款 `getFriendDisplayName(memberWxid, groupWxid)`。 |
| `getGroupNickName(String groupWxid, String memberWxid)` | Hchat 便利方法：群 ID 在前，成员 wxid 在后；返回原始群昵称（不带备注），允许返回空字符串表示成员没有群昵称/已取消群昵称；只有当前 `chatroom.roomdata` 没有该成员记录时才回退缓存。 |
| `getGroupMemberGender(String groupWxid, String memberWxid)` | 返回群成员性别。按成员联系人资料返回。 |
| `getGroupMemberProvince(String groupWxid, String memberWxid)` | 返回群成员省份。按成员联系人资料返回。 |
| `getGroupMemberCity(String groupWxid, String memberWxid)` | 返回群成员城市。按成员联系人资料返回。 |
| `getGroupMemberRegion(String groupWxid, String memberWxid)` | 返回群成员地区。按成员联系人资料返回。 |
| `getAvatarUrl(String username)` | 返回头像链接或路径。 |
| `getAvatarUrl(String username, boolean isBigHeadImg)` | 返回头像链接或路径；`isBigHeadImg` 表示是否优先高清头像。 |

微信主进程冷启动自动加载已启用插件时，模块会先初始化脚本 Bridge、目录和文件监听，等公共 Dex 定位完成并确认联系人数据库包含 `rcontact`、`chatroom` 表后，再在独立线程执行插件源码与 `onLoad()`。该等待不阻塞微信主线程或 DexKit 调度队列，某个主进程插件加载失败也不会阻断后续插件；手动启用或重载仍同步返回真实结果，数据库尚未就绪时会直接返回可重试错误。

`process=appbrand` / `all` 的插件会在每个小程序进程 `Application` 就绪后，由独立轻量运行时加载。小程序进程不初始化完整 `FeatureManager`、联系人/消息数据库、主进程消息回调、插件文件监听或 DexKit；`dexKit`、`dexKitBridge`、`dexFinder`、`dexBridgeHolder` 均为 `null`，联系人、数据库、发送消息等依赖主进程公共 API 的函数也不保证可用。小程序插件应通过稳定完整类名、反射及 `hookBefore` / `hookAfter` / `hookReplace` 操作当前 `classLoader`；不要在子进程创建新的 DexKit。各小程序进程使用隔离的 Dex/SO 代码缓存，`config.prop` 写入使用跨进程文件锁；配置值仍由同一插件的各进程共享。小程序进程加载失败只写错误日志，不会自动关闭该插件的共享开关，以免一个小程序进程影响其它进程。

最小小程序进程 Hook 结构：

```java
void onLoad() {
    if (!isAppBrandProcess) return;
    Class target = findClass("已确认的稳定完整类名");
    if (target == null) {
        log("当前微信版本未找到小程序目标类");
        return;
    }
    Method method = firstMethod(target, "已确认的方法名", 1);
    if (method == null) {
        log("当前微信版本未找到小程序目标方法");
        return;
    }
    hookBefore(method, new Consumer() {
        public void accept(Object value) {
            XC_MethodHook.MethodHookParam param = (XC_MethodHook.MethodHookParam) value;
            // 只处理已逆向确认的参数和返回值。
        }
    });
}
```

示例里的类名、方法名和参数数量必须由 Agent 的 APK 逆向工具或已有多版本证据确认，不能直接照抄占位文字。目标是混淆类且必须运行时定位时，可使用 `process=all`：主进程实例用 DexKit 定位后把 descriptor 写入插件配置，小程序实例只读取已缓存 descriptor；缓存尚未生成时本次应跳过并提示重新打开小程序，不能在小程序进程创建 DexKit。

参数说明：

| 参数 | 说明 |
| --- | --- |
| `groupWxid` / `chatroomId` / `roomId` | 群聊 ID，一般是 `xxx@chatroom`。 |
| `memberWxid` | 群成员 wxid。 |
| `friendWxid` | 好友 wxid。 |
| `talker` | 会话 ID。私聊传好友 wxid，群聊传 `xxx@chatroom`，公众号传其账号 ID。 |
| `username` | wxid、群 ID 或公众号 ID。 |
| `labelId` | 联系人标签 ID。 |
| `labelName` | 联系人标签名称。 |
| `labelNames` | 联系人标签名称列表。修改标签时会替换该好友的整组标签。 |
| `ticket` | 好友申请携带的验证票据，一般从好友申请消息或相关插件逻辑里取得。 |
| `scene` | 好友申请场景值。 |
| `privacy` | 隐私参数，旧插件不需要时可不传。 |
| `inviteMember` / `inviteMemberList` | 要邀请进群的成员 wxid 或 wxid 列表。 |
| `delMember` / `delMemberList` | 要移出群聊的成员 wxid 或 wxid 列表。 |

`getFriendListInfo()` 返回的每个 `Map` 常见字段：

| 字段 | 说明 |
| --- | --- |
| `wxid` | 好友 wxid。 |
| `nickname` | 好友昵称。 |
| `remarkName` | 好友备注。 |
| `displayName` | 最终显示名。 |
| `customWxId` | 微信号。 |
| `gender` | 性别。`0` 未知，`1` 男，`2` 女。 |
| `province` | 省份。 |
| `city` | 城市。 |
| `region` | 地区。优先拼接成“省 市”。 |
| `avatarUrl` | 头像链接或路径。 |

`getGroupListInfo()` 返回的每个 `Map` 常见字段：

| 字段 | 说明 |
| --- | --- |
| `roomId` | 群聊 ID。 |
| `name` | 群名。 |
| `nickname` | 群聊昵称，通常等同当前群名。 |
| `remarkName` | 群聊备注。 |
| `displayName` | 最终显示名；有备注时为 `备注 (群名)`。 |
| `owner` | 群主 wxid。 |
| `memberCount` | 群成员数量。 |
| `memberList` | 群成员 wxid 列表。 |
| `rawDisplayNames` | 群成员原始昵称数据。 |

`getContactLabelList()` 返回的每个标签对象常用方法：

| 代码 | 说明 |
| --- | --- |
| `getLabelId()` | 标签 ID。 |
| `getId()` | 标签 ID，兼容简写。 |
| `getLabelName()` | 标签名称。 |
| `getName()` | 标签名称，兼容简写。 |
| `getUserNameList()` | 该标签下的联系人 wxid 列表。 |
| `getUsernameList()` | 该标签下的联系人 wxid 列表，兼容大小写写法。 |
| `getContactList()` | 该标签下的联系人 wxid 列表。 |

`getContactLabelListInfo()` 返回的每个 `Map` 常见字段：

| 字段 | 说明 |
| --- | --- |
| `labelId` / `id` | 标签 ID。 |
| `labelName` / `name` | 标签名称。 |
| `userNameList` / `usernameList` / `contactList` | 该标签下的联系人 wxid 列表。 |

`getGroupMemberListInfo(groupWxid)` 返回的每个 `Map` 常见字段：

| 字段 | 说明 |
| --- | --- |
| `wxid` | 群成员 wxid。 |
| `displayName` | 最终显示名，会按群昵称、好友昵称、wxid 等兜底。 |
| `groupNick` | 群昵称，兼容旧字段名。 |
| `groupNickName` | 群昵称。 |
| `rawGroupNickName` | 原始群昵称，不带备注，允许为空字符串；判断成员是否取消群昵称应以这个字段是否为空为准。 |
| `nickname` | 好友昵称。 |
| `remarkName` | 好友备注。 |
| `customWxId` | 微信号。 |
| `gender` | 性别。`0` 未知，`1` 男，`2` 女。 |
| `province` | 省份。 |
| `city` | 城市。 |
| `region` | 地区。优先拼接成“省 市”。 |
| `avatarUrl` | 头像链接或路径。 |

列表较大时，推荐使用：

- `getFriendListInfo()`
- `getGroupListInfo()`
- `getGroupMemberListInfo(String groupWxid)`
- `getContactLabelListInfo()`

这些接口返回整理好的 `Map` 列表，读取速度更快。

性别返回值说明：

- `0`: 未知
- `1`: 男
- `2`: 女

示例：

```java
String self = getLoginWxid();
String talker = getTargetTalker();
Object friends = getFriendList();
Object groups = getGroupList();
Object labels = getContactLabelList();
```

标签示例：

```java
List<ContactLabelBean> labels = getContactLabelList();
for (ContactLabelBean label : labels) {
    log(label.getLabelId() + " " + label.getLabelName());
}

List<String> users = getContactByLabelName("重要好友");
log("重要好友数量: " + users.size());
```

通过好友申请示例：

```java
verifyUser("wxid_xxx", "ticket_xxx", 17);
verifyUser("wxid_xxx", "ticket_xxx", 17, 1);
```

邀请群成员示例：

```java
addChatroomMember("123456@chatroom", "wxid_xxx");
inviteChatroomMember("123456@chatroom", "wxid_xxx");
delChatroomMember("123456@chatroom", "wxid_xxx");
```

### WA 插件列表接口迁移

WA 插件里常见写法：

```java
import me.hd.wauxv.data.bean.info.FriendInfo;
import me.hd.wauxv.data.bean.info.GroupInfo;
import me.hd.wauxv.data.bean.info.ContactLabelBean;

List<FriendInfo> friends = getFriendList();
List<GroupInfo> groups = getGroupList();
List<ContactLabelBean> labels = getContactLabelList();
```

迁移到 Hchat 时，建议改成下面两种方式之一。

方式一：使用高性能 `Map` 列表，适合搜索、遍历、批量读取：

```java
Object friends = getFriendListInfo();
for (Object item : friends) {
    Map friend = (Map) item;
    String wxid = String.valueOf(friend.get("wxid"));
    String name = String.valueOf(friend.get("displayName"));
    log(wxid + " " + name);
}

Object groups = getGroupListInfo();
for (Object item : groups) {
    Map group = (Map) item;
    String roomId = String.valueOf(group.get("roomId"));
    String name = String.valueOf(group.get("name"));
    log(roomId + " " + name);
}

Object labels = getContactLabelListInfo();
for (Object item : labels) {
    Map label = (Map) item;
    String id = String.valueOf(label.get("labelId"));
    String name = String.valueOf(label.get("labelName"));
    Object users = label.get("userNameList");
    log(id + " " + name + " " + users);
}
```

方式二：保留 WA 风格对象列表，适合少量读取：

```java
Object friends = getFriendList();
for (Object friend : friends) {
    String wxid = friend.getWxid();
    String name = friend.getName();
    log(wxid + " " + name);
}

Object groups = getGroupList();
for (Object group : groups) {
    String roomId = group.getRoomId();
    String name = group.getName();
    log(roomId + " " + name);
}

Object labels = getContactLabelList();
for (Object label : labels) {
    String id = label.getLabelId();
    String name = label.getLabelName();
    Object users = label.getUserNameList();
    log(id + " " + name + " " + users);
}

modifyContactLabelList("wxid_xxx", "重要好友");
String labelId = addContactLabel("新标签");
java.util.ArrayList names = new java.util.ArrayList();
names.add("重要好友");
names.add("家人");
modifyContactLabelList("wxid_xxx", names);
```

注意事项：

- Hchat 已兼容 `me.hd.wauxv.data.bean.info.FriendInfo` 和 `me.hd.wauxv.data.bean.info.GroupInfo`，旧 WA 插件保留这两个 `import` 也可以加载。`ContactLabelBean` 仍使用 Hchat 内置对象，不需要额外导入 WA 包名。
- Hchat 已默认导入自己的 `ContactLabelBean`，标签接口可以直接写 `List<ContactLabelBean> labels = getContactLabelList();`。
- 旧代码写了 `List<FriendInfo>` 或 `List<GroupInfo>` 时可以继续使用；如果只想减少强类型依赖，也可以写成 `Object` 或不写类型。
- 批量读取好友、群聊、群成员时，优先使用 `getFriendListInfo()`、`getGroupListInfo()`、`getGroupMemberListInfo(String groupWxid)`，速度比逐个对象反射字段更快。
- 批量读取标签时，优先使用 `getContactLabelListInfo()`；需要 WA 同款对象写法时使用 `getContactLabelList()`。
- `addContactLabel(String labelName)` 是 Hchat 额外便利接口，不是 WA 公开文档里的同款接口；返回空字符串表示创建失败。底层优先走微信原生 `addcontactlabel` 网络场景，不再只靠本地数据库插入。
- `modifyContactLabelList(...)` 按 WA 同款签名提供；只匹配已经存在的标签名，不存在的标签会被跳过。传入的标签会替换该好友原来的整组标签。底层会先调用微信原生标签存储把标签名解析成微信内部同款的 `contactLabelIds` 字符串，再走 `modifycontactlabellist` 网络场景并同步本地联系人表，避免自己手拼标签串导致微信侧不生效。
- `getFriendListInfo()` 读取字段用 `friend.get("wxid")`、`friend.get("displayName")`、`friend.get("remarkName")`、`friend.get("nickname")`。
- `getGroupListInfo()` 读取字段用 `group.get("roomId")`、`group.get("name")`、`group.get("remarkName")`、`group.get("displayName")`、`group.get("memberCount")`、`group.get("memberList")`。
- `getGroupMemberListInfo(groupWxid)` 读取字段用 `member.get("wxid")`、`member.get("displayName")`、`member.get("groupNick")`、`member.get("remarkName")`、`member.get("nickname")`。
- `getContactLabelListInfo()` 读取字段用 `label.get("labelId")`、`label.get("labelName")`、`label.get("userNameList")`。
- `verifyUser(String wxid, String ticket, int scene)` 和 `verifyUser(String wxid, String ticket, int scene, int privacy)` 按 WA 同款参数顺序提供；能拿到 `privacy` 时传四参，拿不到时用三参。
- 如果只需要当前聊天对象，直接用 `getTargetTalker()`，不需要遍历列表。
- 旧 WA 插件如果依赖 `FriendInfo` 或 `GroupInfo` 的特殊字段，先用 `log(item)` 或改用 `Map` 方式确认 Hchat 当前返回字段，再替换字段读取代码。

## 发送消息

接口：

| 代码 | 说明 |
| --- | --- |
| `sendText(String talker, String content)` | 发送文本；启用模块的 `发送文本格式` 后，会按当前模板处理文本。 |
| `sendText(String talker, String content, Consumer callback)` | 发送文本，并在回调里返回发送结果；当前回调值可能是 `Long` 或 `null`，同样会应用启用的 `发送文本格式`。 |
| `sendQuoteMsg(String talker, long msgId, String content)` | 发送引用消息，WA 当前文档顺序。`msgId` 可传本地 `msgId`，也兼容传 `msgSvrId`。 |
| `sendQuoteMsg(String talker, String content, long msgId)` | 兼容部分旧 WA 插件常见写法，效果同上。 |
| `revokeMsg(long msgId)` | WA 同款：撤回指定消息。优先按本地 `msgId` 撤回，也兼容直接传 `msgSvrId` 反查；只能撤回自己发送、微信仍允许撤回的消息。 |
| `uploadDeviceStep(long step)` | WA 同款：上传微信运动设备步数。 |
| `sendPat(String talker, String pattedUser)` | 发送拍一拍，会按微信原生规则校验当前会话和被拍用户，并走原生点击同款 `scene=0`。 |
| `sendShareCard(String talker, String wxid)` | 发送联系人名片。 |
| `boolean sendImage(String talker, String sendPath)` | 发送图片，返回是否成功提交。 |
| `boolean sendImage(String talker, String sendPath, String appId)` | 发送图片，并按 WA 同款把 appId 写入图片消息 `appinfo.appid`，返回是否成功提交。 |
| `boolean sendOriginalImage(String talker, String sendPath)` | 静默发送图片并传原图/不压缩标志，返回是否成功提交。 |
| `boolean sendVoice(String talker, String sendPath)` | 发送语音，自动读取语音真实时长，返回是否成功提交。 |
| `boolean sendVoice(String talker, String sendPath, int duration)` | 发送语音并指定语音时长，单位秒，返回是否成功提交。 |
| `boolean sendVideo(String talker, String sendPath)` | 发送视频，返回是否成功提交。 |
| `boolean sendEmoji(String talker, String sendPath)` | 发送表情，`sendPath` 可为本地路径或表情 MD5，返回是否成功提交。 |
| `boolean sendFile(String talker, String sendPath)` | 发送文件，返回是否成功提交。 |
| `boolean sendFile(String talker, String sendPath, String title)` | 发送文件并指定显示文件名，返回是否成功提交。 |
| `getFavoriteList(int limit)` | 获取最近收藏列表，返回 `Map` 列表，`limit` 范围自动限制在 `1..200`。 |
| `getFavorite(long localId)` | 按收藏 `localId` 获取单条收藏信息，未找到时返回 `null`。 |
| `boolean sendFavorite(String talker, long localId)` | 按收藏 `localId` 发送收藏消息，返回是否成功提交。 |
| `boolean sendFavorite(String talker, String localId)` | 字符串形式的收藏 `localId` 发送收藏消息，返回是否成功提交。 |
| `sendMediaMsg(String talker, Object mediaMessage, String appId)` | 发送 WA 同款 `WXMediaMessage` 媒体消息/卡片。 |
| `shareFile(String talker, String title, String filePath, String appId)` | 分享文件卡片。 |
| `shareMiniProgram(String talker, String title, String description, String userName, String path, byte[] thumbData, String appId)` | 分享小程序卡片。 |
| `sendAppBrandMsg(String talker, String title, String pagePath, String ghName)` | WA 同款四参：发送小程序消息。 |
| `shareMusic(String talker, String title, String description, String musicUrl, String musicDataUrl, byte[] thumbData, String appId)` | 分享音乐卡片。 |
| `shareMusicVideo(String talker, String title, String description, String musicUrl, String musicDataUrl, String singerName, int duration, String songLyric, byte[] thumbData, String appId)` | 分享音乐视频卡片。 |
| `shareText(String talker, String text, String appId)` | 分享文本卡片。 |
| `shareVideo(String talker, String title, String description, String videoUrl, byte[] thumbData, String appId)` | 分享视频卡片。 |
| `shareWebpage(String talker, String title, String description, String webpageUrl, byte[] thumbData, String appId)` | 分享网页卡片。 |
| `sendXmlMsg(String talker, String content)` | 发送 XML/AppMsg 消息。AppMsg XML 如果自带 `<msgsource>...</msgsource>`，模块会按微信原生 AppMsgLogic 参数透传到发送链路；卡片缩略图采用缓存/后台预取，不会阻塞调用线程等待网络。 |
| `sendLocation(String talker, String poiName, String label, String x, String y, String scale)` | 发送位置消息。 |
| `sendLocation(String talker, JSONObject jsonObj)` | 发送位置消息，`jsonObj` 可包含 `poiName`、`label`、`x`、`y`、`scale`。 |

参数说明：

| 参数 | 说明 |
| --- | --- |
| `talker` | 目标会话 ID。私聊传好友 wxid，群聊传 `xxx@chatroom`。 |
| `content` | 文本内容或 XML 内容。 |
| `msgId` | 被引用消息的本地 `msgId`；如果只能拿到 `msgSvrId`，也可以直接传 `msgSvrId`。 |
| `msgId` 用于 `revokeMsg` | 优先传要撤回消息的本地 `msgId`，通常从 `msgInfoBean.getMsgId()` 或历史消息对象读取；如果手里只有 `msgSvrId`，现在也会尝试反查后撤回。 |
| `step` | 要上传的步数，必须大于 `0`。 |
| `pattedUser` | 被拍一拍的用户 wxid。私聊通常和 `talker` 相同；群聊传群成员 wxid。 |
| `wxid` | 要分享的联系人 wxid 或 OpenIM 用户 ID。 |
| `sendPath` | 本地文件路径，例如 `/sdcard/a.jpg`。 |
| `duration` | 语音时长，单位秒。 |
| `title` | 文件显示名。 |
| `localId` | 收藏表里的本地收藏 ID，可从 `getFavoriteList(...)` 返回项读取。 |
| `pagePath` | 小程序页面路径。 |
| `ghName` | 小程序账号/原始 ID。 |
| `appId` | AppMsg 应用标识，或图片消息 `appinfo.appid`，可传空字符串。 |
| `mediaMessage` | `com.tencent.mm.opensdk.modelmsg.WXMediaMessage` 对象。 |
| `filePath` | 要分享的本地文件路径。 |
| `userName` | 小程序原始 ID。 |
| `path` | 小程序页面路径。 |
| `thumbData` | 卡片缩略图字节数组，可传 `null`。 |
| `musicUrl` | 音乐页或落地页链接。 |
| `musicDataUrl` | 音频直链。 |
| `singerName` | 歌手名。 |
| `duration` | 音乐视频时长，单位秒。 |
| `songLyric` | 歌词文本。 |
| `videoUrl` | 视频链接。 |
| `webpageUrl` | 网页链接。 |
| `poiName` | 地点名称。 |
| `label` | 位置描述。 |
| `x` | 经度。 |
| `y` | 纬度。 |
| `scale` | 地图缩放级别，空值默认 `16`。 |
| `jsonObj` | 位置参数对象。 |

说明：

- `sendVoice(String talker, String sendPath)` 会按文件头识别真实音频类型，非 Silk 的常见音频会先转 Silk 再发送，并自动读取真实时长；`sendVoice(String talker, String sendPath, int duration)` 的 `duration` 单位是秒。超过 60 秒的语音按真实文件发送，微信界面显示时长仍最多显示 60 秒；模块的 `伪造语音时长` 开启时，全局设置优先决定微信界面显示时长，三参 `duration` 仍用于发送链路判断。
- `getFavoriteList(int limit)` 返回项常见字段包括 `localId`、`id`、`type`、`typeLabel`、`title`、`summary`、`totalSizeBytes`、`updateTimeMillis`、`tags`。收藏语音发送会优先转成语音消息发送，其它收藏走微信原生收藏转发链路。
- 群聊文本支持 `[AtWx=wxid]`。
- `[AtWx=notify@all]` 表示 @全体。
- `sendText` 及其回调重载会复用模块的统一发送文本格式；插件没有输入框交互时，模板中的 `发送耗时` 为 `0秒`。
- `[AtWx=wxid]` 的展示名优先取该成员当前群内昵称；取不到时回退好友微信昵称，再回退微信号，不使用你的联系人备注名。
- 兼容旧 WA 插件：如果插件通过数据库 Hook 在 `message.lvbuffer` 写入 `<msgsource>...</msgsource>`，且当前消息 `ContentValues` 原本包含 `msgSource` 字段，Hchat 会在消息入库前同步补写该字段；旧版消息表或插入参数没有该字段时不会主动查表或新增字段。

示例：

```java
String talker = getTargetTalker();

sendText(talker, "普通文本");
sendText(talker, "[AtWx=wxid_xxx] 你好");
sendText(talker, "[AtWx=notify@all] 大家好");
sendQuoteMsg(talker, 123456789L, "这是引用回复");
sendQuoteMsg(talker, "这是引用回复", 123456789L);
sendPat(talker, "wxid_xxx");
sendShareCard(talker, "wxid_xxx");
sendLocation(talker, "测试地点", "测试描述", "113.3245", "23.0999", "16");
sendImage(talker, "/sdcard/a.jpg");
sendVoice(talker, "/sdcard/a.amr", 3);
sendVideo(talker, "/sdcard/a.mp4");
sendEmoji(talker, "/sdcard/a.gif");
sendFile(talker, "/sdcard/a.zip", "压缩包.zip");
Object favorites = getFavoriteList(20);
sendFavorite(talker, 123456789L);
sendXmlMsg(talker, "<msg><appmsg><title>测试</title></appmsg></msg>");
shareWebpage(talker, "示例标题", "示例描述", "https://example.com", null, "wx123");
```

媒体消息示例：

```java
var music = new com.tencent.mm.opensdk.modelmsg.WXMusicObject();
music.musicUrl = "https://example.com/music.mp3";
music.musicDataUrl = "https://example.com/music.mp3";

var media = new com.tencent.mm.opensdk.modelmsg.WXMediaMessage();
media.mediaObject = music;
media.title = "测试音乐";
media.description = "这是媒体消息";

sendMediaMsg(talker, media, "wx1234567890");
```

WA 同款分享示例：

```java
byte[] thumbData = null;

shareMusic(
    talker,
    "测试音乐",
    "歌手",
    "https://example.com/music-page",
    "https://example.com/music.mp3",
    thumbData,
    "wx8dd6ecd81906fd84"
);

shareMiniProgram(
    talker,
    "测试小程序",
    "描述",
    "gh_xxxxxxx@app",
    "pages/index/index.html",
    thumbData,
    "wx123456"
);

sendAppBrandMsg(talker, "测试小程序", "pages/index/index.html", "gh_xxxxxxx@app");
```

## 朋友圈

接口：

| 代码 | 说明 |
| --- | --- |
| `getSnsPostList()` | 获取本机最近缓存的 50 条朋友圈，返回稳定 Bean 列表。 |
| `getSnsPostList(int limit)` | 获取本机最近缓存的朋友圈；`limit` 最大按 200 处理。 |
| `getSnsPostList(String userName, int limit)` | 按发布者 wxid 获取本机缓存的朋友圈。 |
| `getSnsPost(String snsId)` | 按朋友圈 ID 获取一条本机缓存记录，找不到返回 `null`。 |
| `prepareSnsPostMedia(String snsId, Consumer callback)` | 异步准备原图、视频或实况媒体；成功提交异步任务返回 `true`，结果传给回调。 |
| `publishSnsPost(Object prepared)` | 把成功的媒体准备结果原样写入现有朋友圈发布队列。 |
| `refreshSnsTimeline()` | 发起微信原生首页时间线刷新请求。 |
| `uploadText(String content)` | WA 同款：发布纯文字朋友圈。 |
| `uploadText(String content, String sdkId, String sdkAppName)` | WA 同款：发布纯文字朋友圈，并附带第三方 SDK 信息。 |
| `uploadText(JSONObject jsonObj)` | WA 同款：发布纯文字朋友圈，`jsonObj` 可包含 `content`、`sdkId`、`sdkAppName`。 |
| `uploadTextAndPicList(String content, String picPath)` | WA 同款：发布单图或图文朋友圈。 |
| `uploadTextAndPicList(String content, String picPath, String sdkId, String sdkAppName)` | WA 同款：发布单图或图文朋友圈，并附带第三方 SDK 信息。 |
| `uploadTextAndPicList(String content, List picPathList)` | WA 同款：发布多图或图文朋友圈。 |
| `uploadTextAndPicList(String content, List picPathList, String sdkId, String sdkAppName)` | WA 同款：发布多图或图文朋友圈，并附带第三方 SDK 信息。 |
| `uploadTextAndPicList(JSONObject jsonObj)` | WA 同款：发布图文朋友圈，`jsonObj` 可包含 `content`、`picPath`、`picPathList`、`sdkId`、`sdkAppName`。 |
| `uploadLivePhoto(String livePhotoPath)` | Hchat 扩展：发布一张单文件实况照片，文件内需包含静态图和内嵌视频。 |
| `uploadLivePhoto(String imagePath, String videoPath)` | Hchat 扩展兼容接口：分别传入静态封面和配套视频。 |
| `uploadLivePhoto(JSONObject jsonObj)` | Hchat 扩展：发布实况照片；单文件使用 `livePhotoPath` / `path`，也兼容图片与视频双路径。 |
| `uploadLivePhotoList(List livePhotoList)` | Hchat 扩展：发布最多 9 张实况照片；列表项可为单文件路径、实况 Bean 或媒体 `JSONObject`。 |
| `uploadLivePhotoList(JSONObject jsonObj)` | Hchat 扩展：从 `livePhotoList` / `livePhotoPathList` 发布多张实况照片。 |
| `uploadTextAndLivePhoto(String content, String livePhotoPath)` | Hchat 扩展：发布单文件实况照片加文字朋友圈。 |
| `uploadTextAndLivePhoto(String content, String livePhotoPath, String sdkId, String sdkAppName)` | Hchat 扩展：发布单文件实况照片加文字朋友圈，并附带第三方 SDK 信息。 |
| `uploadTextAndLivePhoto(String content, String imagePath, String videoPath)` | Hchat 扩展：发布实况照片加文字朋友圈。 |
| `uploadTextAndLivePhoto(String content, String imagePath, String videoPath, String sdkId, String sdkAppName)` | Hchat 扩展：发布实况照片加文字朋友圈，并附带第三方 SDK 信息。 |
| `uploadTextAndLivePhoto(JSONObject jsonObj)` | Hchat 扩展：发布实况照片加文字朋友圈，`jsonObj` 还可包含 `content`、`sdkId`、`sdkAppName`、`coverTimeMs`。 |
| `uploadTextAndLivePhotoList(String content, List livePhotoList)` | Hchat 扩展：发布多张实况照片加文字朋友圈。 |
| `uploadTextAndLivePhotoList(String content, List livePhotoList, String sdkId, String sdkAppName)` | Hchat 扩展：发布多张实况照片加文字朋友圈，并附带第三方 SDK 信息。 |
| `uploadTextAndLivePhotoList(JSONObject jsonObj)` | Hchat 扩展：通过 JSON 发布多张实况照片加文字朋友圈。 |
| `uploadVideo(String videoPath)` | Hchat 扩展：发布纯视频朋友圈。 |
| `uploadVideo(JSONObject jsonObj)` | Hchat 扩展：发布纯视频朋友圈，`jsonObj` 可包含 `videoPath` 或 `path`。 |
| `uploadTextAndVideo(String content, String videoPath)` | Hchat 扩展：发布视频加文字朋友圈。 |
| `uploadTextAndVideo(String content, String videoPath, String sdkId, String sdkAppName)` | Hchat 扩展：发布视频加文字朋友圈，并附带第三方 SDK 信息。 |
| `uploadTextAndVideo(JSONObject jsonObj)` | Hchat 扩展：发布视频加文字朋友圈，`jsonObj` 可包含 `content`、`videoPath`、`path`、`sdkId`、`sdkAppName`。 |

参数说明：

| 参数 | 说明 |
| --- | --- |
| `content` | 朋友圈文字内容，可以为空。 |
| `picPath` | 单张图片路径。 |
| `picPathList` | 多张图片路径列表。 |
| `livePhotoPath` | 包含静态图和内嵌视频的单文件实况照片路径；JSON 形式兼容字段名 `path`。 |
| `livePhotoList` | 最多 9 项；每项可为单文件路径、`WeChatSnsLivePhoto`，或包含 `livePhotoPath` / `imagePath` / `videoPath` / `coverTimeMs` 的 `JSONObject`。JSON 外层兼容字段名 `livePhotoPathList`。 |
| `imagePath` | 双路径调用中的静态封面路径，兼容字段名 `picPath`；未传视频路径时也可直接填写单文件实况照片。 |
| `liveVideoPath` | JSON 形式的实况配套视频兼容字段名。 |
| `coverTimeMs` | JSON 形式可选的实况封面时间戳，单位毫秒；默认 `0`，兼容字段名 `coverTime`。 |
| `videoPath` / `path` | 本地视频文件路径。 |
| `sdkId` | 第三方 SDK 标识，不需要时传空字符串。 |
| `sdkAppName` | 第三方应用名，不需要时传空字符串。 |

朋友圈记录 Bean：

| Bean | 可调用方法 |
| --- | --- |
| 朋友圈记录 | `getSnsId()`、`getLocalId()`、`getUserName()`、`getDisplayName()`、`getCreateTimeSeconds()`、`getCreateTimeMillis()`、`getStorageType()`、`getContentType()`、`getType()`、`getText()` / `getContent()`、`getMediaList()`、`isText()`、`isImage()`、`isVideo()`、`isLivePhoto()`、`isCard()`、`isSelf()`。 |
| 媒体记录 | `getId()`、`getType()`、`getUrl()`、`getThumbUrl()`、`isLivePhoto()`、`getLiveVideo()`。 |
| 准备结果 | `isSuccess()`、`getMessage()`、`getSnsId()`、`getType()`、`getText()` / `getContent()`、`getImagePathList()`、`getVideoPath()`、`getVideoThumbPath()`、`getLivePhotoList()`、四个类型判断方法。 |
| 实况媒体 | `getImagePath()`、`getVideoPath()`、`getVideoDurationMillis()`、`getCoverTimeMillis()`。 |

`getType()` 返回 `text`、`image`、`video`、`live_photo`、`card` 或 `unknown`。其中网页链接、音乐和当前已确认的扩展协议类型返回 `card`，未识别类型返回 `unknown`，`getContentType()` 始终保留微信原始协议值。Bean 不包含微信 `SnsInfo`、`TimeLineObject` 或混淆 Protobuf 对象。

示例：

```java
uploadText("今天状态不错");

java.util.ArrayList pics = new java.util.ArrayList();
pics.add(cacheDir + "/1.jpg");
pics.add(cacheDir + "/2.jpg");
uploadTextAndPicList("图文测试", pics);

uploadLivePhoto(cacheDir + "/motion_photo.jpg");
uploadTextAndLivePhoto("实况测试", cacheDir + "/motion_photo.jpg");

java.util.ArrayList livePhotos = new java.util.ArrayList();
livePhotos.add(cacheDir + "/motion_photo_1.jpg");
livePhotos.add(new org.json.JSONObject()
    .put("imagePath", cacheDir + "/cover_2.jpg")
    .put("videoPath", cacheDir + "/live_2.mp4")
    .put("coverTimeMs", 0));
uploadTextAndLivePhotoList("多张实况测试", livePhotos);

uploadVideo(cacheDir + "/test.mp4");
uploadTextAndVideo("视频测试", cacheDir + "/test.mp4");

// 原样转发本机缓存的一条朋友圈。
java.util.List posts = (java.util.List) getSnsPostList(20);
if (!posts.isEmpty()) {
    Object post = posts.get(0);
    prepareSnsPostMedia(post.getSnsId(), new java.util.function.Consumer() {
        public void accept(Object prepared) {
            if (!prepared.isSuccess()) {
                log(prepared.getMessage());
                return;
            }
            log("朋友圈已加入发布队列: " + publishSnsPost(prepared));
        }
    });
}
```

注意事项：

- 朋友圈接口是发朋友圈，不是发聊天消息。
- 读取接口只返回当前账号本机已经缓存的朋友圈，不代表服务端完整历史；需要先拉取最新内容时调用 `refreshSnsTimeline()`，刷新请求是异步网络操作，不能立即假定缓存已经更新。
- 查询结果按发布时间倒序返回，广告记录会被过滤；不指定发布者时读取时间线缓存，指定发布者时读取该发布者已缓存内容。
- `prepareSnsPostMedia(...)` 必须从后台异步执行，回调一定要先检查 `isSuccess()`；普通图片和实况照片最多准备 9 张。多张实况逐项准备，只有配套视频下载或校验失败的项按静态封面发布，其他项继续保留实况；卡片和未知类型不会降级成纯文字。
- 图片和视频文件必须是微信进程可读的本地路径。
- 图文和视频不能混在同一条朋友圈里发布。
- 实况照片和视频朋友圈是 Hchat 扩展接口，不是 WA 公开文档里的同款接口。
- 单文件实况照片会由模块自动拆出静态封面和内嵌视频；旧插件仍可分别提供两份文件。单张旧接口在当前微信不支持原生实况上传时仍返回失败；多项接口会保留可用实况，并只把无效项降级为静态封面，微信完全没有实况入口时全部按静态封面发布。
- 底层复用微信原生朋友圈发送队列，会自动生成视频缩略图并触发上传。
- `wa.uploadText(...)`、`wa.uploadTextAndPicList(...)`、`wa.uploadLivePhoto(...)`、`wa.uploadLivePhotoList(...)`、`wa.uploadTextAndLivePhoto(...)`、`wa.uploadTextAndLivePhotoList(...)`、`wa.uploadVideo(...)`、`wa.uploadTextAndVideo(...)` 会返回 `Boolean`，表示是否成功写入微信本地发布队列；写入成功后会尝试触发上传，但不把触发结果作为重复入队依据。脚本全局函数仍保持 WA 同款 `void` 签名。
- 纯文字朋友圈 `uploadText(...)` 现在优先走微信原生 `TextWidget -> UploadManager.shareAppMsgImp(WXTextObject)` 链路，避免把纯文字错误发成 Hchat 自建的 `UploadPackHelper(1, context)` 模板；图文和视频朋友圈仍复用 `UploadPackHelper` 入队。

## 系统消息、历史消息和未读消息

接口：

| 代码 | 说明 |
| --- | --- |
| `insertSystemMsg(String talker, String content, long createTime)` | 往指定会话插入一条本地系统消息，返回本地消息 ID。 |
| `queryHistoryMsg(String talker, long startTime, int count)` | 查询指定会话历史消息，返回 `List<MsgInfoBean>`。 |
| `getUnreadCount(String talker)` | 获取指定会话的普通未读消息数。 |
| `getAllUnreadCount()` | 获取所有会话的普通未读消息总数。 |
| `clearUnread(String talker)` | 将指定会话标记为已读，成功返回 `true`。 |
| `clearAllUnread()` | 将所有未读会话标记为已读；操作完成且普通未读总数归零时返回 `true`。 |

参数说明：

| 参数 | 说明 |
| --- | --- |
| `talker` | 会话 ID。 |
| `content` | 系统消息内容。 |
| `createTime` | 创建时间，通常传 `System.currentTimeMillis()`。 |
| `startTime` | 查询起始时间（毫秒）；大于 `0` 时从该时间之后按时间正序查询，传 `0L` 时从最近消息开始按数量倒序取。 |
| `count` | 查询数量。 |

示例：

```java
long msgId = insertSystemMsg(getTargetTalker(), "处理中...", System.currentTimeMillis());
List history = queryHistoryMsg(getTargetTalker(), 0L, 10);
int unreadCount = getUnreadCount(getTargetTalker());
int allUnreadCount = getAllUnreadCount();
boolean cleared = clearUnread(getTargetTalker());
boolean allCleared = clearAllUnread();
```

`queryHistoryMsg(...)` 返回的每一项与 `onHandleMsg(...)` 的 `MsgInfoBean` 用法一致，可读取字段或调用 `getContent()`、`getTalker()`、`getSendTalker()`、`getCreateTime()`、`isText()` 等方法。`getUnreadCount(...)` 和 `getAllUnreadCount()` 只统计普通未读消息。`clearUnread(...)` 通过微信原生会话存储入口清空指定会话的普通未读、免打扰未读和 @ 计数；`clearAllUnread()` 对所有未读会话执行同样操作并清理微信通知。清空接口会触发会话列表刷新；`clearAllUnread()` 在当前没有未读会话时也返回 `true`，执行后仍有普通未读时返回 `false`。

## 微信数据库

`getDatabaseApi()` 返回脚本可直接使用的微信数据库 API；数据库或 DexKit 尚未就绪时可能返回 `null`。R8 构建会保留该 API 的公开方法名，BeanShell 插件可以稳定调用。

| 代码 | 说明 |
| --- | --- |
| `boolean isAvailable()` | 必要的 DexKit 定位结果是否已就绪。 |
| `boolean isReady()` | 数据库 wrapper 和查询方法是否已初始化。 |
| `Cursor rawQuery(String sql, String[] args)` | 执行原始查询；插件必须关闭返回的 `Cursor`。 |
| `List<Map<String, Object>> query(String sql, String[] args)` | 执行查询并返回 Map 列表，内部自动关闭 `Cursor`。 |
| `String queryFirstString(String sql, String[] args, String columnName)` | 读取查询结果的第一个字符串值。 |
| `long insert(String table, String nullColumnHack, ContentValues values)` | 插入记录，失败返回 `-1`。 |
| `int update(String table, ContentValues values, String whereClause, String[] whereArgs)` | 更新记录，失败返回 `-1`。 |
| `int delete(String table, String whereClause, String[] whereArgs)` | 删除记录，失败返回 `-1`。 |
| `String messageTableForTalker(String talker)` | 返回会话对应的实际消息表名。 |
| `List<String> messageTables()` | 返回已识别的消息表。 |
| `Object storageObjectForMethod(Method method)` | 从 CoreStorage 对象图中定位能调用指定方法的 storage 实例。 |

示例：

```java
Object database = getDatabaseApi();
if (database == null || !database.isAvailable()) {
    throw new IllegalStateException("微信数据库尚未就绪");
}

List rows = database.query(
    "SELECT username FROM rcontact WHERE username=? LIMIT 1",
    new String[] { "wxid_xxx" }
);
int deleted = database.delete(
    "rconversation",
    "username=?",
    new String[] { "wxid_xxx" }
);
```

数据库写操作会直接修改微信本地数据，只应在用户明确要求时使用。表名和字段必须来自已确认的当前微信版本结构；不要拼接用户输入到 SQL，条件值应通过 `String[]` 绑定。

## HTTP 和下载

接口：

| 代码 | 说明 |
| --- | --- |
| `get(String url, Map headerMap, Consumer callback)` | 发起 GET 请求。 |
| `get(String url, Map headerMap, long timeout, Consumer callback)` | 发起 GET 请求，并指定超时时间。 |
| `get(String url, Map headerMap, PluginCallBack.HttpCallback callback)` | WA 同款 GET 请求回调写法。 |
| `get(String url, Map headerMap, long timeout, PluginCallBack.HttpCallback callback)` | WA 同款 GET 请求回调写法，并指定超时时间。 |
| `post(String url, Map paramMap, Map headerMap, Consumer callback)` | 发起 POST 请求。 |
| `post(String url, Map paramMap, Map headerMap, long timeout, Consumer callback)` | 发起 POST 请求，并指定超时时间。 |
| `post(String url, Map paramMap, Map headerMap, PluginCallBack.HttpCallback callback)` | WA 同款 POST 请求回调写法。 |
| `post(String url, Map paramMap, Map headerMap, long timeout, PluginCallBack.HttpCallback callback)` | WA 同款 POST 请求回调写法，并指定超时时间。 |
| `download(String url, String path, Map headerMap, Consumer callback)` | 下载文件。 |
| `download(String url, String path, Map headerMap, long timeout, Consumer callback)` | 下载文件，并指定超时时间。 |
| `download(String url, String path, Map headerMap, PluginCallBack.DownloadCallback callback)` | WA 同款下载回调写法。 |
| `download(String url, String path, Map headerMap, long timeout, PluginCallBack.DownloadCallback callback)` | WA 同款下载回调写法，并指定超时时间。 |
| `downloadImage(String url, Consumer callback)` | 下载单张图片到 `Hchat/Image`。 |
| `downloadImage(String url, String fileName, Consumer callback)` | 下载单张图片到 `Hchat/Image`，并指定文件名。 |
| `downloadImg(String md5, String cdnUrl, String aesKey, String savePath)` | WA 同款图片下载薄封装，后台下载 `cdnUrl` 到 `savePath`；支持 HTTP URL 和微信图片 CDN fileid，CDN fileid 走微信 Mars CDN 下载并使用 `aesKey` 解密，兼容 `[AtWx=...]` 包装和 XML 转义 URL；脚本传入 `cdnbigimgurl` 时按该高清 fileid 下载。 |
| `downloadImg(Object imageMsg, String savePath)` | WA 同款图片消息下载便捷封装，自动从 `imageMsg` 取 MD5、高清/普通/缩略图 CDN 地址、长度和 AES key，优先高清图，高清为空时降级普通图。 |
| `downloadImg(Object imageMsg, String savePath, PluginCallBack.DownloadCallback callback)` | 异步下载图片消息，成功返回完整 `File`，失败返回异常。 |
| `downloadImages(List urlList, Consumer callback)` | 批量下载图片到 `Hchat/Image`。 |
| `downloadImages(List urlList, String prefix, Consumer callback)` | 批量下载图片到 `Hchat/Image`，并用前缀生成文件名。 |
| `downloadImg(String md5, String cdnUrl, String aesKey, String savePath, PluginCallBack.DownloadCallback callback)` | 异步下载图片 CDN 文件，成功返回完整 `File`，失败返回异常。 |
| `downloadVideo(String md5, String cdnUrl, String aesKey, String savePath, PluginCallBack.DownloadCallback callback)` | 异步下载视频 CDN 文件，成功返回完整 `File`，失败返回异常。 |
| `downloadVideo(Object videoMessage, String savePath, PluginCallBack.DownloadCallback callback)` | 异步下载聊天视频；可传整条消息、原始消息或 `getVideoMsg()` 结果。 |
| `downloadFinderMedia(Object finderFeedOrMessage, String savePath, PluginCallBack.DownloadCallback callback)` | 异步下载视频号图片或视频，默认下载媒体索引 `0`。 |
| `downloadFinderMedia(Object finderFeedOrMessage, int mediaIndex, String savePath, PluginCallBack.DownloadCallback callback)` | 异步下载指定索引的视频号图片或视频。 |

参数说明：

| 参数 | 说明 |
| --- | --- |
| `url` | 请求地址。 |
| `headerMap` | 请求头 Map，可传空 Map。 |
| `paramMap` | POST 参数 Map。`Content-Type` 是 `application/json` 时会转成 JSON，否则按表单提交。 |
| `path` | 下载保存路径。可以是完整文件路径，也可以是目录。 |
| `fileName` | 图片文件名；为空时自动按时间生成。 |
| `urlList` | 图片 URL 列表。 |
| `prefix` | 批量图片文件名前缀；为空时自动生成文件名。 |
| `videoMessage` | 推荐直接传 `onHandleMsg` 收到的整条视频消息；这样可从 `imgPath` 查询原生 `VideoInfo`，不依赖消息正文 XML。 |
| `finderFeedOrMessage` | 原生 `BaseFinderFeed`、持有该对象的上下文/消息对象、可直接调用 `getMediaType()` / `getMediaList()` 的 Finder 对象、聊天消息对象或视频号分享 XML 字符串。 |
| `mediaIndex` | 视频号媒体索引，从 `0` 开始；不传时默认 `0`。多图动态需要按索引分别调用。 |
| `timeout` | 超时时间，单位秒。 |
| `callback` | 回调。`get/post` 返回响应文本；`download`、`downloadImage`、`downloadVideo` 和 `downloadFinderMedia` 返回下载后的 `File`；`downloadImages` 返回成功下载的 `File` 列表。普通 Consumer 接口失败时可能返回 `null` 或空列表，`PluginCallBack` 接口失败时调用 `onError(Exception)`。 |

说明：

- HTTP 是异步回调，不会直接返回响应内容。
- `timeout` 单位是秒。
- `get/post` 回调参数是响应文本，失败时可能是 `null`。
- `download` 回调参数是下载后的 `File`，失败时可能是 `null`。
- 使用 `PluginCallBack.HttpCallback` 时，成功走 `onSuccess(200, response)`，失败走 `onError(Exception)`。
- 使用 `PluginCallBack.DownloadCallback` 时，成功走 `onSuccess(File)`，失败走 `onError(Exception)`；`onProgress` 当前只作为兼容方法保留。
- `downloadImage` / `downloadImages` 是异步图片下载薄封装，保存目录固定为微信媒体目录下的 `Hchat/Image`，脚本不需要自己拼保存路径。
- `downloadImg(...)` 是 WA 兼容无回调接口，`savePath` 可传完整文件路径或目录；HTTP URL 直接下载，微信图片 CDN fileid 会走微信 Mars CDN `CdnManager$C2CDownloadRequest + startC2CDownload` 并使用 `aesKey` 解密。下载前会剥离 `[AtWx=...]` 包装并反转 `&amp;` 等 XML 实体；CDN fileKey 按 FkWeChat 同款 `fk_dl_<hash>_<time>` 生成，框架会 hook 微信已初始化的 `CdnManager` 实例提交下载；无回调下载会等完整文件落到 `savePath`，避免脚本轮询 `File.exists()` 时读到 0 字节或半成品文件。
- 带四个媒体参数和 `PluginCallBack.DownloadCallback` 的 `downloadImg(...)` 会在后台复用同一图片 CDN 下载链路；成功调用 `onSuccess(File)`，地址无效、提交失败或超时时调用 `onError(Exception)`。该重载适合插件已经持有 `md5`、`cdnUrl` 和 `aesKey` 的场景。
- 带 `PluginCallBack.DownloadCallback` 的图片消息重载在后台下载；成功调用 `onSuccess(File)`，图片对象无效、提交失败或超时时调用 `onError(Exception)`。
- `downloadVideo(...)` 始终异步执行。传整条消息时先复用本地完整 MP4；本地文件缺失或不完整时，通过消息 `imgPath` 查询微信原生 `VideoInfo`，再使用视频文件类型 `4` 提交 Mars CDN 下载。`savePath` 可传完整文件路径或目录；为空时保存到 `Hchat/Video`。
- 视频消息正文经常没有 `cdnvideourl` XML，因此优先使用 `downloadVideo(msg, savePath, callback)`，不要只依赖 `msg.getVideoMsg()`。直接 CDN 参数重载适合插件已经持有 `md5/cdnUrl/aesKey` 的场景；没有 AES key 的普通 HTTP URL 会按普通文件下载。
- 视频下载成功、失败或等待 60 秒超时都会且只会调用一次回调。回调线程不固定，插件需要更新界面时应自行切换到主线程。
- `downloadFinderMedia(...)` 是 `alt-entry` 专属接口，复用模块视频号菜单已验证的原生媒体解析、H.265 地址选择和解密链路，同时支持图片与视频。`savePath` 为空时保存到 `Hchat/Finder`；可传完整文件路径，目录应传已存在目录或以 `/` 结尾的路径。每次调用只返回一个 `File`，多图动态使用带 `mediaIndex` 的重载逐张下载。
- 视频号接口优先解析调用现场持有的原生 Finder 对象；传聊天消息或原始 XML 时，结构化读取 `objectId`、`objectNonceId`、`sourceCommentScene` 与 `finderFeed/mediaList/media`。聊天分享 XML 通常不含解密密钥，接口会走微信原生详情请求取得完整 `FinderObject` 后再下载。视频下载结果必须具有标准 MP4 `ftyp` 文件头；密文、错误响应或残缺文件会被删除并调用 `onError(Exception)`，不会作为成功文件返回。无法取得有效媒体时同样调用 `onError(Exception)`。下载完成或失败各只回调一次，回调线程不固定。

示例：

```java
java.util.HashMap headers = new java.util.HashMap();
headers.put("User-Agent", "Hchat");

get("https://example.com", headers, new java.util.function.Consumer() {
    public void accept(Object body) {
        log(body);
    }
});

get("https://example.com", headers, new PluginCallBack.HttpCallback() {
    public void onSuccess(int statusCode, String response) {
        log(response);
    }

    public void onError(Exception e) {
        log(e.getMessage());
    }
});

downloadImage("https://example.com/a.png", new java.util.function.Consumer() {
    public void accept(Object file) {
        log("图片下载完成=" + file);
    }
});

java.util.ArrayList urls = new java.util.ArrayList();
urls.add("https://example.com/a.png");
urls.add("https://example.com/b.png");
downloadImages(urls, "album", new java.util.function.Consumer() {
    public void accept(Object files) {
        log("批量图片下载完成=" + files);
    }
});

void onHandleMsg(Object msg) {
    if (!msg.isVideo()) return;
    downloadVideo(msg, pluginDir + "/video/", new PluginCallBack.DownloadCallback() {
        public void onSuccess(File file) {
            log("视频下载完成=" + file.getAbsolutePath());
        }

        public void onError(Exception error) {
            log("视频下载失败=" + error.getMessage());
        }
    });
}

void downloadFinderItem(Object finderFeed, int index) {
    downloadFinderMedia(finderFeed, index, pluginDir + "/finder/", new PluginCallBack.DownloadCallback() {
        public void onSuccess(File file) {
            log("视频号媒体下载完成=" + file.getAbsolutePath());
        }

        public void onError(Exception error) {
            log("视频号媒体下载失败=" + error.getMessage());
        }
    });
}
```

## 菜单扩展接口

脚本可以向微信右上角加号菜单和聊天消息长按菜单注册自定义条目。每次注册返回一个句柄；插件可以保存句柄并通过 `removeMenu(...)` 主动移除对应条目。菜单接口只用于微信主进程；`process=appbrand` 的插件不要注册微信 UI 菜单，`process=all` 的插件应先判断 `isMainProcess`。

### 右上角加号菜单

```beanshell
Object registerPlusMenu(String title, String iconPath, Consumer callback);
Object registerPlusMenu(String title, String iconPath, boolean front, Consumer callback);
Object registerPlusMenu(String title, Consumer callback);
Object registerPlusMenu(String title, boolean front, Consumer callback);
```

`Consumer.accept(Object value)` 收到当前加号菜单所在的 `Activity`；无法解析页面时参数可能为 `null`。插件条目被点击后，模块会拦截微信原点击方法、去除短时间重复点击并先关闭加号菜单，再执行插件回调，插件不需要自行关闭菜单。

### 聊天长按消息菜单

```beanshell
Object registerMessageMenu(String title, String iconPath, Consumer callback);
Object registerMessageMenu(String title, String iconPath, boolean front, Consumer callback);
Object registerMessageMenu(String title, Consumer callback);
Object registerMessageMenu(String title, boolean front, Consumer callback);
```

`Consumer.accept(Object value)` 收到当前长按消息对应的真实 `ScriptMessageBean`。模块从本次长按行绑定的微信原生消息对象读取本地消息 ID，再按 ID 查询消息并构造回调参数；不会根据列表位置猜测消息。没有有效消息绑定时不会执行回调。回调对象可直接调用 `getMsgId()`、`getTalker()`、`getSendTalker()`、`getContent()`、`isText()` 等消息接口。

插件菜单项被点击后，模块会拦截微信原点击方法、去除短时间重复点击、移除本次菜单项绑定并清理临时消息绑定，然后执行插件回调；不会继续调用微信原生菜单项处理方法。插件不需要自行调用原生点击方法或清理消息选中状态。

### 图标、位置和移除

```beanshell
void removeMenu(Object handle);
```

- `title` 会去除首尾空白；空标题或空回调注册失败并返回 `null`。
- `iconPath` 可传绝对文件路径，也可传相对当前插件目录的路径。为空时使用无自定义图标的菜单项；图片读取失败时同样不会设置自定义图标。
- `front=true` 表示把插件条目放到微信原生菜单项之前；不传 `front` 时默认为 `false`。多个前置条目按注册顺序排列。模块会按最终显示顺序整体重排右上角加号菜单，并把宿主 `SparseArray` 压平为从 `0` 开始的连续位置 key；插件移除或重载后也会重新压平，不会留下空位置。
- 每次成功注册都会返回独立句柄。建议保存句柄并在不再需要时调用 `removeMenu(handle)`；传入 `null` 或已经移除的句柄不会重新注册条目。
- 插件关闭、重载或加载失败清理时，模块会按插件自动移除其全部菜单条目。`onUnload()` 中手动移除适合插件运行期间按自身状态提前撤销菜单，不是防止重载残留的必要条件。

完整示例：

```java
Object plusMenuHandle = null;
Object messageMenuHandle = null;

void onLoad() {
    plusMenuHandle = registerPlusMenu(
        "插件入口",
        "icons/plus.png",
        true,
        new java.util.function.Consumer() {
            public void accept(Object activity) {
                log("点击加号菜单，activity=" + activity);
                openSettings();
            }
        }
    );

    messageMenuHandle = registerMessageMenu(
        "处理消息",
        "icons/message.png",
        new java.util.function.Consumer() {
            public void accept(Object msg) {
                log("msgId=" + msg.getMsgId());
                log("talker=" + msg.getTalker());
                log("sender=" + msg.getSendTalker());
                log("content=" + msg.getContent());
            }
        }
    );
}

void onUnload() {
    removeMenu(plusMenuHandle);
    removeMenu(messageMenuHandle);
    plusMenuHandle = null;
    messageMenuHandle = null;
}
```

### 视频号下载

`alt-entry` 提供视频号图片和视频的专用异步下载接口：

```java
void downloadFinderMedia(Object finderFeedOrMessage, String savePath, PluginCallBack.DownloadCallback callback);
void downloadFinderMedia(Object finderFeedOrMessage, int mediaIndex, String savePath, PluginCallBack.DownloadCallback callback);
```

接口接受原生 `BaseFinderFeed`、持有该对象的上下文对象、可直接提供 `getMediaType/getMediaList` 的 Finder 对象、聊天消息对象或视频号分享 XML 字符串。默认下载索引 `0`；多图按索引分别调用。聊天分享会结构化解析 `finderFeed/mediaList/media`，无法取得有效媒体时调用 `onError(Exception)`。普通聊天视频继续使用 `downloadVideo(...)`，不要混用两套接口。

## 延迟、通知和重载

薄封装总览：

- `reloadPlugin()`
- `compileSnapshot(String path)`
- `evalSnapshot(String path)`
- `eval(String code)`
- `loadJava(String path)`
- `useCallback(String callbackName, String methodName)`
- `loadDex(String path)`
- `loadSo(String path)`
- `loadSo(String path, ClassLoader loader)`
- `getDuration(String filePath)`

说明：

- `reloadPlugin()` 异步重载当前插件。
- `compileSnapshot(String path)` 编译指定脚本为加密快照，返回生成的快照文件绝对路径。
- `evalSnapshot(String path)` 执行指定的快照文件，返回脚本执行结果。
- `loadJava` 支持绝对路径；相对路径从当前插件目录开始找。
- `useCallback` 可以把标准回调绑定到加载进来的非标准方法名。
- `loadDex` 支持绝对路径；相对路径从当前插件目录开始找，返回加载后的 `ClassLoader`。
- `loadSo` 支持绝对路径；相对路径从当前插件目录开始找。JNI 方法必须属于真实 Java 类，不能声明为 BeanShell 顶层函数；类来自 BeanShell 动态类或 `loadDex` 时，应把对应的 `ClassLoader` 传给双参数重载。
- `getDuration` 返回毫秒，失败返回 `0`。

接口：

| 代码 | 说明 |
| --- | --- |
| `delay(long millis, Runnable action)` | 延迟执行一段代码。 |
| `notify(String title, String text)` | 发送模块通知。 |
| `reloadPlugin()` | 异步重载当前插件。 |
| `compileSnapshot(String path)` | 编译指定脚本为 BeanShell 加密快照，返回快照文件绝对路径。 |
| `evalSnapshot(String path)` | 执行指定的 BeanShell 加密快照，返回脚本执行结果。 |
| `evalSnapshot(InputStream inputStream)` | 从输入流执行 BeanShell 加密快照，返回脚本执行结果。 |
| `evalSnapshot(byte[] data)` | 从字节数组执行 BeanShell 加密快照，返回脚本执行结果。 |
| `eval(String code)` | 在当前插件环境里执行一段 BeanShell 代码。 |
| `loadJava(String path)` | 加载并执行另一个 Java/BeanShell 脚本文件。 |
| `useCallback(String callbackName, String methodName)` | 把标准回调绑定到指定方法名。 |
| `loadDex(String path)` | 加载 dex/jar/apk 文件，返回 `ClassLoader`。 |
| `loadSo(String path)` | 为微信宿主 `ClassLoader` 加载 Native SO。 |
| `loadSo(String path, ClassLoader loader)` | 为指定 `ClassLoader` 加载 Native SO。 |

参数说明：

| 参数 | 说明 |
| --- | --- |
| `millis` | 延迟毫秒数。 |
| `action` | 延迟后执行的 `Runnable`。 |
| `title` | 通知标题。 |
| `text` | 通知内容。 |
| `path` 用于 `compileSnapshot` | 要编译的脚本路径；支持绝对路径，相对路径从当前插件目录开始找。 |
| `path` 用于 `evalSnapshot` | 要执行的快照路径；支持绝对路径，相对路径从当前插件目录开始找。 |
| `inputStream` | 快照数据输入流。 |
| `data` | 快照数据字节数组。 |
| `code` | 要执行的脚本文本。 |
| `path` | 脚本文件路径；相对路径从当前插件目录开始找。 |
| `callbackName` | 标准回调名，例如 `onHandleMsg`、`onLoad`。 |
| `methodName` | 当前脚本环境里已有或稍后会加载的方法名。 |
| `path` 用于 `loadDex` | dex/jar/apk 文件路径；支持绝对路径，相对路径从当前插件目录开始找。 |
| `path` 用于 `loadSo` | `.so` 文件路径；支持绝对路径，相对路径从当前插件目录开始找。 |
| `loader` | 声明 JNI 类的加载器，通常直接使用 `loadDex()` 的返回值。 |

说明：

- `reloadPlugin()` 会异步重载当前插件。
- `compileSnapshot(String path)` 当前会在源脚本旁边生成同名 `.bshs` 快照文件，例如 `main.java.bshs`。
- `evalSnapshot(String path)` / `evalSnapshot(InputStream inputStream)` / `evalSnapshot(byte[] data)` 只执行 `.bshs` 加密快照；快照使用模块内置兼容 AES 密钥，可在不同插件目录间加载。
- `compileSnapshot` / `evalSnapshot` 可以在脚本顶层代码和 `onLoad()` 中调用，不要求插件先完成加载登记。
- 如果 `main.java` 只是入口包装，真正的回调定义在 `eval(String code)`、`evalSnapshot()` 或 `loadJava()` 加载的文件里，模块会在执行后刷新并识别这些回调；入口顶层异步加载时也会在插件登记后补充扫描，避免回调因加载时序丢失。
- 如果加载进来的方法不是标准回调名，可以在顶层用 `useCallback(...)` 或 `useOnHandleMsg(...)` 这类别名接口绑定。
- `loadJava` 支持绝对路径；相对路径从当前插件目录开始找。
- `delay()` 按每次插件加载实例管理。实例卸载、重载或加载失败会取消尚未开始的任务，并阻止旧实例继续排队；已经执行的回调不能强制终止。主进程回调在主线程，小程序回调在共享的两个后台调度线程执行。每实例最多等待 128 项、每进程最多等待 512 项，超限跳过新任务并限频记录错误；插件间的延迟任务互不覆盖。小程序进程的启用状态仍按本文前述规则，在该进程重新启动时应用。
- `loadDex` 会先把 dex/jar/apk 复制到微信私有 `code_cache` 并设为只读后加载，返回的 `ClassLoader` 也会加入当前 BeanShell 解释器。
- `loadSo` 仅支持 64 位微信进程，会校验 ELF 为 `arm64-v8a`，再按内容哈希复制到微信私有 `code_cache/hchat_plugin_native/<pluginId>/`，设为只读后加载。ARM32 SO 和 32 位微信进程均不支持。
- 不要在脚本顶层声明 `native void method();`。BeanShell 顶层函数不是 Java 类成员，无法匹配 JNI 类名。可以在脚本内声明包含 `native` 方法的类，并把 `NativeClass.class.getClassLoader()` 传给 `loadSo`；也可以使用 `loadDex` 加载编译好的 JNI 包装类。类全名和方法名必须与 SO 导出的 JNI 符号或 `RegisterNatives` 目标一致。
- Native 库不能随插件关闭或重载而卸载。同一路径、相同内容和同一 `ClassLoader` 会直接复用；替换 SO 内容后，重新加载插件并把新 BeanShell JNI 类的 `ClassLoader` 传给双参数 `loadSo`，模块会生成独立只读副本并加载新版本，不需要重启微信。单参数 `loadSo` 使用固定宿主 `ClassLoader`，或者插件继续复用旧 JNI 类的 `ClassLoader` 时不能可靠热更新。旧版本仍驻留到微信进程结束，开发时连续热更新很多次后可主动重启微信释放 Native 内存。依赖其它插件 SO 时，应按依赖顺序逐个调用 `loadSo`。
- Native 代码可直接导致微信进程崩溃，只加载来源可信的 SO；插件 Agent 会把 SO 加载识别为高风险代码并在写入前确认。

### 重载插件

重新加载当前插件，通常用于修改脚本或配置后主动触发重载。

```java
reloadPlugin();
```

### 执行代码

在当前插件运行时内继续执行一段脚本代码。

```java
void eval(String code);
```

示例：

```java
eval("log(\"hello from eval\")");
```

### 编译快照

把脚本编译成 BeanShell 加密快照文件，便于后续按 WA 同款方式加载。

```java
String compileSnapshot(String path);
```

示例：

```java
String snapshotPath = compileSnapshot(pluginDir + "/main.java");
log(snapshotPath);
```

### 执行快照

执行已经编译好的 BeanShell 加密快照文件。

```java
Object evalSnapshot(String path);
Object evalSnapshot(InputStream inputStream);
Object evalSnapshot(byte[] data);
```

示例：

```java
Object result = evalSnapshot(pluginDir + "/main.java.bshs");
log(result);

byte[] data = ...;
evalSnapshot(data);
```

### 导入 Java 源文件

```java
void loadJava(String path);
```

- `path`：支持绝对路径；相对路径默认相对于当前插件目录，也就是 `pluginDir`

示例：

```java
loadJava("extra/Main.java");
```

### 导入 Dex 文件

```java
ClassLoader loadDex(String path);
```

- `path`：支持绝对路径；相对路径默认相对于当前插件目录，也就是 `pluginDir`

示例：

```java
ClassLoader cl = loadDex("libs/demo.dex");
Class clazz = cl.loadClass("com.example.Demo");
```

### 加载 Native SO

```java
void loadSo(String path);
void loadSo(String path, ClassLoader loader);
```

- `path`：支持绝对路径；相对路径默认相对于当前插件目录，也就是 `pluginDir`
- `loader`：SO 中 JNI 类对应的 `ClassLoader`；BeanShell 动态类传 `NativeClass.class.getClassLoader()`，JNI 类来自 `loadDex()` 时传其返回值
- 仅支持 64 位微信进程；SO 必须是 `arm64-v8a` ELF 文件，不支持 ARM32 SO 或 32 位微信
- JNI 方法不能声明在脚本顶层；类全名和方法名必须匹配 SO 导出的 JNI 符号或 `RegisterNatives` 目标

示例：

```java
package com.example.plugin;

class NativeDemo {
    public static native String decrypt(String name);
}

loadSo("libs/arm64-v8a/libdemo.so", NativeDemo.class.getClassLoader());
String result = NativeDemo.decrypt("demo");

ClassLoader cl = loadDex("libs/demo.dex");
loadSo("libs/arm64-v8a/libdemo_jni.so", cl);
Class clazz = cl.loadClass("com.example.NativeDemo");
```

模块会把 SO 复制到微信私有代码缓存并设为只读。替换 SO 后重新加载插件，并把重新生成的 JNI 类对应 `ClassLoader` 传给双参数 `loadSo`，即可加载新内容，不需要重启微信；同一内容和同一 `ClassLoader` 不会重复加载。单参数 `loadSo` 使用固定宿主 `ClassLoader`，不能用于热更新。Android 无法安全卸载已经加载的 Native 库，因此旧版本会驻留到微信进程结束，连续热更新很多次后可主动重启微信释放 Native 内存。

示例：

```java
delay(1000, new Runnable() {
    public void run() {
        notify("Hchat", "延迟完成");
    }
});

eval("log(\"来自 eval\")");
loadJava("extra.java");
reloadPlugin();
```

## 音频转换

### 取音频时长

返回音频文件时长，单位为毫秒。

```java
long getDuration(String filePath);
```

- `filePath`：音频文件路径
- 返回值：音频时长，单位毫秒；失败时返回 `0`

示例：

```java
long duration = getDuration(cacheDir + "/voice.mp3");
log("duration = " + duration + " ms");
```

接口：

| 代码 | 说明 |
| --- | --- |
| `getFileType(String filePath)` | 获取真实音频类型。返回 `0=未知 1=Silk 2=MP3 3=WAV 4=FLAC 5=OGG 6=PCM 7=M4A 8=MP4`。 |
| `mp3ToSilk(String mp3Path, String silkPath)` | WA 同款，MP3 转 Silk，默认 `24000Hz`。 |
| `mp3ToSilk(String mp3Path, String silkPath, int hz)` | MP3 转 Silk，自定义采样率。 |
| `wavToSilk(String wavPath, String silkPath, int hz)` | WAV 转 Silk。 |
| `flacToSilk(String flacPath, String silkPath, int hz)` | FLAC 转 Silk。 |
| `oggToSilk(String oggPath, String silkPath, int hz)` | OGG 转 Silk，支持 Ogg Vorbis 和 Ogg Opus。 |
| `pcmToSilk(String pcmPath, String silkPath, int hz, int pcmHz, int channels)` | PCM 转 Silk。 |
| `autoToSilk(String audioPath, String silkPath, int hz)` | 自动识别输入格式并转 Silk。 |
| `silkToMp3(String silkPath, String mp3Path)` | WA 同款，Silk 转 MP3，默认 `24000Hz`。 |
| `silkToMp3(String silkPath, String mp3Path, int hz)` | Silk 转 MP3，自定义采样率。 |
| `silkToPcm(String silkPath, String pcmPath, int hz)` | Silk 转 PCM。 |
| `mp3ToPcm(String mp3Path, String pcmPath)` | MP3 转 PCM。 |
| `wavToPcm(String wavPath, String pcmPath)` | WAV 转 PCM。 |
| `flacToPcm(String flacPath, String pcmPath)` | FLAC 转 PCM。 |
| `oggToPcm(String oggPath, String pcmPath)` | OGG 转 PCM，支持 Ogg Vorbis 和 Ogg Opus。 |
| `autoToPcm(String audioPath, String pcmPath)` | 自动识别输入格式并转 PCM。 |
| `getAudioInfo(String filePath)` | 读取音频信息，返回 `Map`，至少包含 `sampleRate`、`channelCount`。 |
| `decodeAacFile(String aacPath, String pcmPath)` | AAC 解码到 PCM。 |
| `encodePcmToAac(String pcmPath, String aacPath, int sampleRate, int channels)` | PCM 编码 AAC。 |
| `encodePcmToM4a(String pcmPath, String m4aPath, int sampleRate, int channels)` | PCM 编码 M4A。 |
| `mp4ToSilk(String mp4Path, String silkPath, int hz)` | MP4 音频转 Silk。 |
| `silkToM4a(String silkPath, String m4aPath, int hz)` | Silk 转 M4A。 |
| `mp4ToM4a(String mp4Path, String m4aPath, int hz)` | MP4 转 M4A。 |
| `mp4ToAac(String mp4Path, String aacPath, int hz)` | MP4 转 AAC。 |
| `m4aToSilk(String m4aPath, String silkPath, int hz)` | M4A 转 Silk。 |
| `aacToSilk(String aacPath, String silkPath, int hz)` | AAC 转 Silk。 |
| `m4aToAac(String m4aPath, String aacPath, int hz)` | M4A 转 AAC。 |
| `m4aToM4a(String m4aPath, String m4aPathOut, int hz)` | M4A 转 M4A。 |
| `autoToAac(String inputPath, String aacPath, int hz)` | 自动识别输入格式并转 AAC。 |
| `autoToM4a(String inputPath, String m4aPath, int hz)` | 自动识别输入格式并转 M4A。 |
| `autoAacToSilk(String inputPath, String silkPath, int hz)` | 自动 AAC/M4A 输入转 Silk。 |
| `silkToAac(String silkPath, String aacPath, int hz)` | Silk 转 AAC。 |
| `aacToPcm(String aacPath, String pcmPath)` | AAC 转 PCM。 |
| `pcmToAac(String pcmPath, String aacPath, int sampleRate, int channels)` | PCM 转 AAC。 |
| `pcmToM4a(String pcmPath, String m4aPath, int sampleRate, int channels)` | PCM 转 M4A。 |
| `m4aToPcm(String m4aPath, String pcmPath)` | M4A 转 PCM。 |
| `decodeM4aFile(String m4aPath, String pcmPath)` | M4A 解码到 PCM。 |
| `getDuration(String filePath)` | 获取音频时长，返回毫秒，失败返回 `0`。 |
| `getDurationLimited(String filePath)` | 获取限制后的时长，超过 60 秒时截断到 60000。 |
| `getErrorMessage(int code)` | 将音频转换错误码转成文字。 |
| `startTransform(int type, String inputPath, String outputPath, int sampleRate, Consumer callback)` | 调用 Hchat 的通用转换入口，异步回调消息/进度。 |

参数说明：

| 参数 | 说明 |
| --- | --- |
| `filePath` | 音频文件路径。 |
| `mp3Path / wavPath / flacPath / oggPath / pcmPath / silkPath / aacPath / m4aPath / mp4Path / audioPath / inputPath` | 输入文件路径。 |
| `m4aPathOut / outputPath` | 输出文件路径。 |
| `hz` | 采样率参数。Silk 编解码只接受 `8000/12000/16000/24000`，传其它值会按 `24000` 处理；`mp3ToSilk/silkToMp3` 不传时默认 `24000`。可识别源格式转 AAC/M4A 时会自动使用真实采样率，裸 PCM 输入才依赖这里传入的采样率。 |
| `pcmHz` | 输入 PCM 采样率。 |
| `sampleRate` | `startTransform` 的采样率参数。`type=0/1/5/9` 属于 Silk 编解码，只接受 `8000/12000/16000/24000`，传 `44100` 会按 `24000` 处理；`type=7/8` 对可识别源格式会自动使用真实采样率，裸 PCM 输入才依赖这里传入的采样率。 |
| `channels` | 声道数，常用 `1` 或 `2`。 |
| `type` | `startTransform` 转换类型。 |
| `callback` | `startTransform` 的异步回调。收到的是 `Map`：`type=message/progress`，另带 `message` 或 `progress`。 |

说明：

- 这些方法返回的 `int` 基本都遵循原库约定：`0` 成功，负数是错误码。
- `mp3ToSilk` 和 `silkToMp3` 提供了 WA 同款双参数重载，默认采样率是 `24000Hz`；Silk 相关接口会把不支持的采样率自动归一到 `24000Hz`；可识别源格式会自动使用真实采样率：AAC/M4A/MP4 以 `MediaCodec` 实际输出 PCM 采样率为准，MP3/FLAC/OGG 优先读媒体元数据，WAV 读文件头；AAC/M4A/MP4 转 Silk 会把中间 PCM 重采样到目标 Silk 采样率，避免 HE-AAC/SBR 等文件因容器采样率和解码输出采样率不一致而变速变调。只有裸 PCM 输入因为没有头信息，仍必须依赖脚本传入的 `sampleRate/pcmHz/channels`。
- `getAudioInfo` 返回 `Map`，至少包含 `sampleRate` 和 `channelCount`。
- `getDuration` 和 `getDurationLimited` 返回毫秒。
- `getErrorMessage` 用于把返回的负数错误码转成可读文本。
- Hchat 默认调用本地接管后的 `Silk-Codec-Android` native 方法；上游只支持 Ogg Vorbis，因此检测到首个 Ogg 识别包为 `OpusHead` 时，会改用 Android `MediaExtractor + MediaCodec` 解码 Ogg Opus，再复用 PCM 重采样和 Silk 编码。Opus 解码失败仍返回 OGG 错误码。Silk 相关接口只接受 `8000/12000/16000/24000`，不支持的采样率按 `24000` 处理；可识别源格式会自动使用真实采样率，只有裸 PCM 输入依赖脚本传入的 `sampleRate/pcmHz/channels`；`autoToSilk`、`autoToPcm`、`autoToAac`、`autoToM4a`、`autoAacToSilk` 和 `startTransform` 都走 `ScriptAudioBridge` 的统一分发。
- 插件处理 OGG 时必须调用上述全局方法或 `audio/audioBridge`；`SilkCodecClass` 和脚本自行 `new SilkCodec()` 暴露的是原始上游类，其 OGG 方法仍只支持 Vorbis，不包含 Hchat 的 Opus 兼容分发。

示例：

```java
int code1 = mp3ToSilk(pluginDir + "/a.mp3", cacheDir + "/a.silk");
log("mp3ToSilk=" + code1 + " " + getErrorMessage(code1));

int code2 = silkToMp3(cacheDir + "/a.silk", cacheDir + "/a.mp3", 24000);
log("silkToMp3=" + code2);

java.util.Map info = getAudioInfo(pluginDir + "/a.m4a");
log("sampleRate=" + info.get("sampleRate") + " channels=" + info.get("channelCount"));

long duration = getDuration(pluginDir + "/voice.mp3");
long limited = getDurationLimited(pluginDir + "/voice.mp3");
log("duration=" + duration + " limited=" + limited);

startTransform(7, pluginDir + "/a.mp3", cacheDir + "/a.aac", 44100, new java.util.function.Consumer() {
    public void accept(Object event) {
        log(event);
    }
});
```

## Hook

接口：

| 代码 | 说明 |
| --- | --- |
| `findClass(String className)` | 按完整类名查找微信里的类。 |
| `hookBefore(Member member, Consumer callback)` | 前置 Hook。目标方法执行前调用 `callback`。 |
| `hookAfter(Member member, Consumer callback)` | 后置 Hook。目标方法执行后调用 `callback`。 |
| `hookReplace(Member member, Function callback)` | 替换 Hook。使用 `callback` 的返回值作为原方法返回值。 |
| `unhook(Object handle)` | 取消某个 Hook。 |

参数说明：

| 参数 | 说明 |
| --- | --- |
| `className` | 完整类名，例如 `com.tencent.mm.ui.LauncherUI`。 |
| `member` | `Method` 或 `Constructor`。 |
| `callback` | 回调对象。Hook 参数运行时实际对象是 `XC_MethodHook.MethodHookParam`。脚本加载前会把 `MethodHookParam` 类型声明兼容为 `Object`，运行时回调参数仍是原始 `XC_MethodHook.MethodHookParam`。 |
| `handle` | `hookBefore/hookAfter/hookReplace` 返回的 Hook 句柄。 |

通过这些接口注册的 Hook 会在插件关闭、重载或加载失败时自动清理，并同步从模块全局 Hook 注册表释放句柄。插件自行保存的句柄、对象以及直接调用 Xposed API 注册的 Hook 仍需插件自行管理。

示例：

```java
Class clazz = findClass("com.tencent.mm.ui.LauncherUI");
Method method = clazz.getDeclaredMethod("onResume", new Class[0]);

Object handle = hookAfter(method, new java.util.function.Consumer() {
    public void accept(Object param) {
        log("onResume");
    }
});

unhook(handle);
```

## DexKit

接口：

| 代码 | 说明 |
| --- | --- |
| `findClassList(Object usingStrings)` | 用字符串特征查找类，返回 `Class` 列表。按 WA 写法兼容单个字符串、`List`、`String[]`、`Object[]` 和 BeanShell 大括号数组；会同时收集类命中和方法所在类，没结果时再按单个字符串扩展查找。 |
| `findMemberList(Object usingStrings)` | 用字符串特征查找成员，返回 `Member` 列表，可直接用于 Hook。按 WA 写法兼容单个字符串、`List`、`String[]`、`Object[]` 和 BeanShell 大括号数组；先收集字符串直接命中的方法/构造器，再追加类命中后展开的全部声明方法和构造器；联合查询完全没结果时才按单个字符串扩展查找。 |

参数说明：

| 参数 | 说明 |
| --- | --- |
| `usingStrings` | 字符串特征。可以按 WA 写法传 `{"keyword"}` / `{"keyword1", "keyword2"}`，也可以传单个字符串、字符串数组、`Object[]` 或 `List`。 |

`findClass(String)` 只用于已知且跨版本稳定的完整类名。类名已混淆或会随微信版本变化时，应使用稳定字符串通过 `findClassList` / `findMemberList` 定位，不要把某个版本的混淆类名写进 `findClass`。

`findMemberList` 的返回顺序是“直接方法命中在前，类命中展开成员在后”。类展开可能带来多个同类、同形状的成员，这不代表 DexKit 直接查询命中了多个目标。开发时应先通过逆向结果确认字符串查询直接命中唯一 descriptor，然后按声明类、参数、返回类型和修饰符从前往后取第一个合格成员；不要对包含类展开成员的整个列表强求全局唯一。如果直接查询本身命中多个 descriptor，应增强字符串锚点或结构签名，不能猜测第一项。

示例：

```java
Object classes = findClassList({"MicroMsg", "LauncherUI"});
for (var clazz : classes) {
    log(clazz.getName());
}

Object members = findMemberList({"sendAppMsg", "attachFilePath"});
```

## 反射

接口：

| 代码 | 说明 |
| --- | --- |
| `firstMethod(Object instance, String methodName)` | 查找第一个指定名称的方法。 |
| `firstMethod(Object instance, String methodName, int paramCount)` | 查找第一个指定名称且参数数量匹配的方法。 |
| `firstConstructor(Object instance, int paramCount)` | 查找第一个参数数量匹配的构造函数。 |
| `firstField(Object instance, String fieldName)` | 查找指定字段。 |
| `invokeMethod(Object instance, String methodName)` | 调用无参方法。 |
| `invokeMethod(Object instance, String methodName, Object[] params)` | 调用方法并传入参数。 |
| `invokeMethod(Object instance, String methodName, int paramCount)` | 不传参数数组的兼容重载，仅用于 `paramCount=0` 的无参方法。 |
| `invokeMethod(Object instance, String methodName, int paramCount, Object[] params)` | 按方法名、参数数量和参数调用方法。 |
| `createInstance(Object instance, int paramCount)` | 不传参数数组的兼容重载，仅用于 `paramCount=0` 的无参构造。 |
| `createInstance(Object instance, int paramCount, Object[] params)` | 调用构造函数并传入参数创建对象。 |
| `getField(Object instance, String fieldName)` | 读取字段值。 |
| `setField(Object instance, String fieldName, Object value)` | 修改字段值。 |

参数说明：

| 参数 | 说明 |
| --- | --- |
| `instance` | 对象实例或 `SomeClass.class`。传对象时操作这个对象；传 class 时操作静态成员或创建对象。 |
| `methodName` | 方法名。 |
| `fieldName` | 字段名。 |
| `paramCount` | 参数数量，用于区分重载。 |
| `params` | 参数数组，例如 `new Object[]{1, "abc"}`。 |
| `value` | 要写入字段的新值。 |

说明：

- 查找不到时一般返回 `null`。
- 调用有参数的方法或构造函数时，必须使用带 `Object[] params` 的重载；只传 `paramCount` 不会生成参数值。
- 反射接口适合进阶插件，普通插件优先使用前面的联系人、消息、HTTP 等接口。

示例：

```java
Method method = firstMethod(String.class, "substring", 2);
Object text = invokeMethod("hello", "substring", 2, new Object[]{1, 3});
Object builder = createInstance(StringBuilder.class, 1, new Object[]{"Hchat"});
```

## 最小插件示例

```java
void onLoad() {
    log("插件已加载");
}

boolean onClickSendBtn(String text) {
    if ("/ping".equals(text)) {
        sendText(getTargetTalker(), "pong");
        return true;
    }
    return false;
}

void onHandleMsg(Object msg) {
    if (!msg.isText()) return;
    if (msg.isSend()) return;

    if ("你好".equals(msg.getContent())) {
        sendText(msg.getTalker(), "你好");
    }
}

void onMemberChange(String type, String groupWxid, String userWxid, String userName) {
    if ("join".equals(type)) {
        sendText(groupWxid, "[AtWx=" + userWxid + "] 欢迎加入");
    }
}
```

## 注意事项

- 插件代码有错误时，插件会加载失败并自动关闭。
- 加载失败原因会写入当前插件目录的 `log.txt`。
- 不要在 `onClickSendBtn` 或 `onLongClickSendBtn` 里做耗时操作，否则发送按钮交互会变慢。
- `onHandleMsg` 使用单线程顺序分发，最多等待 128 条消息。慢插件导致队列满时，跳过新到的脚本回调并每 10 秒最多记录一次累计丢弃数，不影响微信消息本身。没有消息消费者时跳过消息包装和去重；卸载最后一个消费者会清空待执行队列。排队消息只交给入队时仍存活的插件实例，不转交重载后的新实例。
- 模块会分别记录超过 `50ms` 的单击/长按发送按钮回调和因解释器忙碌而跳过的插件名；同类日志会限频。
- 网络请求请使用 `get/post/download` 的回调处理结果。
- 文件路径建议使用 `pluginDir` 或 `cacheDir`。
