# Hchat alt-entry 脚本插件开发指南 v2

本指南对应当前 `alt-entry` 分支的脚本运行时，是 Agent 生成插件时的公开 API 依据。不要退回主线旧签名，也不要猜微信混淆类名、方法名或字段。对未在本指南或当前运行时结果中明确确认的能力、可用性或限制，必须明确说明未知或需要运行时验证，不得猜测。

## 文件、生命周期和回调

每个插件位于 `Hchat/脚本插件/<插件目录>/`，必须有 `main.java`，可选 `info.prop`、`README.md`、`config.prop`。`info.prop` 至少包含 `name`、`author`、`version`。插件由用户手动开启，生成代码不能自动启用自己。

`info.prop` 的 `process` 决定加载进程：默认 `process=main`；需要 Hook 小程序进程时写 `process=appbrand`；同一插件确实要在两类进程分别执行时写 `process=all`。不要把普通消息回调、定时任务或自动回复插件设成 `all`。小程序进程只提供轻量 Hook 运行时，没有主进程联系人/消息数据库和 DexKit 就绪状态；必须使用稳定完整类名、当前 `classLoader`、反射和 Hook API，不得在小程序进程新建 DexKit。

生成小程序 Hook 时，在 `onLoad()` 先检查 `isAppBrandProcess`，再用 `findClass("已确认的稳定完整类名")`、`firstMethod/firstConstructor/firstField` 和 `hookBefore/hookAfter/hookReplace`。类名、方法名、参数数量必须先通过 Agent 的 APK 逆向工具或已有多版本证据确认，不能猜。目标是混淆类且必须运行时定位时，用 `process=all`：主进程实例通过 DexKit 定位后用 `putString` 缓存 descriptor，小程序实例只用 `getString` 读取；缓存缺失时跳过并提示重新打开小程序，禁止在小程序进程初始化 DexKit。

```java
void onLoad();
void onUnload();
void openSettings();
boolean onClickSendBtn(String text);
boolean onLongClickSendBtn(String text);
void onHandleMsg(Object msg);
void onImageDownload(Object msg, String imagePath, String talker, String senderWxid);
void onVideoDownload(Object msg, String videoPath, String talker, String senderWxid);
void onFinderMediaDownload(Object msg, String mediaPath, String talker, String senderWxid);
void onProtobufPacket(Object packet);
void onMemberChange(String type, String groupWxid, String userWxid, String userName);
void onNewFriend(String wxid, String ticket, int scene);
```

`onClickSendBtn` 返回 `true` 时拦截发送并清空输入框，返回 `false` 时放行。`onLongClickSendBtn` 在长按微信发送按钮时调用；返回 `true` 时消费长按、阻止随后单击发送并清空输入框，返回 `false` 时继续微信原长按流程，原流程未消费时松手可能继续触发普通单击。两个发送按钮回调都运行在主线程，解释器忙碌时跳过并放行；不得执行网络、文件或长循环任务。

入口脚本加载外部代码后可绑定回调别名：

```java
void useCallback(String callbackName, String methodName);
void useOnLoad(String methodName);
void useOnUnload(String methodName);
void useOpenSettings(String methodName);
void useOnClickSendBtn(String methodName);
void useOnLongClickSendBtn(String methodName);
void useOnHandleMsg(String methodName);
void useOnImageDownload(String methodName);
void useOnVideoDownload(String methodName);
void useOnFinderMediaDownload(String methodName);
void useOnProtobufPacket(String methodName);
void useOnMemberChange(String methodName);
void useOnNewFriend(String methodName);
```

`callbackName` 只使用上面列出的标准回调名。

## 内置环境

已导入 `File`、`Map`、`List`、`Set`、`Consumer`、`Function`、`Field`、`Method`、`Constructor`、`Member`、`XposedBridge`、`XposedHelpers`、`XC_MethodHook`、`WeChatApis`、`KavaReflector`、`DexKitBridge`、`DexFinder`、`DexBridgeHolder`、`PluginCallBack`、fastjson2 常用类和 `me.hd.wauxv.data.bean.MsgInfoBean`。不要重复导入这些类。

可直接使用 `context`、`hostContext`、`classLoader`、`bridge`、`wa`、`waBridge`、`http`、`httpClient`、`audio`、`audioBridge`、`apis`、`dexKit`、`dexKitBridge`、`dexFinder`、`dexBridgeHolder`、`pluginDir`、`pluginDirFile`、`cacheDir`、`cacheDirFile`、`scriptDir`、`scriptDirFile`、`pluginId`、`pluginName`、`pluginAuthor`、`pluginVersion`、`pluginUpdateTime`、`processName`、`pluginProcess`、`isMainProcess`、`isAppBrandProcess`、`hostVerName`、`hostVerCode`、`hostVerClient`、`moduleVer`、`startedAt`。小程序进程中四个 DexKit 对象变量均为 `null`。

类对象变量包括 `WeChatApisClass`、`XposedBridgeClass`、`XposedHelpersClass`、`XC_MethodHookClass`、`DexKitBridgeClass`、`DexFinderClass`、`DexBridgeHolderClass`、`KavaReflectorClass`、`ScriptAudioBridgeClass`、`SilkCodecClass`、`AacCodecClass`、`ConversionClass`、`FieldClass`、`MethodClass`、`ConstructorClass`。

## 消息对象

`onHandleMsg` 参数兼容 `MsgInfoBean`。优先使用 getter：

```text
getMsgId getType getMsgType getCreateTime getCreateTimeSeconds getMsgSvrId
getTalker getTalkerId getSender getSendTalker getSenderId
getContent getText getXml getMsgSource getAtUserList getSelfWxId getNativeUrl
getImageMsg getVideoMsg getQuoteMsg getPatMsg getFileMsg getTransferMsg
```

常用判断：

```text
isSend isSelf isPrivateChat isGroupChat isChatroom isImChatroom isOfficialAccount
isText isImage isVoice isVideo isEmoji isLocation isApp isAppMsg isSystem
isRedPacket isRedBag isTransfer isQuote isFile isLink isMusic isNote
isShareCard isPat isRecalled isAtMe isNotifyAll isAnnounceAll
```

`getTalker()` 是当前会话，群聊时为群 ID；`getSender()` / `getSendTalker()` 才是群内发送者。引用消息的 `getQuoteMsg().getSendTalker()` / `getSenderId()` 返回原消息发送者，不返回群 ID。模块会优先按引用 `svrid` 回查原消息并按原消息发送方向解析真实 wxid；表情等引用 `svrid` 不完整时，会按引用会话、类型、时间和内容回查本地原消息；私聊引用自己时也会返回自己的 wxid，无法确认时不会把群 ID 当作发送者。

`onImageDownload(...)` 只在主进程触发。只有已启用插件声明该回调时模块才下载图片；消息去重后同一张图片只下载一份到 `Hchat/Cache`，再分发给所有订阅插件。`imagePath` 是完整本地文件，`talker` 是会话 ID，`senderWxid` 是发送者。多个插件共享该缓存路径，不要直接删除或修改；需要长期使用时先复制到插件目录。回调应尽快返回。

`onVideoDownload(...)` 同样只在主进程触发。只有已启用插件实际声明该回调时模块才自动下载普通聊天视频；接收视频只有取得长度元数据后才复用本地文件，否则从 `imgPath` 等待原生 `VideoInfo` 并下载，同一消息有独立任务去重。它不是微信界面任意下载任务的全局监听，也不包含视频号分享。多个插件共享 `videoPath`，所有回调结束后模块自动删除临时视频，需要保留时必须在回调内复制。外部方法用 `useOnVideoDownload` 绑定。

`onFinderMediaDownload(...)` 是 `alt-entry` 主进程专属回调。仅在插件声明回调时，模块结构化解析收到的视频号分享消息中的 `objectId`、`objectNonceId` 与 `finderFeed/mediaList/media`；聊天分享 XML 未携带解密信息时，模块会通过微信原生视频号详情请求补齐 `decodeKey`、规格和 PCDN 地址。视频完成解密并通过 MP4 文件头校验后才回调。多媒体动态每个文件回调一次。多个插件共享当前 `mediaPath`，所有回调结束后模块自动删除临时文件，需要保留时必须在回调内复制。外部方法用 `useOnFinderMediaDownload` 绑定。

## Protobuf 数据包

`onProtobufPacket(Object packet)` 异步接收微信 Protobuf 请求和响应。抓包不受模块抓包页开关或 CGI 屏蔽列表影响；先按方向和 CGI ID 过滤，不要在回调中执行耗时工作，也不要无条件重发捕获到的数据包。

```text
getDirection getUri getCgiId getLength getData getJson getJsonObject getTimestamp
isRequest isResponse
```

`getDirection()` 固定返回 `request` / `response`，`getData()` 返回 `byte[]` 副本，`getJson()` 解码失败时返回 `{}`，`getJsonObject()` 返回可直接读取字段的新 `JSONObject`，`getTimestamp()` 是 Unix 毫秒时间戳。运行时使用有界单线程队列分发；插件持续处理过慢时，新数据包回调可能被丢弃。

主动发包支持 JSON 文本和 `JSONObject`，简单重载默认 `funcId=0`、`routeId=0`：

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

返回 `false` 表示没有可用发送路径。回调结果提供 `isSuccess()` / `getMessage()`，可能同步或异步触发。模块会自动签名并编码 JSON，依次尝试原生 Scene、通用发包和同类请求重放。主动发送也可能再次触发抓包回调，禁止在 `onProtobufPacket` 中无条件原样重发。

返回的子结构可直接使用这些方法：

```text
ImageMsg: getMd5 getBigImgUrl getMidImgUrl getThumbUrl getCdnUrl getKey getAesKey
          getBigLength getMidLength getThumbLength
QuoteMsg: getTitle getMsgSource getSendTalker getSenderId getDisplayName getTalker
          getTalkerId getType getContent getSvrId getStrId getCreateTime
PatMsg: getTalker getFromUser getPattedUser getTemplate getCreateTime
FileMsg: getTitle getFileName getSize getExt getMd5 getUrl getKey getAttachId
TransferMsg: getTransactionId getTransferId getTransId getPayerUsername getPayer
             getReceiver getInvalidTime getFee getDescription getRawXml
```

## 配置、日志和基础工具

```java
String getString(String key, String defValue);
Set getStringSet(String key, Set defValue);
boolean getBoolean(String key, boolean defValue);
int getInt(String key, int defValue);
float getFloat(String key, float defValue);
long getLong(String key, long defValue);
void putString(String key, String value);
void putStringSet(String key, Set value);
void putBoolean(String key, boolean value);
void putInt(String key, int value);
void putFloat(String key, float value);
void putLong(String key, long value);
void log(Object msg);
void toast(Object msg);
boolean showModuleDialog(String title, String message);
boolean showModuleDialog(String title, String message, String position);
boolean showModuleConfirmDialog(String title, String message, Consumer callback);
boolean showModuleConfirmDialog(String title, String message, String position, Consumer callback);
boolean showModuleInputDialog(String title, String summary, String initialValue, String placeholder, Consumer callback);
boolean showModuleInputDialog(String title, String summary, String initialValue, String placeholder, String position, Consumer callback);
boolean showModuleChoiceDialog(String title, String summary, List choices, Consumer callback);
boolean showModuleChoiceDialog(String title, String summary, List choices, String position, Consumer callback);
boolean showModuleMultiChoiceDialog(String title, String summary, List choices, Set initialSelected, Consumer callback);
boolean showModuleMultiChoiceDialog(String title, String summary, List choices, Set initialSelected, String position, Consumer callback);
Object applyModuleFloatingGlassBar(View bottomBar);
Object applyModuleFloatingGlassBar(View bottomBar, Map options);
void delay(long millis, Runnable action);
void notify(String title, String text);
```

配置保存在当前插件的 `config.prop`。插件文件使用 `pluginDirFile`，临时文件使用 `cacheDirFile`。

插件需要普通消息、确认、输入、单选或多选弹窗时，默认使用以上 `showModule*Dialog`，不要直接创建 Android `Dialog` / `AlertDialog`。带 `position` 的重载支持 `top`、`center`、`bottom`（也识别“顶部”“居中”“底部”），省略或使用未知值时保持默认底部。这些接口显示与模块一致的 Miuix 弹窗并返回是否成功提交显示请求；微信不在前台、当前没有可用 Activity，或选项列表为空时返回 `false`。确认回调接收 `Boolean`；输入回调接收确认后的 `String`；单选回调接收从 `0` 开始的索引；多选回调接收索引 `Set`。输入、单选和多选取消时不调用回调，回调运行在主线程，耗时工作应另开线程。

`applyModuleFloatingGlassBar` 把插件已经定位到、且已挂在当前 Activity 内容树中的原生底栏 View 转为模块悬浮液态玻璃样式。成功返回带 `restore()` / `isApplied()` 的句柄，失败返回 `null`；同一个 Activity 同时只能托管一个底栏，插件关闭、重载、原父容器离开窗口或 Activity 销毁时会自动恢复。可选 Map 参数：`glass`（默认 true）、`clearBackground`（默认 true）、`horizontalMarginDp`（默认 12）、`bottomMarginDp`（默认 12）。Android 13 以下自动回退普通悬浮样式。接口不负责猜测微信混淆类或查找底栏 View，定位必须有当前版本逆向证据。

## 联系人、标签和群聊

```java
String getLoginWxid();
String getLoginAlias();
String getTargetTalker();
android.app.Activity getTopActivity();
Object getOfficialList();
Object getFriendList();
Object getFriendListInfo();
Object getGroupList();
Object getGroupListInfo();
Object getGroupMemberList(String groupWxid);
Object getGroupMemberListInfo(String groupWxid);
int getGroupMemberCount(String groupWxid);
List getContactLabelList();
List getContactLabelListInfo();
List getContactByLabelId(String labelId);
List getContactByLabelName(String labelName);
String addContactLabel(String labelName);
void modifyContactLabelList(String username, String labelName);
void modifyContactLabelList(String username, List labelNames);
void verifyUser(String wxid, String ticket, int scene);
void verifyUser(String wxid, String ticket, int scene, int privacy);
void addChatroomMember(String chatroomId, String memberWxid);
void addChatroomMember(String chatroomId, List memberWxids);
void inviteChatroomMember(String chatroomId, String memberWxid);
void inviteChatroomMember(String chatroomId, List memberWxids);
void delChatroomMember(String chatroomId, String memberWxid);
void delChatroomMember(String chatroomId, List memberWxids);
```

名称、地区和头像：

```java
String getGroupName(String groupWxid);
String getChatroomName(String chatroomId);
String getGroupRemarkName(String groupWxid);
String getGroupMemberName(String groupWxid, String memberWxid);
String getGroupNickName(String groupWxid, String memberWxid);
String getFriendNickName(String friendWxid);
String getFriendRemarkName(String friendWxid);
String getFriendDisplayName(String friendWxid, String roomId);
String getFriendName(String friendWxid);
String getFriendName(String friendWxid, String roomId);
int getFriendGender(String friendWxid);
String getFriendProvince(String friendWxid);
String getFriendCity(String friendWxid);
String getFriendRegion(String friendWxid);
int getGroupMemberGender(String groupWxid, String memberWxid);
String getGroupMemberProvince(String groupWxid, String memberWxid);
String getGroupMemberCity(String groupWxid, String memberWxid);
String getGroupMemberRegion(String groupWxid, String memberWxid);
String getAvatarUrl(String username);
String getAvatarUrl(String username, boolean isBigHeadImg);
```

批量搜索优先用 `*Info()` 返回的 `Map` 列表。好友常用键为 `wxid/nickname/remarkName/displayName/customWxId/gender/province/city/region/avatarUrl`；群聊常用键为 `roomId/name/remarkName/displayName/owner/memberCount/memberList`；成员常用键为 `wxid/displayName/groupNickName/rawGroupNickName/nickname/remarkName/customWxId`；标签常用键为 `labelId/labelName/userNameList`。

对象列表的元素方法：

```text
FriendInfo: getWxid getName getNickname getRemark getRemarkName
GroupInfo: getRoomId getName getNickname getRemark getRemarkName getDisplayName
           getMemberList getMemberCount
ContactLabelBean: getLabelId getId getLabelName getName getUserNameList
                  getUsernameList getContactList
```

## 发送消息和收藏

```java
void sendText(String talker, String content);
void sendText(String talker, String content, Consumer callback);
void sendQuoteMsg(String talker, long msgId, String content);
void sendQuoteMsg(String talker, String content, long msgId);
void revokeMsg(long msgId);
void uploadDeviceStep(long step);
void sendPat(String talker, String pattedUser);
void sendShareCard(String talker, String wxid);
boolean sendImage(String talker, String sendPath);
boolean sendImage(String talker, String sendPath, String appId);
boolean sendOriginalImage(String talker, String sendPath);
boolean sendVoice(String talker, String sendPath);
boolean sendVoice(String talker, String sendPath, int durationSeconds);
boolean sendVideo(String talker, String sendPath);
boolean sendEmoji(String talker, String pathOrMd5);
boolean sendFile(String talker, String sendPath);
boolean sendFile(String talker, String sendPath, String title);
Object getFavoriteList(int limit);
Object getFavorite(long localId);
boolean sendFavorite(String talker, long localId);
boolean sendFavorite(String talker, String localId);
void sendXmlMsg(String talker, String content);
void sendLocation(String talker, String poiName, String label, String x, String y, String scale);
void sendLocation(String talker, JSONObject jsonObj);
```

分支的媒体发送接口返回是否成功提交；`sendOriginalImage` 使用原图/不压缩标志。`sendVoice` 三参时长单位是秒。群文本支持 `[AtWx=wxid]` 和 `[AtWx=notify@all]`。`revokeMsg` 优先传本地 `msgId`，也兼容 `msgSvrId`。收藏 `limit` 会限制在 `1..200`。

媒体卡片接口：

```java
void sendMediaMsg(String talker, Object mediaMessage, String appId);
void shareFile(String talker, String title, String filePath, String appId);
void shareMiniProgram(String talker, String title, String description, String userName, String path, byte[] thumbData, String appId);
void sendAppBrandMsg(String talker, String title, String pagePath, String ghName);
void shareMusic(String talker, String title, String description, String musicUrl, String musicDataUrl, byte[] thumbData, String appId);
void shareMusicVideo(String talker, String title, String description, String musicUrl, String musicDataUrl, String singerName, int duration, String songLyric, byte[] thumbData, String appId);
void shareText(String talker, String text, String appId);
void shareVideo(String talker, String title, String description, String videoUrl, byte[] thumbData, String appId);
void shareWebpage(String talker, String title, String description, String webpageUrl, byte[] thumbData, String appId);
```

## 朋友圈、系统消息、历史消息和未读消息

```java
Object getSnsPostList();
Object getSnsPostList(int limit);
Object getSnsPostList(String userName, int limit);
Object getSnsPost(String snsId);
boolean prepareSnsPostMedia(String snsId, Consumer callback);
boolean publishSnsPost(Object prepared);
boolean refreshSnsTimeline();
void uploadText(String content);
void uploadText(String content, String sdkId, String sdkAppName);
void uploadText(JSONObject jsonObj);
void uploadTextAndPicList(String content, String picPath);
void uploadTextAndPicList(String content, String picPath, String sdkId, String sdkAppName);
void uploadTextAndPicList(String content, List picPathList);
void uploadTextAndPicList(String content, List picPathList, String sdkId, String sdkAppName);
void uploadTextAndPicList(JSONObject jsonObj);
void uploadLivePhoto(String livePhotoPath);
void uploadLivePhoto(String imagePath, String videoPath);
void uploadLivePhoto(JSONObject jsonObj);
void uploadLivePhotoList(List livePhotoList);
void uploadLivePhotoList(JSONObject jsonObj);
void uploadTextAndLivePhoto(String content, String livePhotoPath);
void uploadTextAndLivePhoto(String content, String livePhotoPath, String sdkId, String sdkAppName);
void uploadTextAndLivePhoto(String content, String imagePath, String videoPath);
void uploadTextAndLivePhoto(String content, String imagePath, String videoPath, String sdkId, String sdkAppName);
void uploadTextAndLivePhoto(JSONObject jsonObj);
void uploadTextAndLivePhotoList(String content, List livePhotoList);
void uploadTextAndLivePhotoList(String content, List livePhotoList, String sdkId, String sdkAppName);
void uploadTextAndLivePhotoList(JSONObject jsonObj);
void uploadVideo(String videoPath);
void uploadVideo(JSONObject jsonObj);
void uploadTextAndVideo(String content, String videoPath);
void uploadTextAndVideo(String content, String videoPath, String sdkId, String sdkAppName);
void uploadTextAndVideo(JSONObject jsonObj);
long insertSystemMsg(String talker, String content, long createTime);
List<MsgInfoBean> queryHistoryMsg(String talker, long startTime, int count);
int getUnreadCount(String talker);
int getAllUnreadCount();
boolean clearUnread(String talker);
boolean clearAllUnread();
```

`queryHistoryMsg(...)` 的 `startTime` 使用毫秒时间戳；大于 `0` 时查询该时间之后的消息，传 `0L` 时返回最近消息。返回列表项与 `onHandleMsg(...)` 的 `MsgInfoBean` 用法一致。`getUnreadCount(...)` 返回指定会话的普通未读消息数，`getAllUnreadCount()` 返回所有会话的普通未读消息总数。`clearUnread(...)` 通过微信原生入口清空指定会话的普通未读、免打扰未读和 @ 计数；`clearAllUnread()` 对所有未读会话执行同样操作并清理微信通知。清空接口会刷新会话列表。

朋友圈读取只覆盖当前账号本机已经缓存的内容。`getSnsPostList()` 默认取最近 50 条，显式 `limit` 最大 200；按发布者查询传 wxid。返回记录提供 `getSnsId/getUserName/getDisplayName/getCreateTimeSeconds/getStorageType/getContentType/getType/getText/getMediaList` 和 `isText/isImage/isVideo/isLivePhoto/isCard/isSelf`，媒体提供 `getId/getType/getUrl/getThumbUrl/isLivePhoto/getLiveVideo`。网页链接、音乐和已确认扩展协议类型的 `getType()` 返回 `card`，未识别类型返回 `unknown`，需要原始值时读取 `getContentType()`。不要尝试读取微信 `SnsInfo` 或混淆 Protobuf 字段。

原样转发时调用 `prepareSnsPostMedia(snsId, Consumer)`，在回调里先检查结果的 `isSuccess/getMessage`，成功后直接把结果传给 `publishSnsPost(prepared)`。准备结果还提供 `getContent/getType/getImagePathList/getVideoPath/getVideoThumbPath/getLivePhotoList`；实况项提供 `getImagePath/getVideoPath/getVideoDurationMillis/getCoverTimeMillis`。媒体准备会读取微信本地原图，缺失时触发微信下载并等待落地，因此必须保持异步。多张实况会逐项保留动态效果，只有配套视频失败的项按静态封面发布；卡片和未知类型仍不降级转发。

朋友圈文件必须是微信可读的本地路径。图文和视频不能混在同一条朋友圈。单张实况优先传包含内嵌视频的 `livePhotoPath`；多张使用 `uploadLivePhotoList` / `uploadTextAndLivePhotoList`，列表项可以是单文件路径或包含 `livePhotoPath`、`imagePath`、`videoPath`、`coverTimeMs` 的 `JSONObject`，最多 9 项。JSON 外层列表字段使用 `livePhotoList`，兼容 `livePhotoPathList`。旧的单张 `imagePath` 加 `videoPath` / `liveVideoPath` 双路径调用继续兼容。多项中的无效实况只降级对应静态封面；当前微信完全没有实况上传入口时，多项接口全部按静态封面发布，单张旧接口仍返回失败。全局朋友圈函数保持 `void` 签名，`wa` 对象上的同名方法返回 `Boolean`。

## HTTP 和媒体下载

```java
void get(String url, Map headers, Consumer callback);
void get(String url, Map headers, long timeoutSeconds, Consumer callback);
void get(String url, Map headers, PluginCallBack.HttpCallback callback);
void get(String url, Map headers, long timeoutSeconds, PluginCallBack.HttpCallback callback);
void post(String url, Map params, Map headers, Consumer callback);
void post(String url, Map params, Map headers, long timeoutSeconds, Consumer callback);
void post(String url, Map params, Map headers, PluginCallBack.HttpCallback callback);
void post(String url, Map params, Map headers, long timeoutSeconds, PluginCallBack.HttpCallback callback);
void download(String url, String path, Map headers, Consumer callback);
void download(String url, String path, Map headers, long timeoutSeconds, Consumer callback);
void download(String url, String path, Map headers, PluginCallBack.DownloadCallback callback);
void download(String url, String path, Map headers, long timeoutSeconds, PluginCallBack.DownloadCallback callback);
void downloadImage(String url, Consumer callback);
void downloadImage(String url, String fileName, Consumer callback);
void downloadImages(List urlList, Consumer callback);
void downloadImages(List urlList, String prefix, Consumer callback);
void downloadImg(String md5, String cdnUrl, String aesKey, String savePath);
void downloadImg(String md5, String cdnUrl, String aesKey, String savePath, PluginCallBack.DownloadCallback callback);
void downloadImg(Object imageMsg, String savePath);
void downloadImg(Object imageMsg, String savePath, PluginCallBack.DownloadCallback callback);
void downloadVideo(String md5, String cdnUrl, String aesKey, String savePath, PluginCallBack.DownloadCallback callback);
void downloadVideo(Object videoMessage, String savePath, PluginCallBack.DownloadCallback callback);
void downloadFinderMedia(Object finderFeedOrMessage, String savePath, PluginCallBack.DownloadCallback callback);
void downloadFinderMedia(Object finderFeedOrMessage, int mediaIndex, String savePath, PluginCallBack.DownloadCallback callback);
```

这些媒体下载方法是 `alt-entry` 已公开的全局 API，不能回答为不存在。`get/post` 的 Consumer 收到响应文本；`download/downloadImage` 收到 `File`；`downloadImages` 收到 `List<File>`。`downloadImage(s)` 异步保存到 `Hchat/Image`。无回调的 `downloadImg` 支持普通 URL 和微信 CDN fileid，会等待完整文件落盘；带四个媒体参数和 `DownloadCallback` 的重载也会在后台复用同一下载链路并在完成或失败时回调；图片对象重载优先高清地址。`downloadVideo` 始终异步，优先传 `onHandleMsg` 收到的整条视频消息，先复用本地完整 MP4，缺失时从 `imgPath` 查询原生 `VideoInfo` 后下载；不要假设视频正文一定有 XML。`downloadFinderMedia` 是分支专属接口，接受原生 Finder 对象、聊天消息对象或视频号分享 XML 字符串；聊天分享 XML 通常只有媒体密链，模块会按 `objectId` 和 `objectNonceId` 调用微信原生详情请求补齐解密信息，再下载并校验 MP4。默认下载索引 `0`，多图按从 `0` 开始的索引分别调用。视频号 `savePath` 为空时保存到 `Hchat/Finder`，目录应已存在或以 `/` 结尾。成功走 `onSuccess(File)`，失败走 `onError(Exception)`，且只回调一次；回调线程不固定。聊天视频 `savePath` 为空时保存到 `Hchat/Video`。`timeout` 单位秒。

`PluginCallBack.HttpCallback` 实现 `onSuccess(int statusCode, String response)` 和 `onError(Exception error)`；`PluginCallBack.DownloadCallback` 实现 `onSuccess(File file)`、`onError(Exception error)`，`onProgress` 仅作兼容保留。

## 菜单扩展接口

```java
Object registerPlusMenu(String title, Consumer callback);
Object registerPlusMenu(String title, boolean front, Consumer callback);
Object registerPlusMenu(String title, String iconPath, Consumer callback);
Object registerPlusMenu(String title, String iconPath, boolean front, Consumer callback);
Object registerMessageMenu(String title, Consumer callback);
Object registerMessageMenu(String title, boolean front, Consumer callback);
Object registerMessageMenu(String title, String iconPath, Consumer callback);
Object registerMessageMenu(String title, String iconPath, boolean front, Consumer callback);
void removeMenu(Object handle);
```

这些菜单方法是 Hchat 已公开的全局 API，不能回答为不存在。只能在微信主进程注册微信 UI 菜单，小程序进程不要调用。`title` 去除首尾空白后不能为空；`iconPath` 支持绝对路径或相对插件目录的图片路径，为空或读取失败时不设置自定义图标。`front=true` 把插件条目放在微信原生条目前面，不传时默认为 `false`；多个前置条目按注册顺序排列。

`registerPlusMenu` 的回调参数是当前 `Activity`，解析不到时可能为 `null`。模块会消费插件条目的原点击、去重并先关闭加号菜单，再执行回调。`registerMessageMenu` 的回调参数是当前长按消息对应的真实 `ScriptMessageBean`；模块从本次长按行和 Hook 参数解析原生消息并按本地消息 ID 查询，不按列表位置猜测。点击后会消费微信原处理、清理本次消息绑定并执行回调，插件无需自行收尾。

注册成功返回独立句柄，保存后可用 `removeMenu(handle)` 主动移除。插件关闭、重载或加载失败清理时，模块会自动移除该插件的全部菜单。完整示例以 `docs/SCRIPT_PLUGIN_API.md` 为准。

视频号媒体使用 `downloadFinderMedia(...)`，普通聊天视频仍使用 `downloadVideo(...)`。视频号接口可直接接收原生 Finder 对象、聊天消息对象或分享 XML；调用对象不满足接口约定时应处理 `onError(Exception)`。

## 脚本加载和重载

```java
void reloadPlugin();
String compileSnapshot(String path);
Object evalSnapshot(String path);
Object evalSnapshot(InputStream inputStream);
Object evalSnapshot(byte[] data);
void eval(String code);
void loadJava(String path);
ClassLoader loadDex(String path);
void loadSo(String path);
void loadSo(String path, ClassLoader loader);
```

相对路径从当前插件目录解析。`compileSnapshot` 生成 `.bshs`；`evalSnapshot` 只执行加密快照。`loadJava`、`eval`、`evalSnapshot` 加载的标准回调会被重新识别，非标准方法名用 `useCallback` 绑定。`loadDex` 会把 dex/jar/apk 安全复制到微信私有代码缓存后加载。`loadSo` 只接受与微信进程 ABI 匹配的 ELF SO，会复制到私有代码缓存并设为只读。绝对不要在脚本顶层声明 `native void method();`，顶层函数不是 JNI 类成员。JNI 方法必须声明在 BeanShell 类中，并用 `loadSo(path, NativeClass.class.getClassLoader())` 加载，类全名和方法名必须与 SO 的 JNI 符号或 `RegisterNatives` 注册目标一致；也可以先 `loadDex` 加载编译好的 JNI 包装类，再把其返回的加载器传给 `loadSo`。替换 SO 后重新加载插件，并把新 JNI 类的 `ClassLoader` 传给双参数 `loadSo`，即可加载新版本；单参数 `loadSo` 使用固定宿主 `ClassLoader`，不能热更新。旧版本无法热卸载，会驻留到微信进程结束。加载 Native 代码属于高风险操作，只使用可信文件。

## 音频转换

```java
int getFileType(String filePath);
int mp3ToSilk(String mp3Path, String silkPath);
int mp3ToSilk(String mp3Path, String silkPath, int hz);
int wavToSilk(String wavPath, String silkPath, int hz);
int flacToSilk(String flacPath, String silkPath, int hz);
int oggToSilk(String oggPath, String silkPath, int hz);
int pcmToSilk(String pcmPath, String silkPath, int hz, int pcmHz, int channels);
int autoToSilk(String inputPath, String silkPath, int hz);
int silkToMp3(String silkPath, String mp3Path);
int silkToMp3(String silkPath, String mp3Path, int hz);
int silkToPcm(String silkPath, String pcmPath, int hz);
int mp3ToPcm(String inputPath, String pcmPath);
int wavToPcm(String inputPath, String pcmPath);
int flacToPcm(String inputPath, String pcmPath);
int oggToPcm(String inputPath, String pcmPath);
int autoToPcm(String inputPath, String pcmPath);
Map getAudioInfo(String filePath);
int decodeAacFile(String aacPath, String pcmPath);
int encodePcmToAac(String pcmPath, String aacPath, int sampleRate, int channels);
int encodePcmToM4a(String pcmPath, String m4aPath, int sampleRate, int channels);
int mp4ToSilk(String mp4Path, String silkPath, int hz);
int silkToM4a(String silkPath, String m4aPath, int hz);
int mp4ToM4a(String mp4Path, String m4aPath, int hz);
int mp4ToAac(String mp4Path, String aacPath, int hz);
int m4aToSilk(String m4aPath, String silkPath, int hz);
int aacToSilk(String aacPath, String silkPath, int hz);
int m4aToAac(String m4aPath, String aacPath, int hz);
int m4aToM4a(String m4aPath, String outputPath, int hz);
int autoToAac(String inputPath, String aacPath, int hz);
int autoToM4a(String inputPath, String m4aPath, int hz);
int autoAacToSilk(String inputPath, String silkPath, int hz);
int silkToAac(String silkPath, String aacPath, int hz);
int aacToPcm(String aacPath, String pcmPath);
int pcmToAac(String pcmPath, String aacPath, int sampleRate, int channels);
int pcmToM4a(String pcmPath, String m4aPath, int sampleRate, int channels);
int m4aToPcm(String m4aPath, String pcmPath);
int decodeM4aFile(String m4aPath, String pcmPath);
long getDuration(String filePath);
long getDurationLimited(String filePath);
String getErrorMessage(int code);
void startTransform(int type, String inputPath, String outputPath, int sampleRate, Consumer callback);
```

音频转换通常返回 `0` 成功、负数失败。Silk 采样率使用 `8000/12000/16000/24000`，其它值按 `24000`。Ogg Vorbis 和 Ogg Opus 都必须优先调用这里的全局方法或 `audio/audioBridge`；`SilkCodecClass` 和自行创建的 `SilkCodec` 是上游原始类，其 OGG 方法只支持 Vorbis。`getDuration` 返回毫秒，`getDurationLimited` 最多返回 `60000`。`getAudioInfo` 至少包含 `sampleRate` 和 `channelCount`。

## Hook、DexKit 和反射

```java
Class findClass(String className);
List findClassList(Object usingStrings);
List findMemberList(Object usingStrings);
Object hookBefore(Member member, Consumer callback);
Object hookAfter(Member member, Consumer callback);
Object hookReplace(Member member, Function callback);
void unhook(Object handle);
```

`findClass` 只适合已知且跨版本稳定的完整类名；类名已混淆或会随版本变化时，不得把单版本类名写进 `findClass`，应使用稳定字符串调用 `findClassList` / `findMemberList`。`findClassList` 返回 `Class`，`findMemberList` 返回 `Method` / `Constructor`。`usingStrings` 支持字符串、`List`、`String[]`、`Object[]` 和 BeanShell 大括号数组；多个字符串先按同时包含查询，只有方法和类的联合查询都没有结果时才会逐个字符串扩展。

`findMemberList` 的返回顺序有语义：先放入字符串直接命中的方法/构造器，再追加类命中后展开的全部声明方法和构造器。因此同一类中出现多个签名相似的展开成员，不等于 DexKit 直接命中了多个目标。先用内置逆向工具确认字符串查询在目标版本中直接命中唯一 descriptor；插件代码再按声明类、参数、返回类型和修饰符从前往后取第一个合格成员，不要对包含类展开成员的整个列表强求全局唯一。如果直接查询本身命中多个 descriptor，必须增强字符串锚点或结构签名，不得猜测第一项。

Hook 回调参数运行时是 `XC_MethodHook.MethodHookParam`，常用 `method`、`thisObject`、`args`、`getResult/setResult`、`getThrowable/setThrowable`、`hasThrowable`。`hookReplace` 的 `Function.apply` 返回替代结果。插件关闭或加载失败时 Hook 自动清理，也可手动 `unhook`。

```java
Method firstMethod(Object instance, String methodName);
Method firstMethod(Object instance, String methodName, int paramCount);
Constructor firstConstructor(Object instance, int paramCount);
Field firstField(Object instance, String fieldName);
Object invokeMethod(Object instance, String methodName);
Object invokeMethod(Object instance, String methodName, Object[] params);
Object invokeMethod(Object instance, String methodName, int paramCount);
Object invokeMethod(Object instance, String methodName, int paramCount, Object[] params);
Object createInstance(Object instance, int paramCount);
Object createInstance(Object instance, int paramCount, Object[] params);
Object getField(Object instance, String fieldName);
void setField(Object instance, String fieldName, Object value);
```

这组插件全局方法是模块 `KavaReflector` 的稳定薄封装，插件优先调用它们，不需要直接操作 `KavaReflector`。`instance` 可传对象或 `SomeClass.class`；传 Class 时处理静态成员或创建实例。`paramCount` 用于区分重载，`params` 使用 `new Object[]{...}`。`firstMethod` 和 `firstField` 会递归父类，失败一般返回 `null`。不带 `params` 的 `invokeMethod(..., paramCount)` 和 `createInstance(..., paramCount)` 只应用于 `paramCount=0`；有参数时必须使用带 `Object[] params` 的重载。

Hook 回调在 BeanShell 中使用 `Consumer.accept(Object param)` / `Function.apply(Object param)` 最稳妥，再通过 `param.args`、`param.thisObject`、`param.getResult()` 等访问真实 `MethodHookParam`。`findMemberList` 可能因类展开返回多个候选；必须先验证直接命中的 descriptor，再核对成员的声明类、参数、返回类型和修饰符，既不能直接 Hook 未验证的第一项，也不能因后续类展开成员重复而误判为目标不唯一。

## Agent 插件工作区工具

这些是 Agent 管理插件文件的工具，不是插件全局函数。插件目录的读取、搜索、新建、修改、移动和删除都通过 `hchat.workspace.*` 完成，并在聊天中保留工具名称、参数、状态和结果：

- `list_files` / `read_file` / `search_files`：检查目录、按行号分段读取文本，以及用正则、路径 glob 和前后文搜索内容。
- `runtime_diagnostics`：传准确 `plugin_id`，读取已安装插件在当前进程的加载状态及其 `log.txt`；不创建暂存区、不执行或启用插件。默认返回最近 120 行，最多 500 行且每页不超过 64 KiB；使用 `nextBeforeOffset` 作为下一次 `before_offset` 向前续读。日志缺失、读取失败、未加载和配置关闭分别返回明确状态。日志只覆盖插件主动输出和加载失败，不含只写到模块 HLog 的普通回调异常；旧日志不能证明当前暂存代码已经运行。
- `create_directory` / `write_file`：创建目录或文本文件；已有文件的局部修改优先使用 `apply_patch`。
- `apply_patch`：使用以 `*** Begin Patch` / `*** End Patch` 包裹的 Codex 风格统一补丁；支持 `*** Add File`、`*** Update File`、`*** Delete File`、`*** Move to` 和 `@@` 区块，一次可处理多个文件。补丁上下文不包含读取结果中的行号。
- `move_path` / `delete_path`：移动、重命名或删除插件内路径。
- `restore_path`：把路径恢复到本轮开始时的状态，新建路径会被移除。
- `reset_workspace`：丢弃本轮全部暂存修改，恢复到任务开始状态。
- `delete_plugin`：只在用户明确要求时标记删除整个现有插件，提交前一定要求用户确认。
- `workspace_status`：汇总新增、修改、删除、文件检查及真正的 BeanShell 语法检查；必须在最后一次写操作后调用。根 `main.java` 和所有附属 `.java/.bsh` 使用与运行时同序的预处理器和 Parser，只解析、不执行。错误返回文件路径、可确认的位置和解析片段；预处理改变行号时明确标注。`canApply=true` 只表示允许提交，`runtimeVerified=false` 表示未验证实际运行行为。
- `show_diff`：返回标准统一 diff；完成前必须使用 `path="."` 查看当前 revision 的完整差异，结果过长时再按子路径分段查看。

所有工具只操作当前单个插件的暂存副本。新插件、删除路径、删除整个插件或静态检查识别出的高风险代码必须由用户确认；其它现有插件修改通过目录事务提交。提交后插件保持禁用。Agent 不得用最终回复里的整段源码绕过工作区工具。

## Agent Skills

用户可在 Agent 配置页或聊天快捷设置的 `Skill 管理` 新建、编辑、启停和导入技能；聊天 `$技能名` 指定技能，也可按描述选择。技能不是普通已安装脚本插件，不应对它调用插件工作区写入、加载或启用 API。

- `hchat.skills.list`：列出安装的技能，包含准确 ID、名称、描述、启用状态和文档错误；`offset` 默认 0，`limit` 默认 20、最大 50，按 `nextOffset` 翻页。
- `hchat.skills.read`：`skill_id` 必填，`path` 默认 `SKILL.md`，可读取技能目录内的 UTF-8 文本；`offset` 默认 0，`max_chars` 默认 12000、最大 24000，按 `nextOffset` 续读。停用技能不能读取。
- 先名称描述、后按需正文和引用文件。指南可约束当前任务步骤，但不能覆盖用户要求或授予额外权限。`scripts` 只提供文本参考，不会自动执行，不存在通用终端、Python 或任意系统命令执行工具。
- 两工具均可使用原生函数协议，兼容接口用 `status=local_tool`、完整 `localToolName` 和 JSON `localToolArguments`。这些工具不是 BeanShell 可调用的方法。

## Agent 内置逆向工具

这些是 Agent 分析当前或用户指定微信 APK 的工具，不是插件全局函数：

- `hchat.reverse.open_target_session`：不传参数时返回当前运行微信；传 `input` 可打开用户明确提供的微信 APK 绝对路径，传已注册的 `session_id` 可重新切换目标。外部目标返回的 `session_id` 必须用于后续每次查询。
- `hchat.reverse.list_target_sessions` / `hchat.reverse.get_target_session` / `hchat.reverse.close_target_session`：列出、读取或关闭已注册目标；关闭只释放索引并取消注册，不删除 APK 文件。目标路径会持久化，微信重启后仍可列出。
- `hchat.reverse.compare_methods_using_strings`：传至少两个 `session_ids` 和 `contains_all_strings` 或 `contains_any_strings`，用同一锚点横向返回多个微信版本的方法候选。
- `hchat.reverse.find_classes_using_strings` / `hchat.reverse.find_methods_using_strings`：只检索 DEX 字符串常量；使用 `contains_all_strings`、`contains_any_strings`，至少提供一个；支持 `brief`、`fields`、`offset`、`limit`，可按需取得 `sourcePath` 和 `sourceEntry`。界面文本通常来自资源表，不要用这两个工具查 UI 文案。
- `hchat.reverse.find_resource_values`：按 `type` 和 `value` 查询 `resources.arsc`；支持 `string`、`integer`、`bool`、`color`，以及 `contains`、`ignore_case`、`brief`、`fields`、`offset`、`limit`。
- `hchat.reverse.get_resource_value`：使用 `resource_id`，或同时使用 `type` 和 `name`，读取资源的全部配置值。
- `hchat.reverse.find_methods_using_resource`：使用 `resource_id` 查找直接使用该资源常量的方法；支持 `brief`、`fields`、`offset`、`limit`。
- `hchat.reverse.list_res`：列出资源表，可用 `type` 过滤并通过 `brief`、`fields`、`offset`、`limit` 控制返回；详细字段可取得 `filePath`、`sourceEntry` 和 `resolution`。
- `hchat.reverse.decode_xml`：使用 APK 内 `path` 解码 `AndroidManifest.xml` 或 `res/**/*.xml` 二进制 XML；结果截断时传 `nextOffset` 继续读取。
- `hchat.reverse.find_methods`：完整 `descriptor` 可精确查找；也可组合 `class_name_contains`、`method_name_contains`、`descriptor_contains`；支持 `brief`、`fields`、`offset`、`limit`。
- `hchat.reverse.inspect_method`：完整 `descriptor`；`include` 可传 `strings`、`using-fields`、`invokes`、`callers`、`annotations`、`opcodes`，`brief=true` 时只返回各类证据数量。
- `hchat.reverse.inspect_class`：使用 `descriptor` 或 `class_name`；`include` 可选 `fields`、`methods`、`annotations`，`brief` 返回数量摘要，`limit` 控制字段和方法数量。
- `hchat.reverse.export_method_java`：完整方法 `descriptor`、`offset`、`max_chars`，用于理解方法语义。
- `hchat.reverse.export_class_java`：类 `descriptor` 或 `class_name`、`offset`、`max_chars`，用于理解整类语义。
- `hchat.reverse.export_method_smali`：完整方法 `descriptor`、`offset`、`max_chars`。
- `hchat.reverse.export_class_smali`：类 `descriptor` 或 `class_name`、`offset`、`max_chars`。
- `hchat.reverse.read_tool_result`：使用工具结果返回的 `handle`、`offset`、`max_chars` 继续读取被分页保存的长结果。
- `hchat.reverse.manifest`：默认返回结构化 Manifest 摘要；`include` 可按需展开 SDK、应用、权限、特性、Activity/Alias、Service、Receiver、Provider。

`localToolName` 必须填写上面的完整名称。省略 `session_id` 时查询当前运行微信；查询外部 APK 时每次都必须携带对应 `session_id`，不能把一个版本取得的 descriptor 用到另一个版本。首次 find/list 优先传 `brief=true`，只有下一步确实需要时才传最少的 `fields`。查询 `offset` 默认 `0`，`limit` 默认 `30`、最大 `100`，分页结果包含 `hasMore`。界面文案先查资源值，再用资源 ID 查找实际使用方法；代码日志、异常文本和协议常量才使用 DEX 字符串检索。先用 Java 导出理解语义，需要核对精确指令时再读 Smali。Java/Smali/XML `max_chars` 默认 `24000`，Agent 单次范围 `1000..48000`。导出或 XML 结果出现 `truncated=true` 时，用原工具和 `nextOffset` 继续读取；其它长结果返回 `handle` 时使用 `hchat.reverse.read_tool_result`，不能把片段当作完整实现。

## 规则

- 新代码使用 BeanShell/Java 风格，不写 Kotlin。
- 普通能力优先使用本指南的 WA 风格函数；只有用户明确要求 Hook 时才使用 Hook 和 DexKit。
- 不自行创建新的 `DexKitBridge`，只复用模块共享对象。
- 不在主线程执行网络、长时间文件操作或循环任务。
- 不硬编码插件目录和缓存目录。
