package h.Hchat.dexkit;

import android.os.Bundle;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.util.Pair;
import h.Hchat.hooks.api.runtime.WeChatVersionApi;
import h.Hchat.preferences.HchatStorage;
import h.Hchat.utils.KavaReflector;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.MethodsMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;

/**
 * DexFinder - 使用 DexKit 定位微信混淆后的类和方法
 * 适配微信 8.0.49 ~ 8.0.72+
 */
public class DexFinder {

    private static final String TAG = "[Hchat:DexFinder]";
    private static final String CACHE_PREFS = "Hchat_dex_cache";
    private static final String CACHE_COMPLETE = "cache.complete";
    private static final String CACHE_KEY = "cache.key";
    private static final String CACHE_SNAPSHOT_VERSION_KEY = "cache.snapshot.version";
    private static final String CACHE_SNAPSHOT_VERSION = "2";
    private static final String LOCATOR_WISH_COMPLETE = "locator.wish.complete";
    private static final String LOCATOR_PACKET_COMPLETE = "locator.packet.complete";
    private static final String LOCATOR_ECS_COMPLETE = "locator.ecs.complete";
    private static final String LOCATOR_MSG_DESC_COMPLETE = "locator.msg.desc.complete";
    private static final String LOCATOR_PROTOBUF_COMPLETE = "locator.protobuf.complete";
    private static final String RED_PACKET_UNION_LOCATOR_VERSION_KEY = "redpacket.union.locator.version";
    private static final String RED_PACKET_UNION_LOCATOR_VERSION = "1";
    private static final String NETWORK_QUEUE_LOCATOR_VERSION_KEY = "network.queue.locator.version";
    private static final String NETWORK_QUEUE_LOCATOR_VERSION = "2";
    private static final boolean VERBOSE = false;
    private final DexKitBridge dexKit;
    private final ClassLoader classLoader;
    private final SharedPreferences cachePrefs;
    private final String runtimeCacheKey;
    private boolean resolvedAll;
    private boolean cacheLoadAttempted;
    private boolean cacheDescriptorInvalid;

    // AddMsg 处理类
    public List<Class<?>> addMsgClasses = new ArrayList<>();
    // 静默收红包
    public Class<?> receiveLuckyMoneyClass;
    public Class<?> receiveLuckyMoneyUnionClass;
    public Constructor<?> receiveCtor;
    public Constructor<?> unionReceiveCtor;
    // 静默拆红包
    public Class<?> openLuckyMoneyClass;
    public Class<?> openLuckyMoneyUnionClass;
    public Constructor<?> openCtor10;
    public Constructor<?> openCtor9;
    public Constructor<?> openCtor8;
    public Constructor<?> unionOpenCtor10;
    public Constructor<?> unionOpenCtor9;
    // 网络请求队列
    public Class<?> netQueueClass;
    public List<Class<?>> netQueueCandidateClasses = new ArrayList<>();
    public List<Class<?>> packetBaseClasses = new ArrayList<>();
    public List<Class<?>> packetQueueClasses = new ArrayList<>();
    public List<Class<?>> fakePacketClasses = new ArrayList<>();
    private Class<?> protobufBaseClass;
    public Class<?> protobufRawReqClass;
    public Class<?> protobufGenericRespClass;
    public Class<?> protobufConfigBuilderClass;
    public Class<?> protobufReqRespClass;
    public Class<?> protobufCallbackClass;
    public Class<?> protobufNewSendMsgReqClass;
    public Class<?> protobufOplogReqClass;
    public Class<?> protobufOnGYNetEndClass;
    public Class<?> protobufNetSceneBaseClass;
    public Method protobufStaticDispatchMethod;
    public List<Method> protobufSceneEndMethods = new ArrayList<>();
    // 祝福语
    public Class<?> wishWxHbClass;
    public Constructor<?> wishWxHbCtor;
    // 文本消息发送
    public Class<?> sendTextMsgClass;
    public Constructor<?> sendTextMsgCtorLong;
    public Constructor<?> sendTextMsgCtorObject;
    // 微信服务容器
    public Method serviceGetterMethod;
    // ECS 礼物静默处理
    public Class<?> ecsGiftTaskClass;
    public Class<?> ecsGiftServiceClass;
    public Class<?> ecsGiftMsgClass;
    public List<Method> getContactAddMethods = new ArrayList<>();
    public List<Method> getContactServiceGetters = new ArrayList<>();
    // 图片发送高层入口
    public Method sendImageMethod;
    public Class<?> sendImageAsyncParamsClass;
    public Class<?> sendImageCrossParamsClass;
    public Class<?> sendImageAppInfoClass;
    public Method sendImageAsyncSubmitMethod;
    public Class<?> imageCdnTaskClass;
    public Method imageCdnSubmitMethod;
    public Method imageCdnServiceGetterMethod;
    public Class<?> marsCdnManagerClass;
    public Class<?> marsCdnDownloadRequestClass;
    public Class<?> marsCdnDownloadCallbackClass;
    public Method marsCdnStartDownloadMethod;
    public Method imageBestPathMethod;
    public Method imageStorageGetterMethod;
    public Method imageTokenPathMethod;
    // 文件发送高层入口
    public Method sendFileMethod;
    public Method sendFileAttachDirMethod;
    public Method sendFileAttachPathMethod;
    // 微信消息描述文本方法（签名 (Context, boolean)→String；微信对该类方法内部对 null 调 isEmpty() 会崩，用于防崩兜底）
    // 跨版本通用：不依赖混淆类名/方法名，按签名定位（各版本这类方法极少，8.0.76 实测仅 3 个）
    public List<Method> msgDescTextMethods = new java.util.ArrayList<>();
    // XML/AppMsg 原始发送入口
    public Method sendXmlAppMsgMethod;
    public Method appMsgParseMethod;
    public Class<?> groupSolitairePluginClass;
    public Method groupSolitaireSendMethod;
    // 本地消息插入
    public Class<?> localMessageClass;
    public Constructor<?> localMessageCtor;
    public Method localSystemMessageMethod;
    public Method localMessageInsertMethod;
    public Method localMessageCreateTimeMethod;
    // 视频发送高层入口
    public Method sendVideoMethod;
    public Class<?> sendVideoTaskClass;
    public Method videoPathMethod;
    public Method videoPathOwnerGetterMethod;
    private Class<?> videoInfoClass;
    public Method videoInfoByFileNameMethod;
    // 转账领取/退回
    public Class<?> transferOperationClass;
    // 转账详情查询
    public Class<?> transferQueryClass;
    public Method transferQueryResponseMethod;
    // 通过好友申请
    public Class<?> verifyUserClass;
    // 发送联系人名片
    public Method contactCardXmlMethod;
    // 拍一拍系统消息模板解析
    public Method patDisplayTemplateMethod;
    public Class<?> patExtensionClass;
    public Method patCreatePairMethod;
    public Method patSuffixMethod;
    public Method patCanSendMethod;
    public Class<?> sendPatSceneClass;
    public Constructor<?> sendPatSceneCtor;
    // 语音消息发送
    public Method voiceStartRecordMethod;
    public Method voiceFullPathMethod;
    public Method voiceMessagePathMethod;
    private boolean voiceMessagePathLookupComplete;
    public Method voiceFinishRecordMethod;
    public Method voiceInfoQueryMethod;
    public Class<?> voiceUploadClass;
    public Constructor<?> voiceUploadCtor;
    public Constructor<?> voiceUploadCdnCtor;
    // 微信原生语音播放
    public Method voicePlaybackStartMethod;
    public Method voicePlaybackPauseMethod;
    public Method voicePlaybackResumeMethod;
    public Method voicePlaybackStopMethod;
    // 表情消息发送
    public Method emojiSendMethod;
    public Method emojiManagerSendMethod;
    public Method emojiGetByMd5Method;
    public Method emojiCreateInfoMethod;
    public Method emojiUpdateInfoMethod;
    public Method emojiAccPathMethod;
    public Method emojiCheckGifMethod;
    public Method emojiFilePathMethod;
    public Method emojiDecodeDataMethod;
    public Method emojiDecodeManagerGetterMethod;
    // 收藏消息
    public Class<?> favoriteItemClass;
    public Method favoriteItemConvertFromCursorMethod;
    public Class<?> favoriteServiceClass;
    public Method favoriteServiceResolverMethod;
    public Method favoriteStorageGetterMethod;
    public Method favoriteListMethod;
    public Method favoriteListNextMethod;
    public Method favoriteListCursorMethod;
    public Method favoriteGetMethod;
    public Method favoriteSendMethod;
    // 微信数据库/联系人 API
    public Class<?> mmKernelClass;
    public Class<?> coreStorageClass;
    public Method coreStorageGetter;
    public Class<?> configStorageClass;
    public Class<?> sqliteDbWrapperClass;
    public Method conversationDeleteMethod;
    public Method messageClearByTalkerMethod;
    public Method messageClearBatchMethod;
    // 微信原生消息免打扰
    public Method contactMuteStateMethod;
    public Method contactMuteEnableMethod;
    public Method contactMuteDisableMethod;
    public Method contactStorageGetterMethod;
    public Method contactStorageQueryMethod;
    public Method chatroomMuteServiceGetterMethod;
    public Method chatroomMuteBuildMethod;
    public Method chatroomMuteSubmitMethod;
    public Method groupMemberDisplayNameMethod;
    public Class<?> addChatroomMemberClass;
    public Constructor<?> addChatroomMemberCtor;
    public Class<?> inviteChatroomMemberClass;
    public Constructor<?> inviteChatroomMemberCtor;
    public Class<?> delChatroomMemberClass;
    public Constructor<?> delChatroomMemberCtor;
    public Class<?> revokeMsgClass;
    public Constructor<?> revokeMsgCtor;
    public Class<?> uploadDeviceStepClass;
    public Constructor<?> uploadDeviceStepCtor;
    public Class<?> addContactLabelClass;
    public Constructor<?> addContactLabelCtorString;
    public Constructor<?> addContactLabelCtorList;
    public Class<?> modifyContactLabelListClass;
    public Constructor<?> modifyContactLabelListCtor;
    public Class<?> snsUploadPackHelperClass;
    public Class<?> snsUploadManagerClass;
    public Method snsUploadManagerGetterMethod;
    public Method snsSetContentMethod;
    public Method snsSetSdkIdMethod;
    public Method snsSetSdkAppNameMethod;
    public Method snsAddImageMethod;
    public Method snsAddVideoMethod;
    public Method snsCommitMethod;
    public Method snsUploadCheckMethod;
    public Method snsShareAppMsgMethod;
    // 聊天页 API
    public Method chatPageStartMethod;
    public Method chatPageFragmentEnterMethod;
    public Method chatPageFragmentExitMethod;
    // 脚本发送按钮 hook
    public Method chatFooterSendClickMethod;

    private boolean receiveUnionLocatorResolved;
    private boolean openUnionLocatorResolved;
    private boolean networkQueueLocatorResolved;
    private boolean wishLocatorResolved;
    private boolean packetCompatLocatorResolved;
    private boolean ecsGiftLocatorResolved;
    private boolean msgDescLocatorResolved;
    private boolean protobufLocatorResolved;

    public DexFinder(DexKitBridge dexKit, ClassLoader classLoader) {
        this(dexKit, classLoader, null);
    }

    public DexFinder(DexKitBridge dexKit, ClassLoader classLoader, Context hostContext) {
        this.dexKit = dexKit;
        this.classLoader = classLoader;
        this.cachePrefs = hostContext != null
                ? HchatStorage.preferences(hostContext, CACHE_PREFS)
                : null;
        this.runtimeCacheKey = buildRuntimeCacheKey(hostContext, classLoader);
    }

    public synchronized void resolveAll() {
        if (resolvedAll) {
            logDetail("resolveAll 已完成，跳过重复解析");
            return;
        }
        receiveUnionLocatorResolved = false;
        openUnionLocatorResolved = false;
        networkQueueLocatorResolved = false;
        if (loadCache()) {
            resolvedAll = true;
            logDetail("命中缓存: " + shortKey(runtimeCacheKey));
            return;
        }
        cacheDescriptorInvalid = false;
        resolveAddMsgClasses();
        resolveReceiveLuckyMoney();
        resolveOpenLuckyMoney();
        resolveNetworkQueue();
        resolveServiceManagerApi();
        resolveSendTextMsg();
        resolveGetContactServiceApi();
        resolveSendImageApi();
        resolveImageCdnDownloadApi();
        resolveSendFileApi();
        resolveSendXmlApi();
        resolveGroupSolitaireApi();
        resolveLocalMessageApi();
        resolveSendVideoTaskApi();
        resolveVideoPathApi();
        resolveVideoInfoApi();
        resolveSendVoiceApi();
        resolveSendEmojiApi();
        resolveFavoriteApi();
        resolveTransferOperationApi();
        resolveTransferQueryApi();
        resolveVerifyUserApi();
        resolveContactCardApi();
        resolvePatMessageApi();
        resolveDatabaseApi();
        resolveConversationDeleteApi();
        resolveMessageClearApi();
        resolveConversationMuteApi();
        resolveGroupMemberDisplayName();
        resolveAddChatroomMemberApi();
        resolveInviteChatroomMemberApi();
        resolveDelChatroomMemberApi();
        resolveRevokeMsgApi();
        resolveUploadDeviceStepApi();
        resolveContactLabelNetworkApi();
        resolveSnsUploadApi();
        resolveChatPageApi();
        resolveScriptSendHookApi();
        resolveWishWxHb();
        resolvePacketCompatClasses();
        resolveProtobufPacketApi();
        resolveEcsGiftApi();
        logMissingCritical();
        saveCache();
        resolvedAll = true;
        logDetail("解析完成并缓存: " + shortKey(runtimeCacheKey));
    }

    public synchronized boolean isResolvedAll() {
        return resolvedAll;
    }

    // ============ Helper: create FindMethod/FindClass ============
    private FindMethod mkMethodUsingStrings(String... strings) {
        FindMethod fm = new FindMethod();
        MethodMatcher mm = new MethodMatcher();
        mm.usingStrings(Arrays.asList(strings));
        fm.matcher(mm);
        return fm;
    }

    private FindMethod mkMethodUsingStringsAndName(String methodName, String... strings) {
        FindMethod fm = new FindMethod();
        MethodMatcher mm = new MethodMatcher();
        mm.name(methodName);
        mm.usingStrings(Arrays.asList(strings));
        fm.matcher(mm);
        return fm;
    }

    private FindClass mkClassUsingStrings(String... strings) {
        FindClass fc = new FindClass();
        ClassMatcher cm = new ClassMatcher();
        cm.usingStrings(Arrays.asList(strings));
        fc.matcher(cm);
        return fc;
    }

    private FindClass mkClassByName(String name) {
        FindClass fc = new FindClass();
        ClassMatcher cm = new ClassMatcher();
        cm.className(name);
        fc.matcher(cm);
        return fc;
    }

    private void collectMethodOwnerClass(List<MethodData> methods, List<Class<?>> out) {
        for (MethodData m : methods) {
            try {
                Class<?> cl = KavaReflector.loadClass(m.getClassName(), classLoader);
                if (!out.contains(cl)) out.add(cl);
            } catch (Throwable ignored) {}
        }
    }

    // ============ AddMsg ============
    private void resolveAddMsgClasses() {
        try {
            // 策略1: 搜索 "dkAddMsg" (OR 搜索，不同时要求两个字符串)
            List<MethodData> methods1 = dexKit.findMethod(
                    mkMethodUsingStrings("dkAddMsg"));
            collectMethodOwnerClass(methods1, addMsgClasses);

            // 策略2: 搜索 "processAddMsg"
            if (addMsgClasses.isEmpty()) {
                List<MethodData> methods2 = dexKit.findMethod(
                        mkMethodUsingStrings("processAddMsg"));
                collectMethodOwnerClass(methods2, addMsgClasses);
            }

            logDetail("AddMsg类: " + addMsgClasses.size());
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveAddMsg 失败: " + e.getMessage(), e);
        }
    }

    // ============ 收红包 ============
    private void resolveReceiveLuckyMoney() {
        try {
            // 策略1: 用真实的 CGI URL 字符串定位
            List<ClassData> classes = dexKit.findClass(
                    mkClassUsingStrings("cgi-bin/mmpay-bin/receivewxhb"));
            for (ClassData cd : classes) {
                try {
                    Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                    Constructor<?> ctor = findFirstCtorByArgCounts(cl, 7, 10, 8);
                    if (ctor != null) {
                        receiveLuckyMoneyClass = cl;
                        receiveCtor = ctor;
                        break;
                    }
                } catch (Throwable ignored) {}
            }

            // 策略2: 回退到旧锚点
            if (receiveLuckyMoneyClass == null) {
                List<ClassData> classes2 = dexKit.findClass(
                        mkClassUsingStrings("receivehongbao"));
                for (ClassData cd : classes2) {
                    try {
                        Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                        Constructor<?> ctor = findCtorByArgCount(cl, 7);
                        if (ctor != null) {
                            receiveLuckyMoneyClass = cl;
                            receiveCtor = ctor;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }

            resolveReceiveLuckyMoneyUnion();

            logDetail("收红包类: " +
                    (receiveLuckyMoneyClass != null ? receiveLuckyMoneyClass.getName() : "null")
                    + " Union=" + (receiveLuckyMoneyUnionClass != null
                    ? receiveLuckyMoneyUnionClass.getName() : "null"));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveReceive 失败: " + e.getMessage(), e);
        }
    }

    private boolean resolveReceiveLuckyMoneyUnion() {
        receiveLuckyMoneyUnionClass = null;
        unionReceiveCtor = null;
        receiveUnionLocatorResolved = false;
        try {
            List<ClassData> classes = dexKit.findClass(mkClassUsingStrings(
                    "MicroMsg.NetSceneReceiveLuckyMoneyUnion", "union_source"));
            for (ClassData cd : classes) {
                try {
                    Class<?> clazz = KavaReflector.loadClass(cd.getName(), classLoader);
                    Constructor<?> ctor = findCtorByArgCount(clazz, 6);
                    if (ctor == null) continue;
                    receiveLuckyMoneyUnionClass = clazz;
                    unionReceiveCtor = ctor;
                    break;
                } catch (Throwable ignored) {}
            }
            receiveUnionLocatorResolved = receiveLuckyMoneyUnionClass != null
                    && unionReceiveCtor != null;
            logDetail("Union收红包类: " + (receiveLuckyMoneyUnionClass != null
                    ? receiveLuckyMoneyUnionClass.getName() : "null")
                    + " ctor6=" + (unionReceiveCtor != null));
            return receiveUnionLocatorResolved;
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveReceiveUnion 失败: " + e.getMessage(), e);
            return false;
        }
    }

    // ============ 拆红包 ============
    private void resolveOpenLuckyMoney() {
        try {
            // 策略1: 用真实的 CGI URL 字符串定位
            List<ClassData> classes = dexKit.findClass(
                    mkClassUsingStrings("cgi-bin/mmpay-bin/openwxhb"));
            for (ClassData cd : classes) {
                try {
                    Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                    Constructor<?> c10 = findCtorByArgCount(cl, 10);
                    Constructor<?> c9 = findCtorByArgCount(cl, 9);
                    Constructor<?> c8 = findCtorByArgCount(cl, 8);
                    if (c10 != null || c9 != null || c8 != null) {
                        openLuckyMoneyClass = cl;
                        openCtor10 = c10;
                        openCtor9 = c9;
                        openCtor8 = c8;
                        break;
                    }
                } catch (Throwable ignored) {}
            }

            // 策略2: 回退到旧锚点
            if (openLuckyMoneyClass == null) {
                List<ClassData> classes2 = dexKit.findClass(
                        mkClassUsingStrings("open lucky"));
                for (ClassData cd : classes2) {
                    try {
                        Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                        Constructor<?> c10 = findCtorByArgCount(cl, 10);
                        Constructor<?> c9 = findCtorByArgCount(cl, 9);
                        Constructor<?> c8 = findCtorByArgCount(cl, 8);
                        if (c10 != null || c9 != null || c8 != null) {
                            openLuckyMoneyClass = cl;
                            openCtor10 = c10;
                            openCtor9 = c9;
                            openCtor8 = c8;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }

            resolveOpenLuckyMoneyUnion();

            logDetail("拆红包类: " +
                    (openLuckyMoneyClass != null ? openLuckyMoneyClass.getName() : "null")
                    + " Union=" + (openLuckyMoneyUnionClass != null
                    ? openLuckyMoneyUnionClass.getName() : "null"));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveOpen 失败: " + e.getMessage(), e);
        }
    }

    private boolean resolveOpenLuckyMoneyUnion() {
        openLuckyMoneyUnionClass = null;
        unionOpenCtor10 = null;
        unionOpenCtor9 = null;
        openUnionLocatorResolved = false;
        try {
            List<ClassData> classes = dexKit.findClass(mkClassUsingStrings(
                    "MicroMsg.NetSceneOpenLuckyMoneyUnion", "union_source"));
            for (ClassData cd : classes) {
                try {
                    Class<?> clazz = KavaReflector.loadClass(cd.getName(), classLoader);
                    Constructor<?> ctor10 = findCtorByArgCount(clazz, 10);
                    Constructor<?> ctor9 = findCtorByArgCount(clazz, 9);
                    if (ctor10 == null && ctor9 == null) continue;
                    openLuckyMoneyUnionClass = clazz;
                    unionOpenCtor10 = ctor10;
                    unionOpenCtor9 = ctor9;
                    break;
                } catch (Throwable ignored) {}
            }
            openUnionLocatorResolved = openLuckyMoneyUnionClass != null
                    && (unionOpenCtor10 != null || unionOpenCtor9 != null);
            logDetail("Union拆红包类: " + (openLuckyMoneyUnionClass != null
                    ? openLuckyMoneyUnionClass.getName() : "null")
                    + " ctor10=" + (unionOpenCtor10 != null)
                    + " ctor9=" + (unionOpenCtor9 != null));
            return openUnionLocatorResolved;
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveOpenUnion 失败: " + e.getMessage(), e);
            return false;
        }
    }

    // ============ 网络队列 ============
    private void resolveNetworkQueue() {
        netQueueClass = null;
        netQueueCandidateClasses.clear();
        networkQueueLocatorResolved = false;
        try {
            collectKnownNetworkQueueClasses();

            // 先按真实红包请求类型筛选，避免 doSceneImp 的普通业务类抢到首位。
            collectNetworkQueueClassesByAnchors();
            for (Class<?> candidate : netQueueCandidateClasses) {
                if (hasQueueSendMethodForRequests(candidate)) {
                    netQueueClass = candidate;
                    break;
                }
            }

            // 策略1: 搜索 doSceneImp 字符串，找到包含 dispatch 方法的类
            List<MethodData> methods = dexKit.findMethod(
                    mkMethodUsingStrings("doSceneImp"));
            if (netQueueClass == null) {
                for (MethodData m : methods) {
                    try {
                        Class<?> cl = KavaReflector.loadClass(m.getClassName(), classLoader);
                        addNetQueueCandidate(cl);
                        if (hasQueueSendMethodForRequests(cl)) {
                            netQueueClass = cl;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }

            // 策略2: 如果没找到直接发包方法，继续收集候选，后面再统一筛选。
            if (netQueueClass == null) {
                for (MethodData m : methods) {
                    try {
                        Class<?> cl = KavaReflector.loadClass(m.getClassName(), classLoader);
                        addNetQueueCandidate(cl);
                    } catch (Throwable ignored) {}
                }
            }

            if (netQueueClass == null || !hasQueueSendMethodForRequests(netQueueClass)) {
                Class<?> sendClass = findFirstLikelyQueueClass();
                if (sendClass != null) netQueueClass = sendClass;
            }

            networkQueueLocatorResolved = netQueueClass != null
                    && hasQueueSendMethodForRequests(netQueueClass);

            logDetail("网络队列类: " +
                    (netQueueClass != null ? netQueueClass.getName() : "null")
                    + " candidates=" + netQueueCandidateClasses.size());
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveQueue 失败: " + e.getMessage(), e);
        }
    }

    // ============ 祝福语 ============
    private void resolveWishWxHb() {
        if (wishLocatorResolved) return;
        if (wishWxHbClass != null) {
            wishLocatorResolved = true;
            return;
        }
        try {
            String[][] anchors = {
                    {"/cgi-bin/mmpay-bin/wishwxhb"},
                    {"wishwxhb"},
                    {"NetSceneWishWxHb"}
            };
            for (String[] anchor : anchors) {
                List<ClassData> classes = dexKit.findClass(mkClassUsingStrings(anchor));
                for (ClassData cd : classes) {
                    try {
                        Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                        Constructor<?> ctor = findCtorByArgCount(cl, 4);
                        if (ctor != null) {
                            wishWxHbClass = cl;
                            wishWxHbCtor = ctor;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
                if (wishWxHbClass != null) break;
            }
            logDetail("祝福语类: " +
                    (wishWxHbClass != null ? wishWxHbClass.getName() : "null"));
            wishLocatorResolved = true;
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveWish 失败: " + e.getMessage(), e);
        }
    }

    // ============ 文本消息发送 ============
    public void resolveServiceManagerApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (isServiceGetterMethod(serviceGetterMethod)) return;
            List<MethodData> methods = dexKit.findMethod(
                    mkMethodUsingStrings("calling getService(...)"));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isServiceGetterMethod(method)) continue;
                    KavaReflector.accessible(method);
                    serviceGetterMethod = method;
                    break;
                } catch (Throwable ignored) {}
            }
            logDetail("服务容器方法: " + methodName(serviceGetterMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveServiceManagerApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveSendTextMsg() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (sendTextMsgClass != null
                    && (sendTextMsgCtorLong != null || sendTextMsgCtorObject != null)) {
                return;
            }

            List<Class<?>> candidates = new ArrayList<>();
            collectSendTextClassCandidates(candidates, 20,
                    new String[][]{
                            {"/cgi-bin/micromsg-bin/newsendmsg"},
                            {"MicroMsg.NetSceneSendMsg"},
                            {"NetSceneSendMsg"},
                            {"newsendmsg"}
                    });
            collectSendTextMethodOwnerCandidates(candidates, 20,
                    new String[][]{
                            {"/cgi-bin/micromsg-bin/newsendmsg"},
                            {"MicroMsg.NetSceneSendMsg"},
                            {"NetSceneSendMsg"}
                    });

            for (Class<?> cl : candidates) {
                Constructor<?> longCtor = findCtorByExactTypes(
                        cl, String.class, String.class, int.class, int.class, long.class);
                Constructor<?> objectCtor = findCtorByExactTypes(
                        cl, String.class, String.class, int.class, int.class, Object.class);
                if (longCtor != null || objectCtor != null) {
                    sendTextMsgClass = cl;
                    sendTextMsgCtorLong = longCtor;
                    sendTextMsgCtorObject = objectCtor;
                    break;
                }
            }

            logDetail("文本发送类: "
                    + (sendTextMsgClass != null ? sendTextMsgClass.getName() : "null")
                    + " longCtor=" + (sendTextMsgCtorLong != null)
                    + " objectCtor=" + (sendTextMsgCtorObject != null));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveSendText 失败: " + e.getMessage(), e);
        }
    }

    public void resolveGetContactServiceApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (getContactAddMethods.isEmpty()) {
                collectGetContactAddMethods("dkverify add Contact");
                collectGetContactAddMethods("[addContact] has consume");
            }
            if (getContactServiceGetters.isEmpty()) {
                collectGetContactServiceGetters();
            }
            logDetail("联系人资料服务方法: " + getContactAddMethods.size());
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveGetContactServiceApi 失败: " + e.getMessage(), e);
        }
    }

    private void collectGetContactAddMethods(String anchor) {
        try {
            List<MethodData> methods = dexKit.findMethod(mkMethodUsingStrings(anchor));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isGetContactAddMethod(method)) continue;
                    KavaReflector.accessible(method);
                    if (!getContactAddMethods.contains(method)) {
                        getContactAddMethods.add(method);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private void collectGetContactServiceGetters() {
        for (Method addMethod : getContactAddMethods) {
            if (addMethod == null) continue;
            collectServiceGettersForType(addMethod.getDeclaringClass());
            for (Class<?> itf : addMethod.getDeclaringClass().getInterfaces()) {
                collectServiceGettersForType(itf);
            }
        }
    }

    private void collectServiceGettersForType(Class<?> serviceType) {
        if (serviceType == null || serviceType == Object.class) return;
        try {
            FindMethod fm = new FindMethod();
            MethodMatcher mm = new MethodMatcher();
            mm.paramCount(0);
            mm.returnType(serviceType);
            fm.matcher(mm);
            List<MethodData> methods = dexKit.findMethod(fm);
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isGetContactServiceGetter(method, serviceType)) continue;
                    KavaReflector.accessible(method);
                    if (!getContactServiceGetters.contains(method)) {
                        getContactServiceGetters.add(method);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    // ============ 图片消息发送 ============
    public void resolveSendImageApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (isSendImageAppInfoMethod(sendImageMethod)) {
                resolveSendImageAsyncAppInfoApi();
                resolveImageBestPathApi();
                return;
            }

            List<MethodData> methods = dexKit.findMethod(
                    mkMethodUsingStrings("sendImg: args error"));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isSendImageAppInfoMethod(method)) continue;
                    KavaReflector.accessible(method);
                    sendImageMethod = method;
                    break;
                } catch (Throwable ignored) {}
            }
            if (sendImageMethod == null) {
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isSendImageMethod(method)) continue;
                        KavaReflector.accessible(method);
                        sendImageMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            logDetail("图片发送方法: " + methodName(sendImageMethod));
            resolveSendImageAsyncAppInfoApi();
            resolveImageBestPathApi();
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveSendImageApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveImageCdnDownloadApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            resolveMarsCdnDownloadApi();
            if (isImageCdnTaskClass(imageCdnTaskClass)
                    && isPreferredImageCdnSubmitMethod(imageCdnSubmitMethod)
                    && isImageCdnServiceGetterMethod(imageCdnServiceGetterMethod)) {
                return;
            }
            imageCdnSubmitMethod = null;
            imageCdnServiceGetterMethod = null;

            if (!isImageCdnTaskClass(imageCdnTaskClass)) {
                List<ClassData> classes = dexKit.findClass(mkClassUsingStrings(
                        "field_fullpath",
                        "field_fileId",
                        "field_aesKey",
                        "field_fileType"));
                for (ClassData classData : classes) {
                    try {
                        Class<?> clazz = classData.getInstance(classLoader);
                        if (!isImageCdnTaskClass(clazz)) continue;
                        imageCdnTaskClass = clazz;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            if (!isImageCdnTaskClass(imageCdnTaskClass)) {
                List<MethodData> methods = dexKit.findMethod(mkMethodUsingStrings(
                        "field_fullpath",
                        "field_fileId",
                        "field_fileType"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        Class<?> ret = method.getReturnType();
                        if (isImageCdnTaskClass(ret)) {
                            imageCdnTaskClass = ret;
                            break;
                        }
                        for (Class<?> param : method.getParameterTypes()) {
                            if (!isImageCdnTaskClass(param)) continue;
                            imageCdnTaskClass = param;
                            break;
                        }
                        if (imageCdnTaskClass != null) break;
                    } catch (Throwable ignored) {}
                }
            }

            if (imageCdnTaskClass != null) {
                FindMethod fm = new FindMethod();
                MethodMatcher mm = new MethodMatcher();
                mm.paramCount(2);
                fm.matcher(mm);
                List<MethodData> methods = dexKit.findMethod(fm);
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isPreferredImageCdnSubmitMethod(method)) continue;
                        KavaReflector.accessible(method);
                        imageCdnSubmitMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            if (imageCdnTaskClass != null && imageCdnSubmitMethod == null) {
                FindMethod fm = new FindMethod();
                MethodMatcher mm = new MethodMatcher();
                mm.paramCount(1);
                fm.matcher(mm);
                List<MethodData> methods = dexKit.findMethod(fm);
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isImageCdnSubmitMethod(method)) continue;
                        KavaReflector.accessible(method);
                        imageCdnSubmitMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            if (imageCdnSubmitMethod == null) {
                List<MethodData> methods = dexKit.findMethod(mkMethodUsingStrings(
                        "field_fileId",
                        "field_aesKey",
                        "field_fullpath"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isImageCdnSubmitMethod(method)) continue;
                        KavaReflector.accessible(method);
                        imageCdnSubmitMethod = method;
                        imageCdnTaskClass = method.getParameterTypes()[0];
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            if (imageCdnSubmitMethod != null) {
                Class<?> owner = imageCdnSubmitMethod.getDeclaringClass();
                FindMethod fm = new FindMethod();
                MethodMatcher mm = new MethodMatcher();
                mm.paramCount(0);
                fm.matcher(mm);
                List<MethodData> methods = dexKit.findMethod(fm);
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!KavaReflector.isStatic(method)) continue;
                        if (method.getReturnType() != owner) continue;
                        KavaReflector.accessible(method);
                        imageCdnServiceGetterMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            logDetail("图片CDN下载API: task=" + className(imageCdnTaskClass)
                    + " submit=" + methodName(imageCdnSubmitMethod)
                    + " getter=" + methodName(imageCdnServiceGetterMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveImageCdnDownloadApi 失败: " + e.getMessage(), e);
        }
    }

    private void resolveMarsCdnDownloadApi() {
        try {
            if (isMarsCdnReady()) return;
            try {
                marsCdnManagerClass = classLoader.loadClass("com.tencent.mars.cdn.CdnManager");
            } catch (Throwable ignored) {}
            try {
                marsCdnDownloadRequestClass = classLoader.loadClass("com.tencent.mars.cdn.CdnManager$C2CDownloadRequest");
            } catch (Throwable ignored) {}
            if (marsCdnDownloadRequestClass == null) {
                try {
                    marsCdnDownloadRequestClass = classLoader.loadClass("com.tencent.mars.cdn.CdnLogic$C2CDownloadRequest");
                } catch (Throwable ignored) {}
            }
            if (marsCdnDownloadRequestClass == null) {
                List<ClassData> classes = dexKit.findClass(mkClassUsingStrings("must set marscdnBizType,apptype"));
                for (ClassData classData : classes) {
                    try {
                        Class<?> clazz = classData.getInstance(classLoader);
                        if (isMarsCdnRequestClass(clazz)) {
                            marsCdnDownloadRequestClass = clazz;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            if (marsCdnDownloadCallbackClass == null) {
                try {
                    marsCdnDownloadCallbackClass = classLoader.loadClass("com.tencent.mars.cdn.CdnManager$DownloadCallback");
                } catch (Throwable ignored) {}
            }
            if (marsCdnDownloadCallbackClass == null) {
                try {
                    marsCdnDownloadCallbackClass = classLoader.loadClass("com.tencent.mars.cdn.CdnLogic$DownloadCallback");
                } catch (Throwable ignored) {}
            }
            if (marsCdnDownloadCallbackClass == null) {
                List<ClassData> classes = dexKit.findClass(mkClassUsingStrings("aeskey must be 32 bytes"));
                for (ClassData classData : classes) {
                    try {
                        Class<?> clazz = classData.getInstance(classLoader);
                        if (isMarsCdnCallbackClass(clazz)) {
                            marsCdnDownloadCallbackClass = clazz;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            if (marsCdnManagerClass != null && marsCdnDownloadRequestClass != null && marsCdnDownloadCallbackClass != null) {
                marsCdnStartDownloadMethod = findMarsStartDownloadMethod(
                        marsCdnManagerClass,
                        marsCdnDownloadRequestClass,
                        marsCdnDownloadCallbackClass);
            }
            if (marsCdnManagerClass != null && marsCdnStartDownloadMethod == null) {
                for (Method method : marsCdnManagerClass.getDeclaredMethods()) {
                    if (!"startC2CDownload".equals(method.getName())) continue;
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length != 2) continue;
                    KavaReflector.accessible(method);
                    marsCdnStartDownloadMethod = method;
                    marsCdnDownloadRequestClass = params[0];
                    marsCdnDownloadCallbackClass = params[1];
                    break;
                }
            }
            logDetail("Mars CDN下载API: manager=" + className(marsCdnManagerClass)
                    + " request=" + className(marsCdnDownloadRequestClass)
                    + " callback=" + className(marsCdnDownloadCallbackClass)
                    + " start=" + methodName(marsCdnStartDownloadMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveMarsCdnDownloadApi 失败: " + e.getMessage(), e);
        }
    }

    private boolean isMarsCdnRequestClass(Class<?> clazz) {
        if (clazz == null) return false;
        if (clazz.getName().contains("C2CDownloadRequest")) return true;
        return KavaReflector.findMethod(clazz, "setFileid", String.class) != null
                && KavaReflector.findMethod(clazz, "setAeskey", String.class) != null
                && KavaReflector.findMethod(clazz, "setSavePath2", String.class) != null
                && KavaReflector.findMethod(clazz, "setFileType", int.class) != null;
    }

    private boolean isMarsCdnCallbackClass(Class<?> clazz) {
        if (clazz == null) return false;
        String name = clazz.getName();
        return name.contains("DownloadCallback") || name.contains("CdnCallback");
    }

    private boolean sameOrAssignable(Class<?> a, Class<?> b) {
        return a == b || a.isAssignableFrom(b) || b.isAssignableFrom(a);
    }

    private Method findMarsStartDownloadMethod(Class<?> managerClass, Class<?> requestClass, Class<?> callbackClass) {
        if (managerClass == null || requestClass == null || callbackClass == null) return null;
        for (Method method : managerClass.getDeclaredMethods()) {
            if (!"startC2CDownload".equals(method.getName())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2) continue;
            if (!sameOrAssignable(params[0], requestClass)) continue;
            if (!sameOrAssignable(params[1], callbackClass)) continue;
            KavaReflector.accessible(method);
            return method;
        }
        return null;
    }

    private void resolveImageBestPathApi() {
        try {
            if (!isImageBestPathMethod(imageBestPathMethod)) {
                imageBestPathMethod = null;
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("[getBigPicPath] msg is null."));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isImageBestPathMethod(method)) continue;
                        KavaReflector.accessible(method);
                        imageBestPathMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }
            Class<?> storageClass = imageBestPathMethod != null
                    ? imageBestPathMethod.getDeclaringClass() : null;
            if (!isImageStorageGetter(imageStorageGetterMethod, storageClass)) {
                imageStorageGetterMethod = findImageStorageGetter(storageClass);
            }
            if (!isImageTokenPathMethod(imageTokenPathMethod)) {
                imageTokenPathMethod = null;
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings(
                                "THUMBNAIL://",
                                "THUMBNAIL_DIRPATH://",
                                "read img buf failed: "));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isImageTokenPathMethod(method)) continue;
                        imageTokenPathMethod = KavaReflector.accessible(method);
                        break;
                    } catch (Throwable ignored) {}
                }
            }
            logDetail("图片原图路径API: path=" + methodName(imageBestPathMethod)
                    + " storage=" + methodName(imageStorageGetterMethod)
                    + " token=" + methodName(imageTokenPathMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveImageBestPathApi 失败: " + e.getMessage(), e);
        }
    }

    private Method findImageStorageGetter(Class<?> storageClass) {
        if (storageClass == null) return null;
        try {
            FindMethod fm = new FindMethod();
            MethodMatcher mm = new MethodMatcher();
            mm.paramCount(0);
            mm.returnType(storageClass.getName());
            fm.matcher(mm);
            for (MethodData methodData : dexKit.findMethod(fm)) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isImageStorageGetter(method, storageClass)) continue;
                    return KavaReflector.accessible(method);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void resolveSendImageAsyncAppInfoApi() {
        try {
            if (isSendImageAsyncAppInfoApiReady()) return;
            resolveSendImageAsyncParamClasses();
            if (sendImageAppInfoClass == null) {
                sendImageAppInfoClass = findSendImageAppInfoClass();
            }
            if (!isSendImageAsyncSubmitMethod(sendImageAsyncSubmitMethod)) {
                sendImageAsyncSubmitMethod = findSendImageAsyncSubmitMethod();
            }
            logDetail("图片新版appid链路: params=" + className(sendImageAsyncParamsClass)
                    + " cross=" + className(sendImageCrossParamsClass)
                    + " appinfo=" + className(sendImageAppInfoClass)
                    + " submit=" + methodName(sendImageAsyncSubmitMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveSendImageAsyncAppInfoApi 失败: " + e.getMessage(), e);
        }
    }

    private boolean isSendImageAsyncAppInfoApiReady() {
        return sendImageAsyncParamsClass != null
                && sendImageCrossParamsClass != null
                && sendImageAppInfoClass != null
                && isSendImageAsyncSubmitMethod(sendImageAsyncSubmitMethod);
    }

    private void resolveSendImageAsyncParamClasses() {
        if (sendImageAsyncParamsClass != null && sendImageCrossParamsClass != null) return;
        try {
            List<ClassData> classes = dexKit.findClass(
                    mkClassUsingStrings("msg_raw_img_send", "crossParams", "imgPath", "fromUsername", "toUsername"));
            for (ClassData classData : classes) {
                try {
                    Class<?> clazz = KavaReflector.loadClass(classData.getName(), classLoader);
                    Constructor<?> ctor = findSendImageAsyncParamsCtor(clazz);
                    if (ctor == null) continue;
                    sendImageAsyncParamsClass = clazz;
                    sendImageCrossParamsClass = ctor.getParameterTypes()[4];
                    break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private Class<?> findSendImageAppInfoClass() {
        Class<?> fromCrossParams = findSendImageAppInfoClassFromCrossParams();
        if (fromCrossParams != null) return fromCrossParams;
        try {
            List<ClassData> classes = dexKit.findClass(
                    mkClassUsingStrings("appid", "mediatagname", "messageext", "messageaction", "appinfo"));
            for (ClassData classData : classes) {
                try {
                    Class<?> clazz = KavaReflector.loadClass(classData.getName(), classLoader);
                    if (isSendImageAppInfoClass(clazz)) return clazz;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Method findSendImageAsyncSubmitMethod() {
        if (sendImageAsyncParamsClass == null) return null;
        try {
            FindMethod fm = new FindMethod();
            MethodMatcher mm = new MethodMatcher();
            mm.paramTypes(sendImageAsyncParamsClass.getName());
            fm.matcher(mm);
            List<MethodData> methods = dexKit.findMethod(fm);
            Method fallback = null;
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isSendImageAsyncSubmitMethod(method)) continue;
                    KavaReflector.accessible(method);
                    if (isKotlinFlowReturn(method)) return method;
                    if (fallback == null) fallback = method;
                } catch (Throwable ignored) {}
            }
            return fallback;
        } catch (Throwable ignored) {}
        return null;
    }

    public void resolveSendFileApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (isSendFileAppMsgMethod(sendFileMethod)) {
                if (sendFileAttachDirMethod == null || sendFileAttachPathMethod == null) {
                    resolveSendFileAttachHelpers(sendFileMethod.getDeclaringClass());
                }
                return;
            }

            List<Class<?>> candidates = new ArrayList<>();
            collectAppMsgLogicCandidates(candidates, 20,
                    new String[][]{
                            {"summerbig content url:"},
                            {"MicroMsg.AppMsgLogic"},
                            {"/cgi-bin/micromsg-bin/uploadappattach"}
                    });

            for (Class<?> clazz : candidates) {
                Method method = findSendFileAppMsgMethod(clazz);
                if (method == null) continue;
                KavaReflector.accessible(method);
                sendFileMethod = method;
                resolveSendFileAttachHelpers(clazz);
                break;
            }
            if (sendFileMethod != null && (sendFileAttachDirMethod == null || sendFileAttachPathMethod == null)) {
                resolveSendFileAttachHelpers(sendFileMethod.getDeclaringClass());
            }

            logDetail("文件发送方法: " + methodName(sendFileMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveSendFileApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveSendXmlApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (isSendXmlAppMsgMethod(sendXmlAppMsgMethod)) {
                resolveAppMsgParseMethod(sendXmlAppMsgMethod.getParameterTypes()[0]);
                return;
            }

            List<MethodData> methods = dexKit.findMethod(
                    mkMethodUsingStrings("summerbig sendAppMsg attachFilePath"));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isSendXmlAppMsgMethod(method)) continue;
                    KavaReflector.accessible(method);
                    sendXmlAppMsgMethod = method;
                    resolveAppMsgParseMethod(method.getParameterTypes()[0]);
                    break;
                } catch (Throwable ignored) {}
            }

            logDetail("XML发送方法: " + methodName(sendXmlAppMsgMethod)
                    + " parse=" + methodName(appMsgParseMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveSendXmlApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveMsgDescTextApi() {
        if (ensureSnapshotLoaded() && msgDescLocatorResolved) return;
        try {
            if (msgDescLocatorResolved) return;
            if (!msgDescTextMethods.isEmpty()) {
                msgDescLocatorResolved = true;
                saveCache();
                return;
            }
            // 跨版本通用：微信消息描述文本方法名稳定为 "l"（各版本 xN0.q/r.l），用 "string" 字符串缩小范围后按签名过滤
            List<MethodData> methods = dexKit.findMethod(mkMethodUsingStringsAndName("l", "string"));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isMsgDescTextMethod(method)) continue;
                    KavaReflector.accessible(method);
                    msgDescTextMethods.add(method);
                } catch (Throwable ignored) {}
            }
            if (!msgDescTextMethods.isEmpty()) {
                logDetail("消息描述文本兜底方法: " + msgDescTextMethods.size() + " 个");
            }
            msgDescLocatorResolved = true;
            saveCache();
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveMsgDescTextApi 失败: " + e.getMessage(), e);
        }
    }

    private boolean isMsgDescTextMethod(Method method) {
        if (method == null) return false;
        if (method.getReturnType() != String.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 2 && params[0] == android.content.Context.class && params[1] == boolean.class;
    }

    private void resolveAppMsgParseMethod(Class<?> appMsgClass) {
        if (appMsgClass == null) return;
        if (isAppMsgParseMethod(appMsgParseMethod, appMsgClass)) return;
        try {
            for (Method method : KavaReflector.declaredMethods(appMsgClass)) {
                if (!isAppMsgParseMethod(method, appMsgClass)) continue;
                KavaReflector.accessible(method);
                appMsgParseMethod = method;
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    private void resolveSendFileAttachHelpers(Class<?> clazz) {
        if (clazz == null) return;
        try {
            if (sendFileAttachDirMethod == null) {
                for (Method method : KavaReflector.declaredMethods(clazz)) {
                    if (!isStaticNoArgStringMethod(method)) continue;
                    KavaReflector.accessible(method);
                    if (looksLikeAttachDir(method)) {
                        sendFileAttachDirMethod = method;
                        break;
                    }
                }
            }
            if (sendFileAttachPathMethod == null) {
                for (Method method : KavaReflector.declaredMethods(clazz)) {
                    if (!isSendFileAttachPathMethod(method)) continue;
                    KavaReflector.accessible(method);
                    sendFileAttachPathMethod = method;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean looksLikeAttachDir(Method method) {
        try {
            Object value = KavaReflector.invoke(method, null);
            if (!(value instanceof String)) return false;
            String path = ((String) value).toLowerCase();
            return path.contains("attachment") || path.contains("appattach") || path.contains("app_attach");
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void resolveSendVideoApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (sendVideoMethod != null) return;

            List<MethodData> methods = dexKit.findMethod(
                    mkMethodUsingStrings("send vedio args error"));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isSendVideoMethod(method)) continue;
                    KavaReflector.accessible(method);
                    sendVideoMethod = method;
                    break;
                } catch (Throwable ignored) {}
            }

            logDetail("视频发送方法: " + methodName(sendVideoMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveSendVideoApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveSendVideoTaskApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (sendVideoTaskClass != null) return;
            sendVideoTaskClass = findFirstClassByStrings(
                    "MicroMsg.MsgRetransmitUI",
                    "CopyVideoTask ori[%s] status[%d] new[%s]");
            logDetail("视频静默Task: "
                    + (sendVideoTaskClass != null ? sendVideoTaskClass.getName() : "null"));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveSendVideoTaskApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveVideoPathApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (!isVideoPathMethod(videoPathMethod)) {
                videoPathMethod = null;
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings(
                                "MicroMsg.C2CVideoPathFeatureService",
                                "success restore file, from ",
                                ".mp4"));
                if (methods.isEmpty()) {
                    methods = dexKit.findMethod(
                            mkMethodUsingStrings(
                                    "MicroMsg.VideoInfoStorage",
                                    "success restore file, from ",
                                    ".mp4"));
                }
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isVideoPathMethod(method)) continue;
                        videoPathMethod = KavaReflector.accessible(method);
                        break;
                    } catch (Throwable ignored) {}
                }
            }
            Class<?> owner = videoPathMethod != null ? videoPathMethod.getDeclaringClass() : null;
            if (videoPathMethod != null && !KavaReflector.isStatic(videoPathMethod)) {
                if (!isVideoPathOwnerGetter(videoPathOwnerGetterMethod, owner)) {
                    videoPathOwnerGetterMethod = findVideoPathOwnerGetter(owner);
                }
            } else {
                videoPathOwnerGetterMethod = null;
            }
            logDetail("视频消息路径API: path=" + methodName(videoPathMethod)
                    + " owner=" + methodName(videoPathOwnerGetterMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveVideoPathApi 失败: " + e.getMessage(), e);
        }
    }

    private boolean isVideoPathMethod(Method method) {
        return method != null
                && method.getReturnType() == String.class
                && method.getParameterTypes().length == 1
                && method.getParameterTypes()[0] == String.class;
    }

    private Method findVideoPathOwnerGetter(Class<?> owner) {
        if (owner == null) return null;
        try {
            FindMethod fm = new FindMethod();
            MethodMatcher mm = new MethodMatcher();
            mm.paramCount(0);
            mm.returnType(owner.getName());
            fm.matcher(mm);
            for (MethodData methodData : dexKit.findMethod(fm)) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isVideoPathOwnerGetter(method, owner)) continue;
                    return KavaReflector.accessible(method);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isVideoPathOwnerGetter(Method method, Class<?> owner) {
        return method != null
                && owner != null
                && KavaReflector.isStatic(method)
                && method.getParameterTypes().length == 0
                && owner.isAssignableFrom(method.getReturnType());
    }

    public void resolveVideoInfoApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (isVideoInfoByFileNameMethod(videoInfoByFileNameMethod, videoInfoClass)) return;
            videoInfoByFileNameMethod = null;

            videoInfoClass = findFirstClassByStrings("VideoInfo{fileName='");
            if (videoInfoClass == null) return;

            FindMethod find = new FindMethod();
            MethodMatcher matcher = new MethodMatcher();
            matcher.paramTypes(String.class);
            matcher.returnType(videoInfoClass.getName());
            find.matcher(matcher);

            List<Method> candidates = new ArrayList<>();
            for (MethodData data : dexKit.findMethod(find)) {
                try {
                    Method method = data.getMethodInstance(classLoader);
                    if (!isVideoInfoByFileNameMethod(method, videoInfoClass)) continue;
                    if (!samePackage(method.getDeclaringClass(), videoInfoClass)) continue;
                    Method accessible = KavaReflector.accessible(method);
                    if (!candidates.contains(accessible)) candidates.add(accessible);
                } catch (Throwable ignored) {}
            }
            if (candidates.size() == 1) {
                videoInfoByFileNameMethod = candidates.get(0);
            }
            logDetail("视频信息查询API: " + methodName(videoInfoByFileNameMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveVideoInfoApi 失败: " + e.getMessage(), e);
        }
    }

    private boolean isVideoInfoByFileNameMethod(Method method, Class<?> expectedReturnType) {
        return method != null
                && expectedReturnType != null
                && KavaReflector.isStatic(method)
                && method.getParameterTypes().length == 1
                && method.getParameterTypes()[0] == String.class
                && method.getReturnType() == expectedReturnType;
    }

    private boolean samePackage(Class<?> left, Class<?> right) {
        if (left == null || right == null) return false;
        String leftName = left.getName();
        String rightName = right.getName();
        int leftDot = leftName.lastIndexOf('.');
        int rightDot = rightName.lastIndexOf('.');
        return leftDot == rightDot
                && leftDot >= 0
                && leftName.regionMatches(0, rightName, 0, leftDot);
    }

    public void resolveSendVoiceApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (voiceStartRecordMethod == null) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("startRecord insert voicestg success"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isVoiceStartRecordMethod(method)) continue;
                        KavaReflector.accessible(method);
                        voiceStartRecordMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            if (voiceFullPathMethod == null) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("getAmrFullPath cost:"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isVoiceFullPathMethod(method)) continue;
                        KavaReflector.accessible(method);
                        voiceFullPathMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            if (voiceMessagePathMethod == null && !voiceMessagePathLookupComplete) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("[f11]getVoiceFullPath, businessType: "));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isVoiceMessagePathMethod(method)) continue;
                        voiceMessagePathMethod = KavaReflector.accessible(method);
                        break;
                    } catch (Throwable ignored) {}
                }
                voiceMessagePathLookupComplete = methods.isEmpty() || voiceMessagePathMethod != null;
            }

            if (voiceFinishRecordMethod == null && voiceStartRecordMethod != null) {
                Class<?> owner = voiceStartRecordMethod.getDeclaringClass();
                for (Method method : KavaReflector.declaredMethods(owner)) {
                    if (!isVoiceFinishRecordMethod(method)) continue;
                    KavaReflector.accessible(method);
                    voiceFinishRecordMethod = method;
                    break;
                }
            }

            if (voiceInfoQueryMethod == null) {
                voiceInfoQueryMethod = findVoiceInfoQueryMethod();
            }

            if (voiceUploadClass == null || (voiceUploadCtor == null && voiceUploadCdnCtor == null)) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("/cgi-bin/micromsg-bin/uploadvoice"));
                for (MethodData methodData : methods) {
                    try {
                        Class<?> clazz = KavaReflector.loadClass(methodData.getClassName(), classLoader);
                        Constructor<?> ctor = findCtorByExactTypes(clazz, String.class, int.class);
                        Constructor<?> cdnCtor = findCtorByExactTypes(clazz, String.class, boolean.class);
                        if (ctor == null && cdnCtor == null) continue;
                        voiceUploadClass = clazz;
                        voiceUploadCtor = ctor;
                        voiceUploadCdnCtor = cdnCtor;
                        break;
                    } catch (Throwable ignored) {}
                }
            }
            if (voiceUploadClass != null && voiceUploadCdnCtor == null) {
                voiceUploadCdnCtor = findCtorByExactTypes(voiceUploadClass, String.class, boolean.class);
            }

            resolveVoicePlaybackApi();

            logDetail("语音发送API: start=" + methodName(voiceStartRecordMethod)
                    + " path=" + methodName(voiceFullPathMethod)
                    + " messagePath=" + methodName(voiceMessagePathMethod)
                    + " finish=" + methodName(voiceFinishRecordMethod)
                    + " info=" + methodName(voiceInfoQueryMethod)
                    + " upload=" + (voiceUploadClass != null ? voiceUploadClass.getName() : "null")
                    + " cdnCtor=" + (voiceUploadCdnCtor != null));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveSendVoiceApi 失败: " + e.getMessage(), e);
        }
    }

    private void resolveVoicePlaybackApi() {
        if (!isVoicePlaybackStartMethod(voicePlaybackStartMethod)) {
            voicePlaybackStartMethod = findVoicePlaybackMethod(
                    null,
                    "start file name:[%s] speakerOn:[%b], isFullPath: %s, type: %s, userType: %s",
                    boolean.class,
                    String.class, boolean.class, boolean.class, int.class);
        }
        Class<?> owner = voicePlaybackStartMethod != null
                ? voicePlaybackStartMethod.getDeclaringClass()
                : null;
        if (owner == null) {
            voicePlaybackPauseMethod = null;
            voicePlaybackResumeMethod = null;
            voicePlaybackStopMethod = null;
            return;
        }
        if (!isExactInstanceMethod(voicePlaybackPauseMethod, owner, boolean.class, boolean.class)) {
            voicePlaybackPauseMethod = findVoicePlaybackMethod(
                    owner, "pause ret:%s", boolean.class, boolean.class);
        }
        if (!isExactInstanceMethod(voicePlaybackResumeMethod, owner, boolean.class)) {
            voicePlaybackResumeMethod = findVoicePlaybackMethod(
                    owner, "resumePlaying set mute false", boolean.class);
        }
        if (!isExactInstanceMethod(voicePlaybackStopMethod, owner, void.class, boolean.class)) {
            voicePlaybackStopMethod = findVoicePlaybackMethod(
                    owner, "stop player failed cause player is null %s", void.class, boolean.class);
        }
        logDetail("语音播放API: start=" + methodName(voicePlaybackStartMethod)
                + " pause=" + methodName(voicePlaybackPauseMethod)
                + " resume=" + methodName(voicePlaybackResumeMethod)
                + " stop=" + methodName(voicePlaybackStopMethod));
    }

    private Method findVoicePlaybackMethod(Class<?> owner,
                                           String anchor,
                                           Class<?> returnType,
                                           Class<?>... parameterTypes) {
        try {
            for (MethodData data : dexKit.findMethod(mkMethodUsingStrings(anchor))) {
                try {
                    Method method = data.getMethodInstance(classLoader);
                    Class<?> expectedOwner = owner != null ? owner : method.getDeclaringClass();
                    if (!isExactInstanceMethod(method, expectedOwner, returnType, parameterTypes)) continue;
                    return KavaReflector.accessible(method);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isVoicePlaybackStartMethod(Method method) {
        return method != null
                && isExactInstanceMethod(
                        method,
                        method.getDeclaringClass(),
                        boolean.class,
                        String.class, boolean.class, boolean.class, int.class)
                && findCtorByExactTypes(method.getDeclaringClass(), Context.class, int.class) != null;
    }

    private boolean isExactInstanceMethod(Method method,
                                          Class<?> owner,
                                          Class<?> returnType,
                                          Class<?>... parameterTypes) {
        return method != null
                && owner != null
                && method.getDeclaringClass() == owner
                && !KavaReflector.isStatic(method)
                && method.getReturnType() == returnType
                && Arrays.equals(method.getParameterTypes(), parameterTypes);
    }

    private Method findVoiceInfoQueryMethod() {
        List<Method> wrappers = new ArrayList<>();
        try {
            FindMethod find = new FindMethod();
            MethodMatcher matcher = new MethodMatcher();
            matcher.paramTypes(String.class);
            matcher.addUsingString("voiceinfo WHERE FileName= ?", StringMatchType.Contains, false);
            find.matcher(matcher);
            for (MethodData data : dexKit.findMethod(find)) {
                Method storageMethod;
                try {
                    storageMethod = data.getMethodInstance(classLoader);
                } catch (Throwable ignored) {
                    continue;
                }
                if (!isVoiceInfoStorageQueryMethod(storageMethod)) continue;
                for (MethodData callerData : data.getCallers()) {
                    try {
                        Method caller = callerData.getMethodInstance(classLoader);
                        if (!isVoiceInfoQueryMethod(caller, storageMethod.getReturnType())) continue;
                        KavaReflector.accessible(caller);
                        if (!wrappers.contains(caller)) wrappers.add(caller);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return wrappers.size() == 1 ? wrappers.get(0) : null;
    }

    private boolean isVoiceInfoStorageQueryMethod(Method method) {
        if (method == null || KavaReflector.isStatic(method)) return false;
        Class<?>[] params = method.getParameterTypes();
        Class<?> returnType = method.getReturnType();
        return params.length == 1
                && params[0] == String.class
                && returnType != void.class
                && !returnType.isPrimitive();
    }

    private boolean isVoiceInfoQueryMethod(Method method, Class<?> voiceInfoClass) {
        if (method == null || voiceInfoClass == null || !KavaReflector.isStatic(method)) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 1
                && params[0] == String.class
                && method.getReturnType() == voiceInfoClass;
    }

    public void resolveSendEmojiApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (emojiSendMethod == null) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("NetSceneUploadEmoji: msgId"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isEmojiSendMethod(method)) continue;
                        KavaReflector.accessible(method);
                        emojiSendMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            if (emojiManagerSendMethod == null) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("sendEmoji: context is null", "sendEmoji: emoji not found"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isEmojiManagerSendMethod(method)) continue;
                        KavaReflector.accessible(method);
                        emojiManagerSendMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            if (emojiGetByMd5Method == null) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("getEmojiByMd5"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isEmojiGetByMd5Method(method)) continue;
                        KavaReflector.accessible(method);
                        emojiGetByMd5Method = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            if (emojiCreateInfoMethod == null) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("createEmojiInfo"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isEmojiCreateInfoMethod(method)) continue;
                        KavaReflector.accessible(method);
                        emojiCreateInfoMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            if (emojiUpdateInfoMethod == null) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("updateEmojiInfo"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isEmojiUpdateInfoMethod(method)) continue;
                        KavaReflector.accessible(method);
                        emojiUpdateInfoMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            if (emojiAccPathMethod == null) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("getAccPath"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isNoArgStringMethod(method)) continue;
                        KavaReflector.accessible(method);
                        emojiAccPathMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            if (emojiCheckGifMethod == null) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("checkGifFile"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isStringBooleanMethod(method)) continue;
                        KavaReflector.accessible(method);
                        emojiCheckGifMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            if (emojiFilePathMethod == null) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("[cpan] get icon path failed. productid and md5 are null."));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isEmojiFilePathMethod(method)) continue;
                        KavaReflector.accessible(method);
                        emojiFilePathMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            if (emojiDecodeDataMethod == null || emojiDecodeManagerGetterMethod == null) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings(
                                "MicroMsg.emoji.EmojiFileEncryptMgr",
                                "decode emoji file failed. path is no exist :%s "));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isEmojiDecodeDataMethod(method)) continue;
                        Method getter = findEmojiDecodeManagerGetter(method.getDeclaringClass());
                        if (getter == null) continue;
                        emojiDecodeDataMethod = KavaReflector.accessible(method);
                        emojiDecodeManagerGetterMethod = KavaReflector.accessible(getter);
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            logDetail("表情发送API: send=" + methodName(emojiSendMethod)
                    + " managerSend=" + methodName(emojiManagerSendMethod)
                    + " getByMd5=" + methodName(emojiGetByMd5Method)
                    + " create=" + methodName(emojiCreateInfoMethod)
                    + " accPath=" + methodName(emojiAccPathMethod)
                    + " decode=" + methodName(emojiDecodeDataMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveSendEmojiApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveFavoriteApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            resolveFavoriteItemApi();
            resolveFavoriteListApi();
            resolveFavoriteSendApi();
            logDetail("收藏API: item="
                    + (favoriteItemClass != null ? favoriteItemClass.getName() : "null")
                    + " convert=" + methodName(favoriteItemConvertFromCursorMethod)
                    + " list=" + methodName(favoriteListMethod)
                    + " send=" + methodName(favoriteSendMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveFavoriteApi 失败: " + e.getMessage(), e);
        }
    }

    private void resolveFavoriteListApi() {
        try {
            if (favoriteServiceClass != null
                    && isFavoriteServiceResolverMethod(favoriteServiceResolverMethod)
                    && isFavoriteStorageGetterMethod(favoriteStorageGetterMethod)
                    && isFavoriteListMethod(favoriteListMethod)
                    && isFavoriteListNextMethod(favoriteListNextMethod)
                    && isFavoriteListCursorMethod(favoriteListCursorMethod)
                    && isFavoriteGetMethod(favoriteGetMethod)) return;

            favoriteServiceClass = null;
            favoriteServiceResolverMethod = null;
            favoriteStorageGetterMethod = null;
            favoriteListMethod = null;
            favoriteListNextMethod = null;
            favoriteListCursorMethod = null;
            favoriteGetMethod = null;
            Method cursor = findFavoriteListCursorMethod();
            List<MethodData> methods = dexKit.findMethod(
                    mkMethodUsingStrings(
                            "getItemList error, getFavItemInfoStorage null.",
                            "MicroMsg.Fav.FavApiLogic"));
            for (MethodData methodData : methods) {
                Method resolver = null;
                Method getter = null;
                Method list = null;
                Method next = null;
                Class<?> service = null;
                for (MethodData invokeData : methodData.getInvokes()) {
                    Method invoked;
                    try {
                        invoked = invokeData.getMethodInstance(classLoader);
                    } catch (Throwable ignored) {
                        continue;
                    }
                    if (isFavoriteServiceResolverMethod(invoked)) resolver = invoked;
                    if (isFavoriteListMethod(invoked)) list = invoked;
                    if (isFavoriteListNextMethod(invoked)) next = invoked;
                }
                if (list != null) {
                    for (MethodData invokeData : methodData.getInvokes()) {
                        Method invoked;
                        try {
                            invoked = invokeData.getMethodInstance(classLoader);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        if (!isFavoriteStorageGetterMethod(invoked)) continue;
                        if (invoked.getReturnType() != list.getDeclaringClass()) continue;
                        getter = invoked;
                        service = invoked.getDeclaringClass();
                        break;
                    }
                }
                if (resolver == null || getter == null || list == null || next == null
                        || cursor == null || service == null) continue;
                KavaReflector.accessible(resolver);
                KavaReflector.accessible(getter);
                KavaReflector.accessible(list);
                KavaReflector.accessible(next);
                favoriteServiceClass = service;
                favoriteServiceResolverMethod = resolver;
                favoriteStorageGetterMethod = getter;
                favoriteListMethod = list;
                favoriteListNextMethod = next;
                favoriteListCursorMethod = cursor;
                favoriteGetMethod = findFavoriteGetMethod(list.getDeclaringClass());
                if (favoriteGetMethod == null) continue;
                KavaReflector.accessible(favoriteGetMethod);
                return;
            }
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveFavoriteListApi 失败: " + e.getMessage(), e);
        }
    }

    private boolean isFavoriteServiceResolverMethod(Method method) {
        if (method == null || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) return false;
        Class<?>[] p = method.getParameterTypes();
        return p.length == 1 && p[0] == Class.class && method.getReturnType() != void.class;
    }

    private boolean isFavoriteStorageGetterMethod(Method method) {
        if (method == null || java.lang.reflect.Modifier.isStatic(method.getModifiers())) return false;
        return method.getParameterTypes().length == 0
                && method.getReturnType() != void.class
                && !method.getReturnType().isPrimitive();
    }

    private boolean isFavoriteListMethod(Method method) {
        if (method == null || java.lang.reflect.Modifier.isStatic(method.getModifiers())) return false;
        Class<?>[] p = method.getParameterTypes();
        return java.util.List.class.isAssignableFrom(method.getReturnType())
                && p.length == 5
                && p[0] == int.class
                && p[1] == int.class
                && java.util.List.class.isAssignableFrom(p[2])
                && java.util.Set.class.isAssignableFrom(p[3]);
    }

    private boolean isFavoriteListNextMethod(Method method) {
        if (method == null || java.lang.reflect.Modifier.isStatic(method.getModifiers())) return false;
        Class<?>[] p = method.getParameterTypes();
        return java.util.List.class.isAssignableFrom(method.getReturnType())
                && p.length == 5
                && p[0] == long.class
                && p[1] == int.class
                && java.util.List.class.isAssignableFrom(p[2])
                && java.util.Set.class.isAssignableFrom(p[3]);
    }

    private Method findFavoriteListCursorMethod() {
        List<MethodData> methods = dexKit.findMethod(
                mkMethodUsingStrings("tryStartBatchGet..."));
        for (MethodData methodData : methods) {
            try {
                Method method = methodData.getMethodInstance(classLoader);
                if (!isFavoriteListCursorMethod(method)) continue;
                KavaReflector.accessible(method);
                return method;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private Class<?> findSendImageAppInfoClassFromCrossParams() {
        if (sendImageCrossParamsClass == null) return null;
        List<Class<?>> indexedCandidates = new ArrayList<>();
        for (Field field : KavaReflector.declaredFields(sendImageCrossParamsClass)) {
            Class<?> type = field.getType();
            if (type == null
                    || type.isPrimitive()
                    || type == String.class
                    || type == Object.class
                    || type.getName().startsWith("java.")) {
                continue;
            }
            if (hasDirectImageAppInfoFields(type)) return type;
            if (isSendImageAppInfoClass(type)) indexedCandidates.add(type);
        }
        return indexedCandidates.isEmpty() ? null : indexedCandidates.get(0);
    }

    private boolean isFavoriteListCursorMethod(Method method) {
        if (method == null || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) return false;
        Class<?>[] p = method.getParameterTypes();
        return method.getReturnType() == long.class
                && p.length == 3
                && p[0] == long.class
                && p[1] == int.class
                && p[2] == int.class;
    }

    private Method findFavoriteGetMethod(Class<?> storageClass) {
        if (storageClass == null || favoriteItemClass == null) return null;
        for (Method method : KavaReflector.declaredMethods(storageClass)) {
            if (!isFavoriteGetMethod(method)) continue;
            return method;
        }
        return null;
    }

    private boolean isFavoriteGetMethod(Method method) {
        if (method == null || java.lang.reflect.Modifier.isStatic(method.getModifiers())) return false;
        Class<?>[] p = method.getParameterTypes();
        return p.length == 1 && p[0] == long.class
                && favoriteItemClass != null
                && favoriteItemClass.isAssignableFrom(method.getReturnType());
    }

    private void resolveFavoriteItemApi() {
        try {
            if (isFavoriteItemClass(favoriteItemClass)) {
                favoriteItemConvertFromCursorMethod = findFavoriteItemConvertFromCursorMethod(favoriteItemClass);
                if (favoriteItemConvertFromCursorMethod != null) return;
            }

            favoriteItemClass = null;
            favoriteItemConvertFromCursorMethod = null;

            List<ClassData> classes = dexKit.findClass(mkClassUsingStrings("FavItemInfo"));
            for (ClassData classData : classes) {
                Class<?> clazz = KavaReflector.loadClass(classData.getName(), classLoader);
                if (!isFavoriteItemClass(clazz)) continue;
                favoriteItemClass = clazz;
                favoriteItemConvertFromCursorMethod = findFavoriteItemConvertFromCursorMethod(clazz);
                if (favoriteItemConvertFromCursorMethod != null) return;
            }

            List<MethodData> methods = dexKit.findMethod(
                    mkMethodUsingStrings("sendFavMsg: processing favId=%d, favType=%d, dataListSize=%d"));
            for (MethodData methodData : methods) {
                Class<?> clazz = favoriteItemClassFromUsingFields(methodData);
                if (!isFavoriteItemClass(clazz)) continue;
                favoriteItemClass = clazz;
                favoriteItemConvertFromCursorMethod = findFavoriteItemConvertFromCursorMethod(clazz);
                if (favoriteItemConvertFromCursorMethod != null) return;
            }
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveFavoriteItemApi 失败: " + e.getMessage(), e);
        }
    }

    private void resolveFavoriteSendApi() {
        try {
            Method cached = findFavoriteSendDispatchMethod(favoriteSendMethod);
            if (cached != null) {
                favoriteSendMethod = cached;
                return;
            }

            favoriteSendMethod = null;
            List<MethodData> methods = dexKit.findMethod(
                    mkMethodUsingStrings(
                            "want to send fav msg, but context is null",
                            "want to send fav msg, but info is null"));
            for (MethodData methodData : methods) {
                Method anchor = methodData.getMethodInstance(classLoader);
                Method dispatch = findFavoriteSendDispatchMethod(anchor);
                if (dispatch == null) continue;
                favoriteSendMethod = dispatch;
                return;
            }
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveFavoriteSendApi 失败: " + e.getMessage(), e);
        }
    }

    private Method findFavoriteSendDispatchMethod(Method anchor) {
        if (anchor == null) return null;
        if (isFavoriteSendMethod(anchor)) return KavaReflector.accessible(anchor);
        for (Method method : KavaReflector.declaredMethods(anchor.getDeclaringClass())) {
            if (!isFavoriteSendMethod(method)) continue;
            return KavaReflector.accessible(method);
        }
        return null;
    }

    private Class<?> favoriteItemClassFromUsingFields(MethodData methodData) {
        if (methodData == null) return null;
        try {
            for (UsingFieldData usingFieldData : methodData.getUsingFields()) {
                FieldData fieldData = usingFieldData.getField();
                if (fieldData == null) continue;
                if (!"field_favProto".equals(fieldData.getFieldName())) continue;
                Class<?> clazz = KavaReflector.loadClass(fieldData.getClassName(), classLoader);
                if (isFavoriteItemClass(clazz)) return clazz;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean isFavoriteItemClass(Class<?> clazz) {
        if (clazz == null) return false;
        if (KavaReflector.findFieldRecursive(clazz, "field_localId") == null) return false;
        if (KavaReflector.findFieldRecursive(clazz, "field_type") == null) return false;
        if (KavaReflector.findFieldRecursive(clazz, "field_favProto") == null) return false;
        if (KavaReflector.findConstructor(clazz) == null) return false;
        return findFavoriteItemConvertFromCursorMethod(clazz) != null;
    }

    private Method findFavoriteItemConvertFromCursorMethod(Class<?> clazz) {
        if (clazz == null) return null;
        Method named = KavaReflector.findMethodRecursive(clazz, "convertFrom", Cursor.class);
        if (isFavoriteItemConvertFromCursorMethod(named)) {
            KavaReflector.accessible(named);
            return named;
        }
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : KavaReflector.declaredMethods(current)) {
                if (!isFavoriteItemConvertFromCursorMethod(method)) continue;
                KavaReflector.accessible(method);
                return method;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private boolean isFavoriteItemConvertFromCursorMethod(Method method) {
        if (method == null || method.getReturnType() != void.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 1 && Cursor.class.isAssignableFrom(params[0]);
    }

    private boolean isFavoriteSendMethod(Method method) {
        if (method == null || !KavaReflector.isStatic(method) || method.getReturnType() != void.class) return false;
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 6) {
            return Context.class.isAssignableFrom(params[0])
                    && params[1] == String.class
                    && params[2] == String.class
                    && params[3] == boolean.class
                    && favoriteItemClass != null
                    && (params[4].isAssignableFrom(favoriteItemClass)
                        || favoriteItemClass.isAssignableFrom(params[4]))
                    && Runnable.class.isAssignableFrom(params[5]);
        }
        return params.length == 5
                && Context.class.isAssignableFrom(params[0])
                && params[1] == String.class
                && params[2] == String.class
                && List.class.isAssignableFrom(params[3])
                && Runnable.class.isAssignableFrom(params[4]);
    }

    public void resolveTransferOperationApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (isTransferOperationClass(transferOperationClass)) return;

            try {
                Class<?> direct = KavaReflector.loadClass(
                        "com.tencent.mm.plugin.remittance.model.n0", classLoader);
                if (isTransferOperationClass(direct)) {
                    transferOperationClass = direct;
                    logDetail("转账操作类: " + transferOperationClass.getName());
                    return;
                }
            } catch (Throwable ignored) {}

            List<ClassData> classes = dexKit.findClass(
                    mkClassUsingStrings("Micromsg.NetSceneTenpayRemittanceConfirm",
                            "recv_account_type"));
            for (ClassData cd : classes) {
                try {
                    Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                    if (!isTransferOperationClass(cl)) continue;
                    transferOperationClass = cl;
                    break;
                } catch (Throwable ignored) {}
            }

            if (transferOperationClass == null) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("/cgi-bin/mmpay-bin/transferoperation"));
                for (MethodData methodData : methods) {
                    try {
                        Class<?> cl = KavaReflector.loadClass(methodData.getClassName(), classLoader);
                        if (!isTransferOperationClass(cl)) continue;
                        transferOperationClass = cl;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            logDetail("转账操作类: "
                    + (transferOperationClass != null ? transferOperationClass.getName() : "null"));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveTransferOperationApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveTransferQueryApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (isTransferQueryClass(transferQueryClass)
                    && isTransferQueryResponseMethod(transferQueryResponseMethod, transferQueryClass)) return;

            List<ClassData> classes = dexKit.findClass(
                    mkClassUsingStrings("Micromsg.NetSceneTenpayRemittanceQuery",
                            "recv_account_info", "recv_channel"));
            for (ClassData cd : classes) {
                try {
                    Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                    Method response = findTransferQueryResponseMethod(cl);
                    if (!isTransferQueryClass(cl) || response == null) continue;
                    transferQueryClass = cl;
                    transferQueryResponseMethod = response;
                    break;
                } catch (Throwable ignored) {}
            }
            logDetail("转账查询类: "
                    + (transferQueryClass != null ? transferQueryClass.getName() : "null")
                    + " response=" + methodName(transferQueryResponseMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveTransferQueryApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveVerifyUserApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (isVerifyUserClass(verifyUserClass)) return;

            List<ClassData> classes = dexKit.findClass(
                    mkClassUsingStrings("/cgi-bin/micromsg-bin/verifyuser",
                            "MicroMsg.NetSceneVerifyUser.dkverify"));
            for (ClassData cd : classes) {
                try {
                    Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                    if (!isVerifyUserClass(cl)) continue;
                    verifyUserClass = cl;
                    break;
                } catch (Throwable ignored) {}
            }

            if (verifyUserClass == null) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("/cgi-bin/micromsg-bin/verifyuser"));
                for (MethodData methodData : methods) {
                    try {
                        Class<?> cl = KavaReflector.loadClass(methodData.getClassName(), classLoader);
                        if (!isVerifyUserClass(cl)) continue;
                        verifyUserClass = cl;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            logDetail("好友申请验证类: "
                    + (verifyUserClass != null ? verifyUserClass.getName() : "null"));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveVerifyUserApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveContactCardApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (isContactCardXmlMethod(contactCardXmlMethod)) return;

            List<MethodData> methods = dexKit.findMethod(
                    mkMethodUsingStrings("MicroMsg.SendContactCardHelper", "getBizNameCardString"));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isContactCardXmlMethod(method)) continue;
                    contactCardXmlMethod = KavaReflector.accessible(method);
                    break;
                } catch (Throwable ignored) {}
            }

            if (contactCardXmlMethod == null) {
                methods = dexKit.findMethod(mkMethodUsingStrings("bigheadimgurl", "smallheadimgurl"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isContactCardXmlMethod(method)) continue;
                        contactCardXmlMethod = KavaReflector.accessible(method);
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            logDetail("名片XML方法: " + methodName(contactCardXmlMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveContactCardApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolvePatMessageApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (!isPatDisplayTemplateMethod(patDisplayTemplateMethod)) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("MicroMsg.PluginPatMsg",
                                "parseDisplayTemplate realtime templateStr:%s"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isPatDisplayTemplateMethod(method)) continue;
                        KavaReflector.accessible(method);
                        patDisplayTemplateMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }
            resolvePatSendApi();
            logDetail("拍一拍API: template=" + methodName(patDisplayTemplateMethod)
                    + " extension=" + (patExtensionClass != null ? patExtensionClass.getName() : "null")
                    + " create=" + methodName(patCreatePairMethod)
                    + " suffix=" + methodName(patSuffixMethod)
                    + " scene=" + (sendPatSceneClass != null ? sendPatSceneClass.getName() : "null"));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolvePatMessageApi 失败: " + e.getMessage(), e);
        }
    }

    private void resolvePatSendApi() {
        if (sendPatSceneCtor == null) {
            sendPatSceneCtor = findSendPatSceneCtor(sendPatSceneClass);
        }
        if (isPatCreatePairMethod(patCreatePairMethod)
                && isPatSuffixMethod(patSuffixMethod)
                && sendPatSceneCtor != null) {
            return;
        }
        resolvePatExtensionApi();
        resolveSendPatSceneApi();
    }

    private void resolvePatExtensionApi() {
        try {
            if (isPatCreatePairMethod(patCreatePairMethod)
                    && isPatSuffixMethod(patSuffixMethod)
                    && patExtensionClass != null) {
                return;
            }
            if (!isPatCreatePairMethod(patCreatePairMethod)) {
                patCreatePairMethod = findPatCreatePairMethodByStrings(
                        "MicroMsg.PatMsgExtension", "insert pat msg %d %s %s");
                if (patCreatePairMethod == null) {
                    patCreatePairMethod = findPatCreatePairMethodByStrings("insert pat msg %d %s %s");
                }
            }
            if (!isPatSuffixMethod(patSuffixMethod)) {
                patSuffixMethod = findPatSuffixMethodByStrings(
                        "MicroMsg.PatMsgExtension", "pattedUser %s, suffix %s");
                if (patSuffixMethod == null) {
                    patSuffixMethod = findPatSuffixMethodByStrings("pattedUser %s, suffix %s");
                }
            }
            if (!isPatCanSendMethod(patCanSendMethod)) {
                patCanSendMethod = findPatCanSendMethodByStrings(
                        "MicroMsg.PatMsgExtension", "cannot pat, talker %s");
                if (patCanSendMethod == null) {
                    patCanSendMethod = findPatCanSendMethodByStrings("cannot pat, talker %s");
                }
            }
            if (patCreatePairMethod != null && patSuffixMethod != null
                    && patCreatePairMethod.getDeclaringClass() == patSuffixMethod.getDeclaringClass()) {
                patExtensionClass = patCreatePairMethod.getDeclaringClass();
                if (!isPatCanSendMethod(patCanSendMethod)) {
                    patCanSendMethod = findPatCanSendMethod(patExtensionClass);
                }
                return;
            }
            List<Class<?>> candidates = new ArrayList<>();
            collectSendTextMethodOwnerCandidates(candidates, 20,
                    new String[][]{
                            {"MicroMsg.PatMsgExtension", "insert pat msg %d %s %s"},
                            {"MicroMsg.PatMsgExtension", "pattedUser %s, suffix %s"},
                            {"insert pat msg %d %s %s"},
                            {"pattedUser %s, suffix %s"}
                    });
            for (Class<?> candidate : candidates) {
                Method createMethod = findPatCreatePairMethod(candidate);
                Method suffixMethod = findPatSuffixMethod(candidate);
                if (createMethod == null || suffixMethod == null) continue;
                patExtensionClass = candidate;
                patCreatePairMethod = createMethod;
                patSuffixMethod = suffixMethod;
                patCanSendMethod = findPatCanSendMethod(candidate);
                break;
            }
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolvePatExtensionApi 失败: " + e.getMessage(), e);
        }
    }

    private Method findPatCreatePairMethodByStrings(String... strings) {
        try {
            List<MethodData> methods = dexKit.findMethod(mkMethodUsingStrings(strings));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isPatCreatePairMethod(method)) continue;
                    return KavaReflector.accessible(method);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Method findPatSuffixMethodByStrings(String... strings) {
        try {
            List<MethodData> methods = dexKit.findMethod(mkMethodUsingStrings(strings));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isPatSuffixMethod(method)) continue;
                    return KavaReflector.accessible(method);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Method findPatCanSendMethodByStrings(String... strings) {
        try {
            List<MethodData> methods = dexKit.findMethod(mkMethodUsingStrings(strings));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isPatCanSendMethod(method)) continue;
                    return KavaReflector.accessible(method);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void resolveSendPatSceneApi() {
        try {
            if (sendPatSceneCtor == null) {
                sendPatSceneCtor = findSendPatSceneCtor(sendPatSceneClass);
            }
            if (sendPatSceneCtor != null) return;
            List<Class<?>> candidates = new ArrayList<>();
            collectSendTextClassCandidates(candidates, 20,
                    new String[][]{
                            {"/cgi-bin/micromsg-bin/sendpat"},
                            {"MicroMsg.NetSceneSendPat"},
                            {"sendpat"}
                    });
            collectSendTextMethodOwnerCandidates(candidates, 20,
                    new String[][]{
                            {"/cgi-bin/micromsg-bin/sendpat"},
                            {"MicroMsg.NetSceneSendPat"}
                    });
            for (Class<?> candidate : candidates) {
                if (!isSendPatSceneClass(candidate)) continue;
                sendPatSceneClass = candidate;
                sendPatSceneCtor = findSendPatSceneCtor(candidate);
                break;
            }
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveSendPatSceneApi 失败: " + e.getMessage(), e);
        }
    }

    // ============ 数据库/联系人公共 API ============
    public void resolveDatabaseApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (coreStorageGetter != null && sqliteDbWrapperClass != null && configStorageClass != null) return;

            mmKernelClass = findFirstClassByStrings(
                    "MicroMsg.MMKernel",
                    "Kernel not null, has initialized.");

            List<MethodData> storageMethods = dexKit.findMethod(
                    mkMethodUsingStrings("mCoreStorage not initialized!"));
            for (MethodData methodData : storageMethods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (method.getParameterTypes().length != 0) continue;
                    if (method.getReturnType() == void.class) continue;
                    KavaReflector.accessible(method);
                    coreStorageGetter = method;
                    coreStorageClass = method.getReturnType();
                    break;
                } catch (Throwable ignored) {}
            }

            if (coreStorageClass == null) {
                coreStorageClass = findFirstClassByStrings(
                        "MMKernel.CoreStorage",
                        "CheckData path[%s] blocksize:%s blockcount:%s availcount:%s");
            }

            configStorageClass = findFirstClassByStrings(
                    "MicroMsg.ConfigStorage",
                    "shouldProcessEvent db is close :%s");

            sqliteDbWrapperClass = findFirstClassByStrings(
                    "MicroMsg.SqliteDB",
                    "sql is null ");

            logDetail("数据库API: kernel="
                    + (mmKernelClass != null ? mmKernelClass.getName() : "null")
                    + " storageGetter=" + (coreStorageGetter != null ? coreStorageGetter.getName() : "null")
                    + " coreStorage=" + (coreStorageClass != null ? coreStorageClass.getName() : "null")
                    + " config=" + (configStorageClass != null ? configStorageClass.getName() : "null")
                    + " sqlite=" + (sqliteDbWrapperClass != null ? sqliteDbWrapperClass.getName() : "null"));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveDatabaseApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveConversationDeleteApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (isConversationDeleteMethod(conversationDeleteMethod)) return;
            conversationDeleteMethod = null;

            List<Method> candidates = new ArrayList<>();
            FindMethod find = new FindMethod();
            MethodMatcher matcher = new MethodMatcher();
            matcher.usingEqStrings("delChatContact username:%s  stack:%s");
            find.matcher(matcher);
            for (MethodData methodData : dexKit.findMethod(find)) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isConversationDeleteMethod(method) || candidates.contains(method)) continue;
                    KavaReflector.accessible(method);
                    candidates.add(method);
                } catch (Throwable ignored) {}
            }
            if (candidates.size() == 1) {
                conversationDeleteMethod = candidates.get(0);
                logDetail("原生会话删除API: " + methodName(conversationDeleteMethod));
            } else {
                h.Hchat.utils.HLog.e(TAG + " 原生会话删除API定位失败: candidates="
                        + candidates.size() + " key=" + shortKey(runtimeCacheKey));
            }
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveConversationDeleteApi 失败: " + e.getMessage(), e);
        }
    }

    private boolean isConversationDeleteMethod(Method method) {
        if (method == null || KavaReflector.isStatic(method) || method.getReturnType() != void.class) {
            return false;
        }
        Class<?> owner = method.getDeclaringClass();
        Class<?>[] params = method.getParameterTypes();
        return owner != null
                && owner.getName().startsWith("com.tencent.mm.storage.")
                && params.length == 1
                && params[0] == String.class;
    }

    public void resolveMessageClearApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (isMessageClearByTalkerMethod(messageClearByTalkerMethod, messageClearBatchMethod)) {
                return;
            }
            messageClearByTalkerMethod = null;
            messageClearBatchMethod = null;

            List<Method> batchCandidates = new ArrayList<>();
            FindMethod find = new FindMethod();
            MethodMatcher matcher = new MethodMatcher();
            matcher.usingEqStrings(
                    "MicroMsg.MsgInfoStorageLogic",
                    "summerdel deleteMsgByTalker[%s] stack[%s]",
                    "summerdel deleteMsgByTalker is null or empty",
                    "AsyncDeleteMessageStage1");
            find.matcher(matcher);
            for (MethodData methodData : dexKit.findMethod(find)) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isMessageClearBatchMethod(method) || batchCandidates.contains(method)) continue;
                    KavaReflector.accessible(method);
                    batchCandidates.add(method);
                } catch (Throwable ignored) {}
            }
            Method batch = batchCandidates.size() == 1 ? batchCandidates.get(0) : null;
            if (batch != null) {
                Class<?> callbackType = batch.getParameterTypes()[1];
                List<Method> singleCandidates = new ArrayList<>();
                for (Method method : KavaReflector.declaredMethods(batch.getDeclaringClass())) {
                    if (!isMessageClearByTalkerMethod(method, batch)) continue;
                    if (method.getParameterTypes()[1] != callbackType) continue;
                    KavaReflector.accessible(method);
                    singleCandidates.add(method);
                }
                if (singleCandidates.size() == 1) {
                    messageClearBatchMethod = batch;
                    messageClearByTalkerMethod = singleCandidates.get(0);
                    logDetail("原生消息清理API: single=" + methodName(messageClearByTalkerMethod)
                            + " batch=" + methodName(messageClearBatchMethod));
                    return;
                }
                h.Hchat.utils.HLog.e(TAG + " 原生单会话消息清理API定位失败: candidates="
                        + singleCandidates.size() + " key=" + shortKey(runtimeCacheKey));
            }
            h.Hchat.utils.HLog.e(TAG + " 原生批量消息清理API定位失败: candidates="
                    + batchCandidates.size() + " key=" + shortKey(runtimeCacheKey));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveMessageClearApi 失败: " + e.getMessage(), e);
        }
    }

    private boolean isMessageClearBatchMethod(Method method) {
        if (method == null || !KavaReflector.isStatic(method) || method.getReturnType() != void.class) {
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 2 && params.length != 3) return false;
        if (!java.util.List.class.isAssignableFrom(params[0]) || !params[1].isInterface()) return false;
        return params.length == 2 || params[2] == long.class;
    }

    private boolean isMessageClearByTalkerMethod(Method method, Method batchMethod) {
        if (method == null || batchMethod == null || !KavaReflector.isStatic(method)
                || method.getReturnType() != void.class
                || method.getDeclaringClass() != batchMethod.getDeclaringClass()) {
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        Class<?>[] batchParams = batchMethod.getParameterTypes();
        return (params.length == 2 || params.length == 3)
                && params[0] == String.class
                && params[1] == batchParams[1]
                && (params.length == 2 || params[2] == long.class);
    }

    public void resolveConversationMuteApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (!isServiceGetterMethod(serviceGetterMethod)) {
                resolveServiceManagerApi();
            }
            if (!isContactMuteMethod(contactMuteEnableMethod)) {
                contactMuteEnableMethod = findUniqueMethodUsingString(
                        "setMute contact invalid username");
            }
            if (!isContactMuteMethod(contactMuteDisableMethod)) {
                contactMuteDisableMethod = findUniqueMethodUsingString(
                        "unSetMute contact invalid username");
            }
            if (!isContactMuteStateMethod(contactMuteStateMethod, contactMuteEnableMethod)) {
                contactMuteStateMethod = findContactMuteStateMethod();
            }
            if (!isContactStorageLookupApiReady()) {
                resolveContactStorageLookupApi();
            }
            if (!isChatroomMuteApiReady()) {
                resolveChatroomMuteApi();
            }
            logDetail("原生免打扰API: state=" + methodName(contactMuteStateMethod)
                    + " enable=" + methodName(contactMuteEnableMethod)
                    + " disable=" + methodName(contactMuteDisableMethod)
                    + " contactStorageGetter=" + methodName(contactStorageGetterMethod)
                    + " contactStorageQuery=" + methodName(contactStorageQueryMethod)
                    + " roomGetter=" + methodName(chatroomMuteServiceGetterMethod)
                    + " roomBuild=" + methodName(chatroomMuteBuildMethod)
                    + " roomSubmit=" + methodName(chatroomMuteSubmitMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveConversationMuteApi 失败: " + e.getMessage(), e);
        }
    }

    public boolean isPrivateConversationMuteApiReady() {
        return isServiceGetterMethod(serviceGetterMethod)
                && isContactMuteMethod(contactMuteEnableMethod)
                && isContactMuteMethod(contactMuteDisableMethod)
                && isContactMuteStateMethod(contactMuteStateMethod, contactMuteEnableMethod)
                && isContactStorageLookupApiReady();
    }

    private Method findUniqueMethodUsingString(String anchor) {
        List<Method> candidates = new ArrayList<>();
        for (MethodData data : dexKit.findMethod(mkMethodUsingStrings(anchor))) {
            try {
                Method method = data.getMethodInstance(classLoader);
                if (candidates.contains(method)) continue;
                if (!isContactMuteMethod(method)) continue;
                KavaReflector.accessible(method);
                candidates.add(method);
            } catch (Throwable ignored) {}
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private boolean isContactMuteMethod(Method method) {
        if (method == null || !KavaReflector.isStatic(method)
                || method.getReturnType() != void.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 2
                && !params[0].isPrimitive()
                && params[1] == boolean.class;
    }

    private Method findContactMuteStateMethod() {
        Method mute = contactMuteEnableMethod;
        if (!isContactMuteMethod(mute)) return null;
        List<Method> candidates = new ArrayList<>();
        for (MethodData entry : dexKit.findMethod(mkMethodUsingStrings("room_notify_new_msg"))) {
            if (!"com.tencent.mm.ui.SingleChatInfoUI".equals(entry.getClassName())) continue;
            for (MethodData invokeData : entry.getInvokes()) {
                try {
                    Method method = invokeData.getMethodInstance(classLoader);
                    if (!isContactMuteStateMethod(method, mute) || candidates.contains(method)) continue;
                    KavaReflector.accessible(method);
                    candidates.add(method);
                } catch (Throwable ignored) {}
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private boolean isContactMuteStateMethod(Method method, Method muteMethod) {
        if (method == null || !isContactMuteMethod(muteMethod)
                || !KavaReflector.isStatic(method)
                || method.getReturnType() != boolean.class
                || method.getDeclaringClass() != muteMethod.getDeclaringClass()) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 1 && params[0] == muteMethod.getParameterTypes()[0];
    }

    private void resolveContactStorageLookupApi() {
        contactStorageGetterMethod = null;
        contactStorageQueryMethod = null;
        Method mute = contactMuteEnableMethod;
        if (!isContactMuteMethod(mute)) return;

        Class<?> contactType = mute.getParameterTypes()[0];
        List<Method> invokes = new ArrayList<>();
        for (MethodData data : dexKit.findMethod(
                mkMethodUsingStrings("setMute contact invalid username"))) {
            try {
                Method candidate = data.getMethodInstance(classLoader);
                if (!mute.equals(candidate)) continue;
                for (MethodData invokeData : data.getInvokes()) {
                    Method invoked = invokeData.getMethodInstance(classLoader);
                    if (!invokes.contains(invoked)) invokes.add(invoked);
                }
            } catch (Throwable ignored) {}
        }

        List<Method[]> pairs = new ArrayList<>();
        for (Method query : invokes) {
            if (!isContactStorageQueryMethod(query, contactType)) continue;
            for (Method getter : invokes) {
                if (!isContactStorageGetterMethod(getter, query)) continue;
                pairs.add(new Method[]{getter, query});
            }
        }
        if (pairs.size() == 1) {
            contactStorageGetterMethod = KavaReflector.accessible(pairs.get(0)[0]);
            contactStorageQueryMethod = KavaReflector.accessible(pairs.get(0)[1]);
            return;
        }
        h.Hchat.utils.HLog.e(TAG + " 原生联系人查询API定位失败: pairs="
                + pairs.size() + " invokes=" + invokes.size()
                + " key=" + shortKey(runtimeCacheKey));
    }

    private boolean isContactStorageLookupApiReady() {
        if (!isContactMuteMethod(contactMuteEnableMethod)) return false;
        Class<?> contactType = contactMuteEnableMethod.getParameterTypes()[0];
        return isContactStorageQueryMethod(contactStorageQueryMethod, contactType)
                && isContactStorageGetterMethod(contactStorageGetterMethod, contactStorageQueryMethod);
    }

    private boolean isContactStorageQueryMethod(Method method, Class<?> contactType) {
        if (method == null || contactType == null || KavaReflector.isStatic(method)
                || method.getReturnType() != contactType) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 2
                && params[0] == String.class
                && params[1] == boolean.class;
    }

    private boolean isContactStorageGetterMethod(Method method, Method query) {
        if (method == null || query == null || KavaReflector.isStatic(method)
                || method.getParameterTypes().length != 0) return false;
        Class<?> returnType = method.getReturnType();
        return returnType != void.class
                && !returnType.isPrimitive()
                && query.getDeclaringClass().isAssignableFrom(returnType);
    }

    private void resolveChatroomMuteApi() {
        chatroomMuteServiceGetterMethod = null;
        chatroomMuteBuildMethod = null;
        chatroomMuteSubmitMethod = null;

        List<MethodData> entries = dexKit.findMethod(mkMethodUsingStrings(
                "ChatroomMuteRefine OpModChatRoomNotify roomId = %s, notifyMsg = %d, defaultNeedPushFlag=%d"));
        if (entries.isEmpty()) {
            entries = dexKit.findMethod(mkMethodUsingStrings("room_notify_new_msg"));
        }
        for (MethodData entry : entries) {
            List<Method> invokes = new ArrayList<>();
            for (MethodData invokeData : entry.getInvokes()) {
                try {
                    Method invoked = invokeData.getMethodInstance(classLoader);
                    if (!invokes.contains(invoked)) invokes.add(invoked);
                } catch (Throwable ignored) {}
            }
            for (Method build : invokes) {
                if (!isChatroomMuteBuildMethod(build)) continue;
                Method getter = null;
                Method submit = null;
                for (Method invoked : invokes) {
                    if (isChatroomMuteServiceGetterMethod(invoked, build)) getter = invoked;
                    if (isChatroomMuteSubmitMethod(invoked, build)) submit = invoked;
                }
                if (getter == null || submit == null) continue;
                KavaReflector.accessible(getter);
                KavaReflector.accessible(build);
                KavaReflector.accessible(submit);
                chatroomMuteServiceGetterMethod = getter;
                chatroomMuteBuildMethod = build;
                chatroomMuteSubmitMethod = submit;
                return;
            }
        }
        h.Hchat.utils.HLog.e(TAG + " 原生群聊免打扰API定位失败: entries="
                + entries.size() + " key=" + shortKey(runtimeCacheKey));
    }

    private boolean isChatroomMuteApiReady() {
        return isChatroomMuteBuildMethod(chatroomMuteBuildMethod)
                && isChatroomMuteServiceGetterMethod(
                        chatroomMuteServiceGetterMethod, chatroomMuteBuildMethod)
                && isChatroomMuteSubmitMethod(chatroomMuteSubmitMethod, chatroomMuteBuildMethod);
    }

    private boolean isChatroomMuteBuildMethod(Method method) {
        if (method == null || KavaReflector.isStatic(method)
                || method.getReturnType() == void.class
                || method.getReturnType().isPrimitive()
                || !method.getReturnType().getName().startsWith("com.tencent.mm.roomsdk.model.factory.")) {
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        return (params.length == 2 || params.length == 3)
                && params[0] == String.class
                && params[1] == int.class
                && (params.length == 2 || params[2] == int.class);
    }

    private boolean isChatroomMuteServiceGetterMethod(Method method, Method build) {
        if (method == null || build == null || KavaReflector.isStatic(method)) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 1
                && params[0] == String.class
                && method.getReturnType() == build.getDeclaringClass();
    }

    private boolean isChatroomMuteSubmitMethod(Method method, Method build) {
        return method != null
                && build != null
                && !KavaReflector.isStatic(method)
                && method.getReturnType() == void.class
                && method.getParameterTypes().length == 0
                && method.getDeclaringClass().isAssignableFrom(build.getReturnType());
    }

    public void resolveLocalMessageApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (hasLocalMessageApi() && localMessageCreateTimeMethod != null) return;
            int candidateCount = resolveLocalMessageApiBySignature();
            logDetail("本地消息API: insert=" + methodName(localMessageInsertMethod)
                    + " system=" + methodName(localSystemMessageMethod)
                    + " createTime=" + methodName(localMessageCreateTimeMethod)
                    + " msg=" + (localMessageClass != null ? localMessageClass.getName() : "null")
                    + " ctor=" + (localMessageCtor != null ? localMessageCtor.getParameterTypes().length : -1));
            if (!hasLocalMessageApi()) {
                h.Hchat.utils.HLog.e(TAG + " 本地消息API未找到: candidates=" + candidateCount
                        + " key=" + shortKey(runtimeCacheKey));
            }
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveLocalMessageApi 失败: " + e.getMessage(), e);
        }
    }

    private int resolveLocalMessageApiBySignature() {
        int count = 0;
        resolveLocalSystemMessageMethod();
        try {
            if (localMessageInsertMethod != null && localMessageCreateTimeMethod == null) {
                localMessageCreateTimeMethod = findLocalMessageCreateTimeMethod(localMessageInsertMethod.getDeclaringClass());
            }
            if (localMessageInsertMethod != null && localMessageCreateTimeMethod != null) {
                return count;
            }
            FindMethod fm = new FindMethod();
            MethodMatcher mm = new MethodMatcher();
            mm.name("x");
            mm.returnType(long.class);
            mm.paramCount(1);
            fm.matcher(mm);
            List<MethodData> methods = dexKit.findMethod(fm);
            count = methods != null ? methods.size() : 0;
            if (methods == null) return 0;
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isLocalMessageInsertMethod(method)) continue;
                    setLocalMessageApi(method);
                    break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return count;
    }

    private void resolveLocalSystemMessageMethod() {
        if (localSystemMessageMethod != null) return;
        try {
            List<MethodData> methods = dexKit.findMethod(
                    mkMethodUsingStrings(
                            "will insert sysmsg from:",
                            "content null, cannot to insert sysmsg!",
                            "failed to insert sysmsg",
                            "sysmsg inserted"));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isLocalSystemMessageMethod(method)) continue;
                    localSystemMessageMethod = KavaReflector.accessible(method);
                    break;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveLocalSystemMessageMethod 失败: " + e.getMessage(), e);
        }
    }

    private boolean isLocalSystemMessageMethod(Method method) {
        if (method == null || method.getReturnType() != void.class) return false;
        if (KavaReflector.isStatic(method)) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 3
                && params[0] == String.class
                && params[1] == String.class
                && params[2] == String.class;
    }

    private void setLocalMessageApi(Method method) {
        KavaReflector.accessible(method);
        localMessageInsertMethod = method;
        localMessageClass = method.getParameterTypes()[0];
        localMessageCtor = findLocalMessageConstructor(localMessageClass);
        localMessageCreateTimeMethod = findLocalMessageCreateTimeMethod(method.getDeclaringClass());
    }

    public void resolveGroupMemberDisplayName() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (groupMemberDisplayNameMethod != null) return;
            List<MethodData> methods = dexKit.findMethod(
                    mkMethodUsingStrings("ChatroomDisplayNameCache"));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (method.getReturnType() != String.class) continue;
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length != 2 || params[0] != String.class || params[1] != String.class) {
                        continue;
                    }
                    KavaReflector.accessible(method);
                    groupMemberDisplayNameMethod = method;
                    break;
                } catch (Throwable ignored) {}
            }
            logDetail("群成员昵称方法: "
                    + (groupMemberDisplayNameMethod != null
                    ? groupMemberDisplayNameMethod.getDeclaringClass().getName()
                    + "#" + groupMemberDisplayNameMethod.getName()
                    : "null"));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveGroupMemberDisplayName 失败: " + e.getMessage(), e);
        }
    }

    public void resolveInviteChatroomMemberApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (inviteChatroomMemberCtor == null) {
                inviteChatroomMemberCtor = findInviteChatroomMemberCtor(inviteChatroomMemberClass);
            }
            if (inviteChatroomMemberCtor != null) return;
            List<Class<?>> candidates = new ArrayList<>();
            collectSendTextClassCandidates(candidates, 20,
                    new String[][]{
                            {"/cgi-bin/micromsg-bin/invitechatroommember"},
                            {"MicroMsg.NetSceneInviteChatRoomMember"},
                            {"invitechatroommember"}
                    });
            collectSendTextMethodOwnerCandidates(candidates, 20,
                    new String[][]{
                            {"/cgi-bin/micromsg-bin/invitechatroommember"},
                            {"MicroMsg.NetSceneInviteChatRoomMember"}
                    });
            for (Class<?> candidate : candidates) {
                Constructor<?> ctor = findInviteChatroomMemberCtor(candidate);
                if (ctor == null) continue;
                inviteChatroomMemberClass = candidate;
                inviteChatroomMemberCtor = ctor;
                break;
            }
            logDetail("邀请群成员API: "
                    + (inviteChatroomMemberClass != null ? inviteChatroomMemberClass.getName() : "null"));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveInviteChatroomMemberApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveAddChatroomMemberApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (addChatroomMemberCtor == null) {
                addChatroomMemberCtor = findAddChatroomMemberCtor(addChatroomMemberClass);
            }
            if (addChatroomMemberCtor != null) return;
            List<Class<?>> candidates = new ArrayList<>();
            collectSendTextClassCandidates(candidates, 20,
                    new String[][]{
                            {"/cgi-bin/micromsg-bin/addchatroommember"},
                            {"MicroMsg.NetSceneAddChatRoomMember"},
                            {"addchatroommember"}
                    });
            collectSendTextMethodOwnerCandidates(candidates, 20,
                    new String[][]{
                            {"/cgi-bin/micromsg-bin/addchatroommember"},
                            {"MicroMsg.NetSceneAddChatRoomMember"}
                    });
            for (Class<?> candidate : candidates) {
                Constructor<?> ctor = findAddChatroomMemberCtor(candidate);
                if (ctor == null) continue;
                addChatroomMemberClass = candidate;
                addChatroomMemberCtor = ctor;
                break;
            }
            logDetail("添加群成员API: "
                    + (addChatroomMemberClass != null ? addChatroomMemberClass.getName() : "null"));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveAddChatroomMemberApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveDelChatroomMemberApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (delChatroomMemberCtor == null) {
                delChatroomMemberCtor = findDelChatroomMemberCtor(delChatroomMemberClass);
            }
            if (delChatroomMemberCtor != null) return;
            List<Class<?>> candidates = new ArrayList<>();
            collectSendTextClassCandidates(candidates, 20,
                    new String[][]{
                            {"/cgi-bin/micromsg-bin/delchatroommember"},
                            {"delchatroommember"}
                    });
            collectSendTextMethodOwnerCandidates(candidates, 20,
                    new String[][]{
                            {"/cgi-bin/micromsg-bin/delchatroommember"}
                    });
            for (Class<?> candidate : candidates) {
                Constructor<?> ctor = findDelChatroomMemberCtor(candidate);
                if (ctor == null) continue;
                delChatroomMemberClass = candidate;
                delChatroomMemberCtor = ctor;
                break;
            }
            logDetail("移除群成员API: "
                    + (delChatroomMemberClass != null ? delChatroomMemberClass.getName() : "null"));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveDelChatroomMemberApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveRevokeMsgApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (revokeMsgCtor == null) {
                revokeMsgCtor = findRevokeMsgCtor(revokeMsgClass);
            }
            if (revokeMsgCtor != null) return;
            List<Class<?>> candidates = new ArrayList<>();
            collectSendTextClassCandidates(candidates, 20,
                    new String[][]{
                            {"/cgi-bin/micromsg-bin/revokemsg", "MicroMsg.NetSceneRevokeMsg"},
                            {"NetSceneRevokeMsg"}
                    });
            collectSendTextMethodOwnerCandidates(candidates, 20,
                    new String[][]{
                            {"/cgi-bin/micromsg-bin/revokemsg", "MicroMsg.NetSceneRevokeMsg"},
                            {"NetSceneRevokeMsg"}
                    });
            for (Class<?> candidate : candidates) {
                Constructor<?> ctor = findRevokeMsgCtor(candidate);
                if (ctor == null) continue;
                revokeMsgClass = candidate;
                revokeMsgCtor = ctor;
                break;
            }
            logDetail("撤回消息API: "
                    + (revokeMsgClass != null ? revokeMsgClass.getName() : "null"));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveRevokeMsgApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveUploadDeviceStepApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (uploadDeviceStepCtor == null) {
                uploadDeviceStepCtor = findUploadDeviceStepCtor(uploadDeviceStepClass);
            }
            if (uploadDeviceStepCtor != null) return;
            List<Class<?>> candidates = new ArrayList<>();
            collectSendTextClassCandidates(candidates, 20,
                    new String[][]{
                            {"/cgi-bin/mmoc-bin/hardware/uploaddevicestep", "MicroMsg.Sport.NetSceneUploadDeviceStep"},
                            {"NetSceneUploadDeviceStep"}
                    });
            collectSendTextMethodOwnerCandidates(candidates, 20,
                    new String[][]{
                            {"/cgi-bin/mmoc-bin/hardware/uploaddevicestep", "MicroMsg.Sport.NetSceneUploadDeviceStep"},
                            {"NetSceneUploadDeviceStep"}
                    });
            for (Class<?> candidate : candidates) {
                Constructor<?> ctor = findUploadDeviceStepCtor(candidate);
                if (ctor == null) continue;
                uploadDeviceStepClass = candidate;
                uploadDeviceStepCtor = ctor;
                break;
            }
            logDetail("上传步数API: "
                    + (uploadDeviceStepClass != null ? uploadDeviceStepClass.getName() : "null"));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveUploadDeviceStepApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveContactLabelNetworkApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (addContactLabelCtorString == null) {
                addContactLabelCtorString = findCtorByExactTypes(addContactLabelClass, String.class);
            }
            if (addContactLabelCtorList == null) {
                addContactLabelCtorList = findCtorByExactTypes(addContactLabelClass, List.class);
            }
            if (modifyContactLabelListCtor == null) {
                modifyContactLabelListCtor = findCtorByExactTypes(modifyContactLabelListClass, java.util.LinkedList.class);
            }
            if (addContactLabelCtorString == null || addContactLabelCtorList == null) {
                List<Class<?>> candidates = new ArrayList<>();
                collectSendTextClassCandidates(candidates, 20,
                        new String[][]{
                                {"/cgi-bin/micromsg-bin/addcontactlabel", "MicroMsg.Label.NetSceneAddContactLabel"},
                                {"NetSceneAddContactLabel"},
                                {"addcontactlabel"}
                        });
                collectSendTextMethodOwnerCandidates(candidates, 20,
                        new String[][]{
                                {"/cgi-bin/micromsg-bin/addcontactlabel", "MicroMsg.Label.NetSceneAddContactLabel"},
                                {"NetSceneAddContactLabel"}
                        });
                for (Class<?> candidate : candidates) {
                    Constructor<?> stringCtor = findCtorByExactTypes(candidate, String.class);
                    Constructor<?> listCtor = findCtorByExactTypes(candidate, List.class);
                    if (stringCtor == null && listCtor == null) continue;
                    addContactLabelClass = candidate;
                    if (stringCtor != null) addContactLabelCtorString = stringCtor;
                    if (listCtor != null) addContactLabelCtorList = listCtor;
                    break;
                }
            }
            if (modifyContactLabelListCtor == null) {
                List<Class<?>> candidates = new ArrayList<>();
                collectSendTextClassCandidates(candidates, 20,
                        new String[][]{
                                {"/cgi-bin/micromsg-bin/modifycontactlabellist", "MicroMsg.Label.NetSceneModifyContactLabelList"},
                                {"NetSceneModifyContactLabelList"},
                                {"modifycontactlabellist"}
                        });
                collectSendTextMethodOwnerCandidates(candidates, 20,
                        new String[][]{
                                {"/cgi-bin/micromsg-bin/modifycontactlabellist", "MicroMsg.Label.NetSceneModifyContactLabelList"},
                                {"NetSceneModifyContactLabelList"}
                        });
                for (Class<?> candidate : candidates) {
                    Constructor<?> ctor = findCtorByExactTypes(candidate, java.util.LinkedList.class);
                    if (ctor == null) continue;
                    modifyContactLabelListClass = candidate;
                    modifyContactLabelListCtor = ctor;
                    break;
                }
            }
            logDetail("联系人标签网络API: add="
                    + (addContactLabelClass != null ? addContactLabelClass.getName() : "null")
                    + " modify="
                    + (modifyContactLabelListClass != null ? modifyContactLabelListClass.getName() : "null"));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveContactLabelNetworkApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveSnsUploadApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (hasSnsUploadApi() && snsAddVideoMethod != null && snsShareAppMsgMethod != null) {
                return;
            }

            if (!isSnsUploadPackHelperClass(snsUploadPackHelperClass)) {
                List<Class<?>> candidates = new ArrayList<>();
                collectSendTextClassCandidates(candidates, 20,
                        new String[][]{
                                {"MicroMsg.UploadPackHelper", "setContentDes"},
                                {"addImageMediaObjByPath", "setUploadList"},
                                {"setSdkId", "setSdkAppName"}
                        });
                collectSendTextMethodOwnerCandidates(candidates, 20,
                        new String[][]{
                                {"MicroMsg.UploadPackHelper", "setContentDes"},
                                {"addImageMediaObjByPath", "setUploadList"},
                                {"setSdkId", "setSdkAppName"}
                        });
                for (Class<?> candidate : candidates) {
                    if (!isSnsUploadPackHelperClass(candidate)) continue;
                    snsUploadPackHelperClass = candidate;
                    break;
                }
            }

            if (!isSnsUploadManagerClass(snsUploadManagerClass)) {
                List<Class<?>> candidates = new ArrayList<>();
                collectSendTextClassCandidates(candidates, 20,
                        new String[][]{
                                {"MicroMsg.UploadManager", "checkPostInUI"},
                                {"checkTLE snsinfo localId it time limit"},
                                {"getSnsUploadManager"}
                        });
                collectSendTextMethodOwnerCandidates(candidates, 20,
                        new String[][]{
                                {"MicroMsg.UploadManager", "checkPostInUI"},
                                {"checkTLE snsinfo localId it time limit"},
                                {"getSnsUploadManager"}
                        });
                for (Class<?> candidate : candidates) {
                    if (!isSnsUploadManagerClass(candidate)) continue;
                    snsUploadManagerClass = candidate;
                    break;
                }
            }

            resolveSnsUploadMethods();

            logDetail("朋友圈发布API: helper="
                    + (snsUploadPackHelperClass != null ? snsUploadPackHelperClass.getName() : "null")
                    + " manager="
                    + (snsUploadManagerClass != null ? snsUploadManagerClass.getName() : "null")
                    + " getter=" + methodName(snsUploadManagerGetterMethod)
                    + " content=" + methodName(snsSetContentMethod)
                    + " addImage=" + methodName(snsAddImageMethod)
                    + " addVideo=" + methodName(snsAddVideoMethod)
                    + " commit=" + methodName(snsCommitMethod)
                    + " shareAppMsg=" + methodName(snsShareAppMsgMethod)
                    + " check=" + methodName(snsUploadCheckMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveSnsUploadApi 失败: " + e.getMessage(), e);
        }
    }

    private void resolveSnsUploadMethods() {
        if (snsUploadPackHelperClass != null) {
            if (snsSetContentMethod == null) {
                snsSetContentMethod = findSnsHelperChainMethod("setContentDes");
            }
            if (snsSetSdkIdMethod == null) {
                snsSetSdkIdMethod = findSnsHelperChainMethod("setSdkId");
            }
            if (snsSetSdkAppNameMethod == null) {
                snsSetSdkAppNameMethod = findSnsHelperChainMethod("setSdkAppName");
            }
            if (snsAddImageMethod == null) {
                snsAddImageMethod = findSnsAddImageMethod(snsUploadPackHelperClass);
            }
            if (snsAddVideoMethod == null) {
                snsAddVideoMethod = findSnsAddVideoMethod(snsUploadPackHelperClass);
            }
            if (snsCommitMethod == null) {
                snsCommitMethod = findSnsCommitMethod(snsUploadPackHelperClass);
            }
        }
        if (snsUploadManagerClass != null) {
            if (snsUploadManagerGetterMethod == null) {
                snsUploadManagerGetterMethod = findSnsUploadManagerGetter(snsUploadManagerClass);
            }
            if (snsShareAppMsgMethod == null) {
                snsShareAppMsgMethod = findSnsShareAppMsgMethod(snsUploadManagerClass);
            }
            if (snsUploadCheckMethod == null) {
                snsUploadCheckMethod = findSnsUploadCheckMethod(snsUploadManagerClass);
            }
        }
    }

    public void resolveChatPageApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (chatPageStartMethod != null
                    && chatPageFragmentEnterMethod != null
                    && chatPageFragmentExitMethod != null) return;

            if (chatPageStartMethod == null) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("try startChatting, ishow:%b userName:%s needAnim:%b"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isChatPageStartMethod(method)) continue;
                        KavaReflector.accessible(method);
                        chatPageStartMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            if (chatPageFragmentEnterMethod == null) {
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("onEnterBegin", "Chat_User"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isNoArgVoidMethod(method)) continue;
                        KavaReflector.accessible(method);
                        chatPageFragmentEnterMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            if (chatPageFragmentExitMethod == null && chatPageFragmentEnterMethod != null) {
                Class<?> fragmentClass = chatPageFragmentEnterMethod.getDeclaringClass();
                List<MethodData> methods = dexKit.findMethod(
                        mkMethodUsingStrings("onExitBegin"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isNoArgVoidMethod(method)
                                || method.getDeclaringClass() != fragmentClass) continue;
                        KavaReflector.accessible(method);
                        chatPageFragmentExitMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }

            logDetail("聊天页API: start="
                    + methodName(chatPageStartMethod)
                    + " fragmentEnter=" + methodName(chatPageFragmentEnterMethod)
                    + " fragmentExit=" + methodName(chatPageFragmentExitMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveChatPageApi 失败: " + e.getMessage(), e);
        }
    }

    public void resolveScriptSendHookApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (chatFooterSendClickMethod != null) return;
            List<MethodData> methods = dexKit.findMethod(
                    mkMethodUsingStringsAndName("onClick",
                            "MicroMsg.ChatFooter",
                            "send msg onClick",
                            "paste clip board to send"));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!isChatFooterSendClickMethod(method)) continue;
                    KavaReflector.accessible(method);
                    chatFooterSendClickMethod = method;
                    break;
                } catch (Throwable ignored) {}
            }
            if (chatFooterSendClickMethod == null) {
                methods = dexKit.findMethod(
                        mkMethodUsingStringsAndName("onClick", "send msg onClick"));
                for (MethodData methodData : methods) {
                    try {
                        Method method = methodData.getMethodInstance(classLoader);
                        if (!isChatFooterSendClickMethod(method)) continue;
                        KavaReflector.accessible(method);
                        chatFooterSendClickMethod = method;
                        break;
                    } catch (Throwable ignored) {}
                }
            }
            logDetail("脚本发送按钮API: click=" + methodName(chatFooterSendClickMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveScriptSendHookApi 失败: " + e.getMessage(), e);
        }
    }

    // ============ DexKit 结果缓存 ============
    private boolean ensureSnapshotLoaded() {
        if (resolvedAll) return true;
        if (cacheLoadAttempted) return false;
        return loadCache();
    }

    private boolean loadCache() {
        cacheLoadAttempted = true;
        if (cachePrefs == null || runtimeCacheKey.length() == 0) return false;
        try {
            String savedKey = cachePrefs.getString(CACHE_KEY, "");
            if (!runtimeCacheKey.equals(savedKey)
                    && !DexCacheIdentity.sameRuntime(runtimeCacheKey, savedKey)) {
                resetCacheForRuntimeKey();
                return false;
            }
            if (!CACHE_SNAPSHOT_VERSION.equals(
                    cachePrefs.getString(CACHE_SNAPSHOT_VERSION_KEY, ""))) return false;

            addMsgClasses = loadClassList("addMsgClasses");
            receiveLuckyMoneyClass = loadClass("receiveLuckyMoneyClass");
            receiveLuckyMoneyUnionClass = loadClass("receiveLuckyMoneyUnionClass");
            openLuckyMoneyClass = loadClass("openLuckyMoneyClass");
            openLuckyMoneyUnionClass = loadClass("openLuckyMoneyUnionClass");
            String savedUnionLocatorVersion = cachePrefs.getString(
                    RED_PACKET_UNION_LOCATOR_VERSION_KEY, "");
            unionReceiveCtor = findCtorByArgCount(receiveLuckyMoneyUnionClass, 6);
            unionOpenCtor10 = findCtorByArgCount(openLuckyMoneyUnionClass, 10);
            unionOpenCtor9 = findCtorByArgCount(openLuckyMoneyUnionClass, 9);
            receiveUnionLocatorResolved = RED_PACKET_UNION_LOCATOR_VERSION.equals(savedUnionLocatorVersion)
                    && receiveLuckyMoneyUnionClass != null && unionReceiveCtor != null;
            openUnionLocatorResolved = RED_PACKET_UNION_LOCATOR_VERSION.equals(savedUnionLocatorVersion)
                    && openLuckyMoneyUnionClass != null
                    && (unionOpenCtor10 != null || unionOpenCtor9 != null);
            netQueueClass = loadClass("netQueueClass");
            netQueueCandidateClasses = loadClassList("netQueueCandidateClasses");
            networkQueueLocatorResolved = NETWORK_QUEUE_LOCATOR_VERSION.equals(
                    cachePrefs.getString(NETWORK_QUEUE_LOCATOR_VERSION_KEY, ""))
                    && netQueueClass != null;
            packetBaseClasses = loadClassList("packetBaseClasses");
            packetQueueClasses = loadClassList("packetQueueClasses");
            fakePacketClasses = loadClassList("fakePacketClasses");
            protobufBaseClass = loadClass("protobufBaseClass");
            protobufRawReqClass = loadClass("protobufRawReqClass");
            protobufGenericRespClass = loadClass("protobufGenericRespClass");
            protobufConfigBuilderClass = loadClass("protobufConfigBuilderClass");
            protobufReqRespClass = loadClass("protobufReqRespClass");
            protobufCallbackClass = loadClass("protobufCallbackClass");
            protobufNewSendMsgReqClass = loadClass("protobufNewSendMsgReqClass");
            protobufOplogReqClass = loadClass("protobufOplogReqClass");
            protobufOnGYNetEndClass = loadClass("protobufOnGYNetEndClass");
            protobufNetSceneBaseClass = loadClass("protobufNetSceneBaseClass");
            protobufStaticDispatchMethod = loadMethod("protobufStaticDispatchMethod");
            protobufSceneEndMethods = loadMethodList("protobufSceneEndMethods");
            wishWxHbClass = loadClass("wishWxHbClass");
            ecsGiftTaskClass = loadClass("ecsGiftTaskClass");
            ecsGiftServiceClass = loadClass("ecsGiftServiceClass");
            ecsGiftMsgClass = loadClass("ecsGiftMsgClass");
            msgDescTextMethods = loadMethodList("msgDescTextMethods");
            wishLocatorResolved = cachePrefs.getBoolean(LOCATOR_WISH_COMPLETE, false);
            packetCompatLocatorResolved = cachePrefs.getBoolean(LOCATOR_PACKET_COMPLETE, false);
            ecsGiftLocatorResolved = cachePrefs.getBoolean(LOCATOR_ECS_COMPLETE, false);
            msgDescLocatorResolved = cachePrefs.getBoolean(LOCATOR_MSG_DESC_COMPLETE, false);
            protobufLocatorResolved = cachePrefs.getBoolean(LOCATOR_PROTOBUF_COMPLETE, false);
            sendTextMsgClass = loadClass("sendTextMsgClass");
            serviceGetterMethod = loadMethod("serviceGetterMethod");
            getContactAddMethods = loadMethodList("getContactAddMethods");
            getContactServiceGetters = loadMethodList("getContactServiceGetters");
            sendImageMethod = loadMethod("sendImageMethod");
            sendImageAsyncParamsClass = loadClass("sendImageAsyncParamsClass");
            sendImageCrossParamsClass = loadClass("sendImageCrossParamsClass");
            sendImageAppInfoClass = loadClass("sendImageAppInfoClass");
            sendImageAsyncSubmitMethod = loadMethod("sendImageAsyncSubmitMethod");
            imageCdnTaskClass = loadClass("imageCdnTaskClass");
            imageCdnSubmitMethod = loadMethod("imageCdnSubmitMethod");
            imageCdnServiceGetterMethod = loadMethod("imageCdnServiceGetterMethod");
            marsCdnManagerClass = loadClass("marsCdnManagerClass");
            marsCdnDownloadRequestClass = loadClass("marsCdnDownloadRequestClass");
            marsCdnDownloadCallbackClass = loadClass("marsCdnDownloadCallbackClass");
            marsCdnStartDownloadMethod = loadMethod("marsCdnStartDownloadMethod");
            imageBestPathMethod = loadMethod("imageBestPathMethod");
            imageStorageGetterMethod = loadMethod("imageStorageGetterMethod");
            imageTokenPathMethod = loadMethod("imageTokenPathMethod");
            sendFileMethod = loadMethod("sendFileMethod");
            sendFileAttachDirMethod = loadMethod("sendFileAttachDirMethod");
            sendFileAttachPathMethod = loadMethod("sendFileAttachPathMethod");
            sendXmlAppMsgMethod = loadMethod("sendXmlAppMsgMethod");
            appMsgParseMethod = loadMethod("appMsgParseMethod");
            groupSolitairePluginClass = loadClass("groupSolitairePluginClass");
            groupSolitaireSendMethod = loadMethod("groupSolitaireSendMethod");
            localSystemMessageMethod = loadMethod("localSystemMessageMethod");
            localMessageInsertMethod = loadMethod("localMessageInsertMethod");
            localMessageCreateTimeMethod = loadMethod("localMessageCreateTimeMethod");
            localMessageClass = loadClass("localMessageClass");
            sendVideoMethod = loadMethod("sendVideoMethod");
            sendVideoTaskClass = loadClass("sendVideoTaskClass");
            videoPathMethod = loadMethod("videoPathMethod");
            videoPathOwnerGetterMethod = loadMethod("videoPathOwnerGetterMethod");
            videoInfoClass = loadClass("videoInfoClass");
            videoInfoByFileNameMethod = loadMethod("videoInfoByFileNameMethod");
            transferOperationClass = loadClass("transferOperationClass");
            transferQueryClass = loadClass("transferQueryClass");
            transferQueryResponseMethod = loadMethod("transferQueryResponseMethod");
            verifyUserClass = loadClass("verifyUserClass");
            contactCardXmlMethod = loadMethod("contactCardXmlMethod");
            patDisplayTemplateMethod = loadMethod("patDisplayTemplateMethod");
            patExtensionClass = loadClass("patExtensionClass");
            patCreatePairMethod = loadMethod("patCreatePairMethod");
            patSuffixMethod = loadMethod("patSuffixMethod");
            patCanSendMethod = loadMethod("patCanSendMethod");
            sendPatSceneClass = loadClass("sendPatSceneClass");
            voiceStartRecordMethod = loadMethod("voiceStartRecordMethod");
            voiceFullPathMethod = loadMethod("voiceFullPathMethod");
            voiceMessagePathMethod = loadMethod("voiceMessagePathMethod");
            if (!isVoiceMessagePathMethod(voiceMessagePathMethod)) voiceMessagePathMethod = null;
            voiceMessagePathLookupComplete = voiceMessagePathMethod != null
                    || (cachePrefs.getBoolean("voiceMessagePathLookupComplete", false)
                    && cachePrefs.getString("voiceMessagePathMethod", "").isEmpty());
            voiceFinishRecordMethod = loadMethod("voiceFinishRecordMethod");
            if (!isVoiceFinishRecordMethod(voiceFinishRecordMethod)) voiceFinishRecordMethod = null;
            voiceInfoQueryMethod = loadMethod("voiceInfoQueryMethod");
            voiceUploadClass = loadClass("voiceUploadClass");
            voiceUploadCdnCtor = findCtorByExactTypes(voiceUploadClass, String.class, boolean.class);
            voicePlaybackStartMethod = loadMethod("voicePlaybackStartMethod");
            voicePlaybackPauseMethod = loadMethod("voicePlaybackPauseMethod");
            voicePlaybackResumeMethod = loadMethod("voicePlaybackResumeMethod");
            voicePlaybackStopMethod = loadMethod("voicePlaybackStopMethod");
            emojiSendMethod = loadMethod("emojiSendMethod");
            emojiManagerSendMethod = loadMethod("emojiManagerSendMethod");
            emojiGetByMd5Method = loadMethod("emojiGetByMd5Method");
            emojiCreateInfoMethod = loadMethod("emojiCreateInfoMethod");
            emojiUpdateInfoMethod = loadMethod("emojiUpdateInfoMethod");
            emojiAccPathMethod = loadMethod("emojiAccPathMethod");
            emojiCheckGifMethod = loadMethod("emojiCheckGifMethod");
            emojiFilePathMethod = loadMethod("emojiFilePathMethod");
            emojiDecodeDataMethod = loadMethod("emojiDecodeDataMethod");
            emojiDecodeManagerGetterMethod = loadMethod("emojiDecodeManagerGetterMethod");
            favoriteItemClass = loadClass("favoriteItemClass");
            favoriteItemConvertFromCursorMethod = loadMethod("favoriteItemConvertFromCursorMethod");
            favoriteServiceClass = loadClass("favoriteServiceClass");
            favoriteServiceResolverMethod = loadMethod("favoriteServiceResolverMethod");
            favoriteStorageGetterMethod = loadMethod("favoriteStorageGetterMethod");
            favoriteListMethod = loadMethod("favoriteListMethod");
            favoriteListNextMethod = loadMethod("favoriteListNextMethod");
            favoriteListCursorMethod = loadMethod("favoriteListCursorMethod");
            favoriteGetMethod = loadMethod("favoriteGetMethod");
            favoriteSendMethod = loadMethod("favoriteSendMethod");
            mmKernelClass = loadClass("mmKernelClass");
            coreStorageClass = loadClass("coreStorageClass");
            configStorageClass = loadClass("configStorageClass");
            sqliteDbWrapperClass = loadClass("sqliteDbWrapperClass");
            conversationDeleteMethod = loadMethod("conversationDeleteMethod");
            messageClearByTalkerMethod = loadMethod("messageClearByTalkerMethod");
            messageClearBatchMethod = loadMethod("messageClearBatchMethod");
            contactMuteStateMethod = loadMethod("contactMuteStateMethod");
            contactMuteEnableMethod = loadMethod("contactMuteEnableMethod");
            contactMuteDisableMethod = loadMethod("contactMuteDisableMethod");
            contactStorageGetterMethod = loadMethod("contactStorageGetterMethod");
            contactStorageQueryMethod = loadMethod("contactStorageQueryMethod");
            chatroomMuteServiceGetterMethod = loadMethod("chatroomMuteServiceGetterMethod");
            chatroomMuteBuildMethod = loadMethod("chatroomMuteBuildMethod");
            chatroomMuteSubmitMethod = loadMethod("chatroomMuteSubmitMethod");

            receiveCtor = findFirstCtorByArgCounts(receiveLuckyMoneyClass, 7, 10, 8);
            unionReceiveCtor = findCtorByArgCount(receiveLuckyMoneyUnionClass, 6);
            openCtor10 = findCtorByArgCount(openLuckyMoneyClass, 10);
            openCtor9 = findCtorByArgCount(openLuckyMoneyClass, 9);
            openCtor8 = findCtorByArgCount(openLuckyMoneyClass, 8);
            unionOpenCtor10 = findCtorByArgCount(openLuckyMoneyUnionClass, 10);
            unionOpenCtor9 = findCtorByArgCount(openLuckyMoneyUnionClass, 9);
            wishWxHbCtor = findCtorByArgCount(wishWxHbClass, 4);
            sendTextMsgCtorLong = findCtorByExactTypes(
                    sendTextMsgClass, String.class, String.class, int.class, int.class, long.class);
            sendTextMsgCtorObject = findCtorByExactTypes(
                    sendTextMsgClass, String.class, String.class, int.class, int.class, Object.class);
            if (localMessageClass == null && localMessageInsertMethod != null
                    && localMessageInsertMethod.getParameterTypes().length == 1) {
                localMessageClass = localMessageInsertMethod.getParameterTypes()[0];
            }
            localMessageCtor = findLocalMessageConstructor(localMessageClass);
            if (localMessageCreateTimeMethod == null && localMessageInsertMethod != null) {
                localMessageCreateTimeMethod = findLocalMessageCreateTimeMethod(localMessageInsertMethod.getDeclaringClass());
            }
            voiceUploadCtor = findCtorByExactTypes(voiceUploadClass, String.class, int.class);
            voiceUploadCdnCtor = findCtorByExactTypes(voiceUploadClass, String.class, boolean.class);
            sendPatSceneCtor = findSendPatSceneCtor(sendPatSceneClass);
            coreStorageGetter = loadMethod("coreStorageGetter");
            groupMemberDisplayNameMethod = loadMethod("groupMemberDisplayNameMethod");
            addChatroomMemberClass = loadClass("addChatroomMemberClass");
            addChatroomMemberCtor = findAddChatroomMemberCtor(addChatroomMemberClass);
            inviteChatroomMemberClass = loadClass("inviteChatroomMemberClass");
            inviteChatroomMemberCtor = findInviteChatroomMemberCtor(inviteChatroomMemberClass);
            delChatroomMemberClass = loadClass("delChatroomMemberClass");
            delChatroomMemberCtor = findDelChatroomMemberCtor(delChatroomMemberClass);
            revokeMsgClass = loadClass("revokeMsgClass");
            revokeMsgCtor = findRevokeMsgCtor(revokeMsgClass);
            uploadDeviceStepClass = loadClass("uploadDeviceStepClass");
            uploadDeviceStepCtor = findUploadDeviceStepCtor(uploadDeviceStepClass);
            addContactLabelClass = loadClass("addContactLabelClass");
            addContactLabelCtorString = findCtorByExactTypes(addContactLabelClass, String.class);
            addContactLabelCtorList = findCtorByExactTypes(addContactLabelClass, List.class);
            modifyContactLabelListClass = loadClass("modifyContactLabelListClass");
            modifyContactLabelListCtor = findCtorByExactTypes(modifyContactLabelListClass, java.util.LinkedList.class);
            snsUploadPackHelperClass = loadClass("snsUploadPackHelperClass");
            snsUploadManagerClass = loadClass("snsUploadManagerClass");
            snsUploadManagerGetterMethod = loadMethod("snsUploadManagerGetterMethod");
            snsSetContentMethod = loadMethod("snsSetContentMethod");
            snsSetSdkIdMethod = loadMethod("snsSetSdkIdMethod");
            snsSetSdkAppNameMethod = loadMethod("snsSetSdkAppNameMethod");
            snsAddImageMethod = loadMethod("snsAddImageMethod");
            snsAddVideoMethod = loadMethod("snsAddVideoMethod");
            snsCommitMethod = loadMethod("snsCommitMethod");
            snsShareAppMsgMethod = loadMethod("snsShareAppMsgMethod");
            snsUploadCheckMethod = loadMethod("snsUploadCheckMethod");
            chatPageStartMethod = loadMethod("chatPageStartMethod");
            chatPageFragmentEnterMethod = loadMethod("chatPageFragmentEnterMethod");
            chatPageFragmentExitMethod = loadMethod("chatPageFragmentExitMethod");
            chatFooterSendClickMethod = loadMethod("chatFooterSendClickMethod");
            return !cacheDescriptorInvalid;
        } catch (Throwable e) {
            logDetail("读取缓存失败，重新解析: " + e.getMessage());
            return false;
        }
    }

    private void resetCacheForRuntimeKey() {
        try {
            cachePrefs.edit()
                    .clear()
                    .putString(CACHE_KEY, runtimeCacheKey)
                    .commit();
        } catch (Throwable ignored) {
        }
    }

    private boolean isSendImageMethod(Method method) {
        if (method == null || method.getReturnType() != void.class) return false;
        Class<?>[] params = method.getParameterTypes();
        if (params.length < 8) return false;
        return params[0] == Context.class
                && params[1] == String.class
                && params[2] == String.class
                && (params[3] == int.class || params[3] == Integer.class)
                && params[4] == String.class
                && params[5] == String.class;
    }

    private boolean isImageBestPathMethod(Method method) {
        if (method == null || KavaReflector.isStatic(method) || method.getReturnType() != String.class) {
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        return params.length == 1
                && params[0].getName().startsWith("com.tencent.mm.storage.");
    }

    private boolean isImageStorageGetter(Method method, Class<?> storageClass) {
        return method != null
                && storageClass != null
                && KavaReflector.isStatic(method)
                && method.getParameterTypes().length == 0
                && method.getReturnType() == storageClass;
    }

    private boolean isImageTokenPathMethod(Method method) {
        if (method == null || KavaReflector.isStatic(method) || method.getReturnType() != String.class) {
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        return params.length == 2
                && params[0] == String.class
                && (params[1] == boolean.class || params[1] == Boolean.class);
    }

    private boolean isVoiceStartRecordMethod(Method method) {
        if (method == null || method.getReturnType() != String.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return KavaReflector.isStatic(method)
                && params.length == 2
                && params[0] == String.class
                && params[1] == String.class;
    }

    private boolean isVoiceFullPathMethod(Method method) {
        if (method == null || method.getReturnType() != String.class) return false;
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 2) {
            return KavaReflector.isStatic(method)
                    && params[0] == String.class
                    && (params[1] == boolean.class || params[1] == Boolean.class);
        }
        return params.length == 3
                && !params[0].isPrimitive()
                && params[1] == String.class
                && (params[2] == boolean.class || params[2] == Boolean.class);
    }

    private boolean isVoiceFinishRecordMethod(Method method) {
        if (method == null || method.getReturnType() != boolean.class) return false;
        if (!KavaReflector.isStatic(method)) return false;
        Class<?>[] params = method.getParameterTypes();
        if (params.length < 3 || params.length > 5) return false;
        if (params.length >= 4 && !params[3].getName().startsWith("com.tencent.mm.storage.")) return false;
        if (params.length == 5 && params[4] != String.class) return false;
        return params[0] == String.class
                && (params[1] == int.class || params[1] == Integer.class)
                && (params[2] == int.class || params[2] == Integer.class);
    }

    private boolean isVoiceMessagePathMethod(Method method) {
        if (method == null || KavaReflector.isStatic(method)
                || java.lang.reflect.Modifier.isAbstract(method.getModifiers())
                || method.getReturnType() != String.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 3
                && params[0].getName().startsWith("com.tencent.mm.storage.")
                && params[1] == String.class
                && params[2] == boolean.class;
    }

    private boolean isSendImageAppInfoMethod(Method method) {
        if (!isSendImageMethod(method)) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 8
                && params[5] == String.class
                && params[6] == String.class;
    }

    private Constructor<?> findSendImageAsyncParamsCtor(Class<?> clazz) {
        if (clazz == null) return null;
        for (Constructor<?> ctor : KavaReflector.declaredConstructors(clazz)) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != 5) continue;
            if (params[0] == String.class
                    && (params[1] == int.class || params[1] == Integer.class)
                    && params[2] == String.class
                    && params[3] == String.class
                    && params[4] != null
                    && !params[4].isPrimitive()) {
                return KavaReflector.accessible(ctor);
            }
        }
        return null;
    }

    private boolean isSendImageAppInfoClass(Class<?> clazz) {
        if (clazz == null || KavaReflector.findConstructor(clazz) == null) return false;
        if (hasDirectImageAppInfoFields(clazz)) return true;
        boolean hasOffsetField = false;
        for (Field field : KavaReflector.declaredFields(clazz)) {
            if (!KavaReflector.isStatic(field)
                    && (field.getType() == int.class || field.getType() == Integer.class)) {
                hasOffsetField = true;
                break;
            }
        }
        if (!hasOffsetField) return false;
        return findIndexedSetter(clazz) != null;
    }

    private boolean hasDirectImageAppInfoFields(Class<?> clazz) {
        int strings = 0;
        int ints = 0;
        int longs = 0;
        int objects = 0;
        for (Field field : KavaReflector.declaredFields(clazz)) {
            if (KavaReflector.isStatic(field)) continue;
            Class<?> type = field.getType();
            if (type == String.class) strings++;
            else if (type == int.class || type == Integer.class) ints++;
            else if (type == long.class || type == Long.class) longs++;
            else if (!type.isPrimitive()) objects++;
        }
        return strings == 5 && ints == 1 && longs == 1 && objects == 0;
    }

    private Method findIndexedSetter(Class<?> clazz) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : KavaReflector.declaredMethods(current)) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 2
                        && (params[0] == int.class || params[0] == Integer.class)
                        && params[1] == Object.class) {
                    if ("set".equals(method.getName())) {
                        return KavaReflector.accessible(method);
                    }
                }
            }
            current = current.getSuperclass();
        }
        current = clazz;
        while (current != null && current != Object.class) {
            for (Method method : KavaReflector.declaredMethods(current)) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 2
                        && (params[0] == int.class || params[0] == Integer.class)
                        && params[1] == Object.class) {
                    return KavaReflector.accessible(method);
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private boolean isSendImageAsyncSubmitMethod(Method method) {
        if (method == null || KavaReflector.isStatic(method)) return false;
        if (KavaReflector.isAbstract(method.getModifiers())) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 1
                && sendImageAsyncParamsClass != null
                && params[0] == sendImageAsyncParamsClass;
    }

    private boolean isKotlinFlowReturn(Method method) {
        if (method == null || method.getReturnType() == null) return false;
        String name = method.getReturnType().getName();
        return name != null && name.startsWith("kotlinx.coroutines.flow.");
    }

    private boolean isImageCdnTaskClass(Class<?> clazz) {
        if (clazz == null) return false;
        return KavaReflector.findFieldRecursive(clazz, "field_mediaId") != null
                && KavaReflector.findFieldRecursive(clazz, "field_fileId") != null
                && KavaReflector.findFieldRecursive(clazz, "field_aesKey") != null
                && KavaReflector.findFieldRecursive(clazz, "field_fullpath") != null
                && KavaReflector.findFieldRecursive(clazz, "field_fileType") != null;
    }

    private boolean isImageCdnSubmitMethod(Method method) {
        if (method == null || imageCdnTaskClass == null) return false;
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 1 && params.length != 2) return false;
        if (!params[0].isAssignableFrom(imageCdnTaskClass)
                && !imageCdnTaskClass.isAssignableFrom(params[0])) {
            return false;
        }
        if (params.length == 2 && params[1] != int.class && params[1] != Integer.class) return false;
        Class<?> ret = method.getReturnType();
        return ret == boolean.class || ret == Boolean.class || ret == int.class || ret == Integer.class
                || ret == imageCdnTaskClass || ret == void.class;
    }

    private boolean isPreferredImageCdnSubmitMethod(Method method) {
        if (!isImageCdnSubmitMethod(method) || KavaReflector.isStatic(method)) return false;
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 2 || (params[1] != int.class && params[1] != Integer.class)) return false;
        Class<?> ret = method.getReturnType();
        return ret == boolean.class || ret == Boolean.class;
    }

    private boolean isImageCdnServiceGetterMethod(Method method) {
        return method != null
                && imageCdnSubmitMethod != null
                && KavaReflector.isStatic(method)
                && method.getParameterTypes().length == 0
                && method.getReturnType() == imageCdnSubmitMethod.getDeclaringClass();
    }

    public boolean isMarsCdnReady() {
        return marsCdnManagerClass != null
                && marsCdnDownloadRequestClass != null
                && marsCdnDownloadCallbackClass != null
                && marsCdnStartDownloadMethod != null;
    }

    private boolean isContactCardXmlMethod(Method method) {
        if (method == null || !KavaReflector.isStatic(method)) return false;
        if (method.getReturnType() != String.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 2
                && params[0] == String.class
                && !params[1].isPrimitive();
    }

    private boolean isServiceGetterMethod(Method method) {
        if (method == null || !KavaReflector.isStatic(method)) {
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        return params.length == 1
                && params[0] == Class.class
                && method.getReturnType() != void.class;
    }

    private Method findPatCreatePairMethod(Class<?> owner) {
        if (owner == null) return null;
        for (Method method : KavaReflector.declaredMethods(owner)) {
            if (isPatCreatePairMethod(method)) {
                return KavaReflector.accessible(method);
            }
        }
        return null;
    }

    private boolean isPatCreatePairMethod(Method method) {
        if (method == null || KavaReflector.isStatic(method)) return false;
        if (method.getReturnType() != Pair.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 6
                && params[0] == String.class
                && params[1] == String.class
                && params[2] == String.class
                && params[3] == String.class
                && (params[4] == int.class || params[4] == Integer.class)
                && (params[5] == long.class || params[5] == Long.class);
    }

    private Method findPatSuffixMethod(Class<?> owner) {
        if (owner == null) return null;
        for (Method method : KavaReflector.declaredMethods(owner)) {
            if (isPatSuffixMethod(method)) {
                return KavaReflector.accessible(method);
            }
        }
        return null;
    }

    private boolean isPatSuffixMethod(Method method) {
        if (method == null || KavaReflector.isStatic(method)) return false;
        if (method.getReturnType() != String.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 2
                && params[0] == String.class
                && params[1] == String.class;
    }

    private Method findPatCanSendMethod(Class<?> owner) {
        if (owner == null) return null;
        for (Method method : KavaReflector.declaredMethods(owner)) {
            if (isPatCanSendMethod(method)) {
                return KavaReflector.accessible(method);
            }
        }
        return null;
    }

    private boolean isPatCanSendMethod(Method method) {
        if (method == null || KavaReflector.isStatic(method)) return false;
        if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 3
                && (params[0] == int.class || params[0] == Integer.class)
                && params[1] == String.class
                && params[2] == String.class;
    }

    private boolean isSendPatSceneClass(Class<?> clazz) {
        return findSendPatSceneCtor(clazz) != null;
    }

    private Constructor<?> findSendPatSceneCtor(Class<?> clazz) {
        if (clazz == null) return null;
        Constructor<?> ctor = findCtorByExactTypes(clazz, Pair.class, String.class, String.class, int.class);
        if (ctor != null) return ctor;
        return findCtorByExactTypes(clazz, Pair.class, String.class, String.class, Integer.class);
    }

    private Constructor<?> findInviteChatroomMemberCtor(Class<?> clazz) {
        if (clazz == null) return null;
        for (Constructor<?> ctor : KavaReflector.declaredConstructors(clazz)) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != 4) continue;
            if (params[0] != String.class) continue;
            if (!List.class.isAssignableFrom(params[1])) continue;
            if (params[2] != int.class && params[2] != Integer.class) continue;
            return KavaReflector.accessible(ctor);
        }
        return null;
    }

    private Constructor<?> findAddChatroomMemberCtor(Class<?> clazz) {
        if (clazz == null) return null;
        for (Constructor<?> ctor : KavaReflector.declaredConstructors(clazz)) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != 4) continue;
            if (params[0] != String.class) continue;
            if (!List.class.isAssignableFrom(params[1])) continue;
            if (params[2] != String.class) continue;
            return KavaReflector.accessible(ctor);
        }
        return null;
    }

    private Constructor<?> findDelChatroomMemberCtor(Class<?> clazz) {
        if (clazz == null) return null;
        for (Constructor<?> ctor : KavaReflector.declaredConstructors(clazz)) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != 3) continue;
            if (params[0] != String.class) continue;
            if (!List.class.isAssignableFrom(params[1])) continue;
            if (params[2] != int.class && params[2] != Integer.class) continue;
            return KavaReflector.accessible(ctor);
        }
        return null;
    }

    private boolean isSendFileAppMsgMethod(Method method) {
        if (method == null || method.getReturnType() != int.class) return false;
        if (!KavaReflector.isStatic(method)) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 6
                && "com.tencent.mm.opensdk.modelmsg.WXMediaMessage".equals(params[0].getName())
                && params[1] == String.class
                && params[2] == String.class
                && params[3] == String.class
                && (params[4] == int.class || params[4] == Integer.class)
                && params[5] == String.class;
    }

    private boolean isSendXmlAppMsgMethod(Method method) {
        if (method == null || method.getReturnType() == void.class) return false;
        if (!"android.util.Pair".equals(method.getReturnType().getName())) return false;
        if (!KavaReflector.isStatic(method)) return false;
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 10 && params.length != 12) return false;
        if (params[0].isPrimitive()) return false;
        return params[1] == String.class
                && params[2] == String.class
                && params[3] == String.class
                && params[4] == String.class
                && params[5] == byte[].class
                && params[6] == String.class
                && params[7] == String.class
                && params[8] == String.class
                && ((params.length == 10 && (params[9] == long.class || params[9] == Long.class))
                || (params.length == 12
                && !params[9].isPrimitive()
                && (params[10] == boolean.class || params[10] == Boolean.class)
                && params[11] == String.class));
    }

    private boolean isAppMsgParseMethod(Method method, Class<?> appMsgClass) {
        if (method == null || appMsgClass == null) return false;
        if (!KavaReflector.isStatic(method)) return false;
        if (method.getReturnType() != appMsgClass) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 1 && params[0] == String.class;
    }

    private boolean isStaticNoArgStringMethod(Method method) {
        return method != null
                && KavaReflector.isStatic(method)
                && method.getReturnType() == String.class
                && method.getParameterTypes().length == 0;
    }

    private boolean isSendFileAttachPathMethod(Method method) {
        if (method == null || method.getReturnType() != String.class) return false;
        if (!KavaReflector.isStatic(method)) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 3
                && params[0] == String.class
                && params[1] == String.class
                && params[2] == String.class;
    }

    private Method findSendFileAppMsgMethod(Class<?> clazz) {
        if (clazz == null) return null;
        try {
            for (Method method : KavaReflector.declaredMethods(clazz)) {
                if (isSendFileAppMsgMethod(method)) return method;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isSendVideoMethod(Method method) {
        if (method == null || method.getReturnType() != void.class) return false;
        Class<?>[] params = method.getParameterTypes();
        if (params.length < 13) return false;
        return params[0] == Context.class
                && params[1] == String.class
                && params[2] == String.class
                && params[3] == String.class
                && (params[4] == int.class || params[4] == Integer.class)
                && (params[5] == int.class || params[5] == Integer.class)
                && (params[7] == boolean.class || params[7] == Boolean.class)
                && (params[8] == boolean.class || params[8] == Boolean.class)
                && params[9] == String.class
                && params[10] == String.class;
    }

    private boolean isEmojiSendMethod(Method method) {
        if (method == null || method.getReturnType() != void.class) return false;
        Class<?>[] params = method.getParameterTypes();
        if (params.length < 4) return false;
        return params[0] == String.class
                && isEmojiInfoClass(params[1]);
    }

    private boolean isEmojiManagerSendMethod(Method method) {
        if (method == null || method.getReturnType() != boolean.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 5
                && Context.class.isAssignableFrom(params[0])
                && params[1] == String.class
                && params[2] == String.class
                && "com.tencent.mm.plugin.msg.MsgIdTalker".equals(params[3].getName())
                && (params[4] == int.class || params[4] == Integer.class);
    }

    private boolean isEmojiGetByMd5Method(Method method) {
        if (method == null || !isEmojiInfoClass(method.getReturnType())) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 1 && params[0] == String.class;
    }

    private boolean isEmojiCreateInfoMethod(Method method) {
        if (method == null || !isEmojiInfoClass(method.getReturnType())) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 4
                && params[0] == String.class
                && (params[1] == int.class || params[1] == Integer.class)
                && (params[2] == int.class || params[2] == Integer.class)
                && (params[3] == int.class || params[3] == Integer.class);
    }

    private boolean isEmojiUpdateInfoMethod(Method method) {
        if (method == null || method.getReturnType() != void.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 1 && isEmojiInfoClass(params[0]);
    }

    private boolean isNoArgStringMethod(Method method) {
        return method != null
                && method.getReturnType() == String.class
                && method.getParameterTypes().length == 0;
    }

    private boolean isStringBooleanMethod(Method method) {
        if (method == null || method.getReturnType() != boolean.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 1 && params[0] == String.class;
    }

    private boolean isEmojiFilePathMethod(Method method) {
        if (method == null || method.getReturnType() != String.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return KavaReflector.isStatic(method)
                && params.length == 3
                && params[0] == String.class
                && params[1] == String.class
                && params[2] == String.class;
    }

    private boolean isEmojiDecodeDataMethod(Method method) {
        if (method == null || KavaReflector.isStatic(method) || method.getReturnType() != byte[].class) {
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        return params.length == 1
                && "com.tencent.mm.api.IEmojiInfo".equals(params[0].getName());
    }

    private Method findEmojiDecodeManagerGetter(Class<?> managerClass) {
        if (managerClass == null) return null;
        for (Method method : KavaReflector.declaredMethods(managerClass)) {
            if (KavaReflector.isStatic(method)
                    && method.getParameterTypes().length == 0
                    && method.getReturnType() == managerClass) {
                return method;
            }
        }
        return null;
    }

    private boolean isEmojiInfoClass(Class<?> clazz) {
        return clazz != null && "com.tencent.mm.storage.emotion.EmojiInfo".equals(clazz.getName());
    }

    private boolean isTransferOperationClass(Class<?> clazz) {
        if (clazz == null) return false;
        if (!hasTransferOperationCtor(clazz)) return false;
        try {
            Object instance = newTransferProbe(clazz);
            if (instance == null) return false;
            Method uri = KavaReflector.findDeclaredMethod(clazz, "getUri");
            if (uri == null) throw new NoSuchMethodException("getUri");
            Object value = KavaReflector.invoke(uri, instance);
            return "/cgi-bin/mmpay-bin/transferoperation".equals(value);
        } catch (Throwable ignored) {
            return "com.tencent.mm.plugin.remittance.model.n0".equals(clazz.getName());
        }
    }

    private boolean isTransferQueryClass(Class<?> clazz) {
        if (clazz == null) return false;
        for (Constructor<?> ctor : KavaReflector.declaredConstructors(clazz)) {
            Class<?>[] p = ctor.getParameterTypes();
            if ((p.length == 5 || p.length == 6)
                    && p[0] == int.class && p[1] == String.class && p[2] == String.class
                    && p[3] == int.class && p[4] == String.class
                    && (p.length == 5 || p[5] == String.class)) return true;
        }
        return false;
    }

    private Method findTransferQueryResponseMethod(Class<?> clazz) {
        if (clazz == null) return null;
        for (Method method : KavaReflector.declaredMethods(clazz)) {
            if (isTransferQueryResponseMethod(method, clazz)) return method;
        }
        return null;
    }

    private boolean isTransferQueryResponseMethod(Method method, Class<?> owner) {
        if (method == null || owner == null || method.getDeclaringClass() != owner
                || java.lang.reflect.Modifier.isStatic(method.getModifiers())
                || method.getReturnType() != void.class) return false;
        Class<?>[] p = method.getParameterTypes();
        return p.length == 3 && p[0] == int.class && p[1] == String.class
                && "org.json.JSONObject".equals(p[2].getName());
    }

    private boolean isVerifyUserClass(Class<?> clazz) {
        if (clazz == null) return false;
        if (!hasVerifyUserCtor(clazz)) return false;
        try {
            Method type = KavaReflector.findMethodRecursive(clazz, "getType");
            if (type == null) return false;
            Class<?>[] params = type.getParameterTypes();
            return params.length == 0
                    && (type.getReturnType() == int.class || type.getReturnType() == Integer.class);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean hasVerifyUserCtor(Class<?> clazz) {
        if (clazz == null) return false;
        for (Constructor<?> ctor : KavaReflector.declaredConstructors(clazz)) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 4
                    && isIntClass(params[0])
                    && params[1] == String.class
                    && params[2] == String.class
                    && isIntClass(params[3])) {
                return true;
            }
            if (params.length == 6
                    && isIntClass(params[0])
                    && params[1] == String.class
                    && params[2] == String.class
                    && isIntClass(params[3])
                    && params[4] == String.class
                    && isIntClass(params[5])) {
                return true;
            }
            if (params.length == 8
                    && isIntClass(params[0])
                    && params[1] == String.class
                    && params[2] == String.class
                    && isIntClass(params[3])
                    && params[4] == String.class
                    && isIntClass(params[5])
                    && List.class.isAssignableFrom(params[6])) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTransferOperationCtor(Class<?> clazz) {
        if (clazz == null) return false;
        for (Constructor<?> ctor : KavaReflector.declaredConstructors(clazz)) {
            if (isTransferOperationCtorTypes(ctor.getParameterTypes())) return true;
        }
        return false;
    }

    private boolean isTransferOperationCtorTypes(Class<?>[] params) {
        if (params == null || params.length < 6
                || params[0] != String.class
                || params[1] != String.class
                || !isIntClass(params[2])
                || params[3] != String.class
                || params[4] != String.class
                || !isIntClass(params[5])) {
            return false;
        }
        if (params.length == 9) {
            return params[6] == String.class
                    && isIntClass(params[7])
                    && Map.class.isAssignableFrom(params[8]);
        }
        if (params.length == 10) {
            return params[6] == String.class
                    && params[7] == String.class
                    && isIntClass(params[8])
                    && Map.class.isAssignableFrom(params[9]);
        }
        if (params.length == 11) {
            return params[6] == String.class
                    && isIntClass(params[7])
                    && Map.class.isAssignableFrom(params[8])
                    && isLongClass(params[9])
                    && params[10] == String.class;
        }
        if (params.length == 12) {
            boolean full58 = params[6] == String.class
                    && params[7] == String.class
                    && isIntClass(params[8])
                    && Map.class.isAssignableFrom(params[9])
                    && isLongClass(params[10])
                    && params[11] == String.class;
            boolean short66 = params[6] == String.class
                    && isIntClass(params[7])
                    && Map.class.isAssignableFrom(params[8])
                    && isLongClass(params[9])
                    && params[10] == String.class
                    && params[11] == String.class;
            return full58 || short66;
        }
        if (params.length == 13) {
            boolean full66 = params[6] == String.class
                    && params[7] == String.class
                    && isIntClass(params[8])
                    && Map.class.isAssignableFrom(params[9])
                    && isLongClass(params[10])
                    && params[11] == String.class
                    && params[12] == String.class;
            boolean short68 = params[6] == String.class
                    && isIntClass(params[7])
                    && params[8] == String.class
                    && Map.class.isAssignableFrom(params[9])
                    && isLongClass(params[10])
                    && params[11] == String.class
                    && params[12] == String.class;
            return full66 || short68;
        }
        if (params.length == 14) {
            return params[6] == String.class
                    && params[7] == String.class
                    && isIntClass(params[8])
                    && params[9] == String.class
                    && Map.class.isAssignableFrom(params[10])
                    && isLongClass(params[11])
                    && params[12] == String.class
                    && params[13] == String.class;
        }
        return false;
    }

    private boolean isIntClass(Class<?> clazz) {
        return clazz == int.class || clazz == Integer.class;
    }

    private boolean isLongClass(Class<?> clazz) {
        return clazz == long.class || clazz == Long.class;
    }

    private boolean isSnsUploadPackHelperClass(Class<?> clazz) {
        if (clazz == null) return false;
        boolean hasCtor = false;
        for (Constructor<?> ctor : KavaReflector.declaredConstructors(clazz)) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length == 2 && isIntClass(params[0])
                    && Context.class.isAssignableFrom(params[1])) {
                hasCtor = true;
                break;
            }
        }
        if (!hasCtor) return false;

        boolean hasCommit = false;
        boolean hasAddImage = false;
        int chainStringMethods = 0;
        for (Method method : KavaReflector.declaredMethods(clazz)) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0 && isIntClass(method.getReturnType())) {
                hasCommit = true;
            } else if (params.length == 2
                    && method.getReturnType() == boolean.class
                    && params[0] == String.class
                    && params[1] == String.class) {
                hasAddImage = true;
            } else if (params.length == 1
                    && method.getReturnType() == clazz
                    && params[0] == String.class) {
                chainStringMethods++;
            }
        }
        return hasCommit && hasAddImage && chainStringMethods >= 2;
    }

    private boolean isSnsUploadManagerClass(Class<?> clazz) {
        if (clazz == null) return false;
        boolean hasCheckPost = false;
        boolean hasListenerMethod = false;
        for (Method method : KavaReflector.declaredMethods(clazz)) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0 && method.getReturnType() == void.class) {
                hasCheckPost = true;
            }
            if (params.length == 1 && method.getReturnType() == void.class
                    && !params[0].isPrimitive()
                    && params[0].getName().startsWith("com.tencent.mm.plugin.sns.model.")) {
                hasListenerMethod = true;
            }
        }
        return hasCheckPost && hasListenerMethod;
    }

    private Method findSnsUploadManagerGetter(Class<?> managerClass) {
        if (managerClass == null) return null;
        try {
            List<MethodData> methods = dexKit.findMethod(
                    mkMethodUsingStrings("getSnsUploadManager"));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (!KavaReflector.isStatic(method)) continue;
                    if (method.getParameterTypes().length != 0) continue;
                    if (method.getReturnType() != managerClass) continue;
                    return KavaReflector.accessible(method);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Method findSnsHelperChainMethod(String marker) {
        if (snsUploadPackHelperClass == null) return null;
        try {
            List<MethodData> methods = dexKit.findMethod(mkMethodUsingStrings(marker));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (method.getDeclaringClass() != snsUploadPackHelperClass) continue;
                    if (method.getReturnType() != snsUploadPackHelperClass) continue;
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length != 1 || params[0] != String.class) continue;
                    return KavaReflector.accessible(method);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Method findSnsAddImageMethod(Class<?> helperClass) {
        if (helperClass == null) return null;
        try {
            List<MethodData> methods = dexKit.findMethod(mkMethodUsingStrings("addImageMediaObjByPath"));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (method.getDeclaringClass() != helperClass) continue;
                    if (method.getReturnType() != boolean.class) continue;
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length == 2 && params[0] == String.class && params[1] == String.class) {
                        return KavaReflector.accessible(method);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        for (Method method : KavaReflector.declaredMethods(helperClass)) {
            if (method.getReturnType() != boolean.class) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 2 && params[0] == String.class && params[1] == String.class) {
                return KavaReflector.accessible(method);
            }
        }
        return null;
    }

    private Method findSnsAddVideoMethod(Class<?> helperClass) {
        if (helperClass == null) return null;
        try {
            List<MethodData> methods = dexKit.findMethod(
                    mkMethodUsingStrings("addSightObjectByPath"));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (method.getDeclaringClass() != helperClass) continue;
                    if (method.getReturnType() != boolean.class) continue;
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length == 4
                            && params[0] == String.class
                            && params[1] == String.class
                            && params[2] == String.class
                            && params[3] == String.class) {
                        return KavaReflector.accessible(method);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            List<MethodData> methods = dexKit.findMethod(
                    mkMethodUsingStrings("produceSightByPath"));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (method.getDeclaringClass() != helperClass) continue;
                    if (method.getReturnType() != boolean.class) continue;
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length == 4
                            && params[0] == String.class
                            && params[1] == String.class
                            && params[2] == String.class
                            && params[3] == String.class) {
                        return KavaReflector.accessible(method);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Method findSnsCommitMethod(Class<?> helperClass) {
        if (helperClass == null) return null;
        try {
            List<MethodData> methods = dexKit.findMethod(mkMethodUsingStrings("commit sns info ret %d"));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (method.getDeclaringClass() != helperClass) continue;
                    if (!isIntClass(method.getReturnType())) continue;
                    if (method.getParameterTypes().length == 0) return KavaReflector.accessible(method);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        for (Method method : KavaReflector.declaredMethods(helperClass)) {
            if (method.getParameterTypes().length == 0 && isIntClass(method.getReturnType())) {
                return KavaReflector.accessible(method);
            }
        }
        return null;
    }

    private Method findSnsUploadCheckMethod(Class<?> managerClass) {
        if (managerClass == null) return null;
        try {
            List<MethodData> methods = dexKit.findMethod(mkMethodUsingStrings("checkPostInUI"));
            for (MethodData methodData : methods) {
                try {
                    Method method = methodData.getMethodInstance(classLoader);
                    if (method.getDeclaringClass() != managerClass) continue;
                    if (method.getReturnType() != void.class) continue;
                    if (method.getParameterTypes().length == 0) return KavaReflector.accessible(method);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        for (Method method : KavaReflector.declaredMethods(managerClass)) {
            if (method.getReturnType() == void.class && method.getParameterTypes().length == 0) {
                return KavaReflector.accessible(method);
            }
        }
        return null;
    }

    private Method findSnsShareAppMsgMethod(Class<?> managerClass) {
        if (managerClass == null) return null;
        for (Method method : KavaReflector.declaredMethods(managerClass)) {
            if (isSnsShareAppMsgMethod(method)) {
                return KavaReflector.accessible(method);
            }
        }
        return null;
    }

    private boolean isSnsShareAppMsgMethod(Method method) {
        if (method == null || KavaReflector.isStatic(method)) return false;
        if (method.getReturnType() == void.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 4
                && "com.tencent.mm.opensdk.modelmsg.WXMediaMessage".equals(params[0].getName())
                && params[1] == String.class
                && params[2] == String.class
                && params[3] == String.class;
    }

    private boolean isPatDisplayTemplateMethod(Method method) {
        if (method == null || method.getReturnType() == void.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 2
                && !params[0].isPrimitive()
                && params[1] == String.class;
    }

    private Object newTransferProbe(Class<?> clazz) {
        if (clazz == null) return null;
        for (Constructor<?> ctor : KavaReflector.declaredConstructors(clazz)) {
            try {
                Class<?>[] params = ctor.getParameterTypes();
                if (!isTransferOperationCtorTypes(params)) continue;
                Object[] args = new Object[params.length];
                for (int i = 0; i < params.length; i++) {
                    if (params[i] == String.class) {
                        args[i] = "";
                    } else if (params[i] == int.class || params[i] == Integer.class) {
                        args[i] = 0;
                    } else if (params[i] == long.class || params[i] == Long.class) {
                        args[i] = 0L;
                    } else if (Map.class.isAssignableFrom(params[i])) {
                        args[i] = null;
                    } else {
                        args[i] = null;
                    }
                }
                args[3] = "confirm";
                return KavaReflector.newInstance(ctor, args);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    public boolean hasTransferOperationApi() {
        return transferOperationClass != null && hasTransferOperationCtor(transferOperationClass);
    }

    public boolean hasTransferQueryApi() {
        return isTransferQueryClass(transferQueryClass)
                && isTransferQueryResponseMethod(transferQueryResponseMethod, transferQueryClass);
    }

    public boolean hasVerifyUserApi() {
        return verifyUserClass != null && hasVerifyUserCtor(verifyUserClass);
    }

    public boolean hasRevokeMsgApi() {
        return revokeMsgCtor != null || findRevokeMsgCtor(revokeMsgClass) != null;
    }

    public boolean hasUploadDeviceStepApi() {
        return uploadDeviceStepCtor != null || findUploadDeviceStepCtor(uploadDeviceStepClass) != null;
    }

    public boolean hasContactLabelNetworkApi() {
        return (addContactLabelCtorString != null || addContactLabelCtorList != null)
                && (modifyContactLabelListCtor != null
                || findCtorByExactTypes(modifyContactLabelListClass, java.util.LinkedList.class) != null);
    }

    public boolean hasSnsUploadApi() {
        if (!isSnsUploadPackHelperClass(snsUploadPackHelperClass)) return false;
        if (!isSnsUploadManagerClass(snsUploadManagerClass)) return false;
        resolveSnsUploadMethods();
        return snsUploadManagerGetterMethod != null
                && snsShareAppMsgMethod != null
                && snsSetContentMethod != null
                && snsAddImageMethod != null
                && snsCommitMethod != null
                && snsUploadCheckMethod != null;
    }

    public boolean hasLocalMessageApi() {
        return localSystemMessageMethod != null
                || (localMessageInsertMethod != null
                && localMessageCreateTimeMethod != null
                && localMessageClass != null
                && localMessageCtor != null);
    }

    public boolean hasGroupSolitaireApi() {
        return groupSolitairePluginClass != null && groupSolitaireSendMethod != null;
    }

    private void saveCache() {
        if (cachePrefs == null || runtimeCacheKey.length() == 0) return;
        try {
            SharedPreferences.Editor editor = cachePrefs.edit().clear();
            editor.putString(CACHE_KEY, runtimeCacheKey);
            if (receiveUnionLocatorResolved && openUnionLocatorResolved) {
                editor.putString(RED_PACKET_UNION_LOCATOR_VERSION_KEY,
                        RED_PACKET_UNION_LOCATOR_VERSION);
            }
            if (networkQueueLocatorResolved) {
                editor.putString(NETWORK_QUEUE_LOCATOR_VERSION_KEY,
                        NETWORK_QUEUE_LOCATOR_VERSION);
            }
            editor.putString("addMsgClasses", joinClassNames(addMsgClasses));
            putClass(editor, "receiveLuckyMoneyClass", receiveLuckyMoneyClass);
            putClass(editor, "receiveLuckyMoneyUnionClass", receiveLuckyMoneyUnionClass);
            putClass(editor, "openLuckyMoneyClass", openLuckyMoneyClass);
            putClass(editor, "openLuckyMoneyUnionClass", openLuckyMoneyUnionClass);
            putClass(editor, "netQueueClass", netQueueClass);
            editor.putString("netQueueCandidateClasses", joinClassNames(netQueueCandidateClasses));
            editor.putString("packetBaseClasses", joinClassNames(packetBaseClasses));
            editor.putString("packetQueueClasses", joinClassNames(packetQueueClasses));
            editor.putString("fakePacketClasses", joinClassNames(fakePacketClasses));
            putClass(editor, "protobufBaseClass", protobufBaseClass);
            putClass(editor, "protobufRawReqClass", protobufRawReqClass);
            putClass(editor, "protobufGenericRespClass", protobufGenericRespClass);
            putClass(editor, "protobufConfigBuilderClass", protobufConfigBuilderClass);
            putClass(editor, "protobufReqRespClass", protobufReqRespClass);
            putClass(editor, "protobufCallbackClass", protobufCallbackClass);
            putClass(editor, "protobufNewSendMsgReqClass", protobufNewSendMsgReqClass);
            putClass(editor, "protobufOplogReqClass", protobufOplogReqClass);
            putClass(editor, "protobufOnGYNetEndClass", protobufOnGYNetEndClass);
            putClass(editor, "protobufNetSceneBaseClass", protobufNetSceneBaseClass);
            putMethod(editor, "protobufStaticDispatchMethod", protobufStaticDispatchMethod);
            putMethodList(editor, "protobufSceneEndMethods", protobufSceneEndMethods);
            putClass(editor, "wishWxHbClass", wishWxHbClass);
            putClass(editor, "ecsGiftTaskClass", ecsGiftTaskClass);
            putClass(editor, "ecsGiftServiceClass", ecsGiftServiceClass);
            putClass(editor, "ecsGiftMsgClass", ecsGiftMsgClass);
            putMethodList(editor, "msgDescTextMethods", msgDescTextMethods);
            putClass(editor, "sendTextMsgClass", sendTextMsgClass);
            putMethod(editor, "serviceGetterMethod", serviceGetterMethod);
            putMethodList(editor, "getContactAddMethods", getContactAddMethods);
            putMethodList(editor, "getContactServiceGetters", getContactServiceGetters);
            putMethod(editor, "sendImageMethod", sendImageMethod);
            putClass(editor, "sendImageAsyncParamsClass", sendImageAsyncParamsClass);
            putClass(editor, "sendImageCrossParamsClass", sendImageCrossParamsClass);
            putClass(editor, "sendImageAppInfoClass", sendImageAppInfoClass);
            putMethod(editor, "sendImageAsyncSubmitMethod", sendImageAsyncSubmitMethod);
            putClass(editor, "imageCdnTaskClass", imageCdnTaskClass);
            putMethod(editor, "imageCdnSubmitMethod", imageCdnSubmitMethod);
            putMethod(editor, "imageCdnServiceGetterMethod", imageCdnServiceGetterMethod);
            putClass(editor, "marsCdnManagerClass", marsCdnManagerClass);
            putClass(editor, "marsCdnDownloadRequestClass", marsCdnDownloadRequestClass);
            putClass(editor, "marsCdnDownloadCallbackClass", marsCdnDownloadCallbackClass);
            putMethod(editor, "marsCdnStartDownloadMethod", marsCdnStartDownloadMethod);
            putMethod(editor, "imageBestPathMethod", imageBestPathMethod);
            putMethod(editor, "imageStorageGetterMethod", imageStorageGetterMethod);
            putMethod(editor, "imageTokenPathMethod", imageTokenPathMethod);
            putMethod(editor, "sendFileMethod", sendFileMethod);
            putMethod(editor, "sendFileAttachDirMethod", sendFileAttachDirMethod);
            putMethod(editor, "sendFileAttachPathMethod", sendFileAttachPathMethod);
            putMethod(editor, "sendXmlAppMsgMethod", sendXmlAppMsgMethod);
            putMethod(editor, "appMsgParseMethod", appMsgParseMethod);
            putClass(editor, "groupSolitairePluginClass", groupSolitairePluginClass);
            putMethod(editor, "groupSolitaireSendMethod", groupSolitaireSendMethod);
            putMethod(editor, "localSystemMessageMethod", localSystemMessageMethod);
            putClass(editor, "localMessageClass", localMessageClass);
            putMethod(editor, "localMessageInsertMethod", localMessageInsertMethod);
            putMethod(editor, "localMessageCreateTimeMethod", localMessageCreateTimeMethod);
            putMethod(editor, "sendVideoMethod", sendVideoMethod);
            putClass(editor, "sendVideoTaskClass", sendVideoTaskClass);
            putMethod(editor, "videoPathMethod", videoPathMethod);
            putMethod(editor, "videoPathOwnerGetterMethod", videoPathOwnerGetterMethod);
            putClass(editor, "videoInfoClass", videoInfoClass);
            putMethod(editor, "videoInfoByFileNameMethod", videoInfoByFileNameMethod);
            putClass(editor, "transferOperationClass", transferOperationClass);
            putClass(editor, "transferQueryClass", transferQueryClass);
            putMethod(editor, "transferQueryResponseMethod", transferQueryResponseMethod);
            putClass(editor, "verifyUserClass", verifyUserClass);
            putMethod(editor, "contactCardXmlMethod", contactCardXmlMethod);
            putMethod(editor, "patDisplayTemplateMethod", patDisplayTemplateMethod);
            putClass(editor, "patExtensionClass", patExtensionClass);
            putMethod(editor, "patCreatePairMethod", patCreatePairMethod);
            putMethod(editor, "patSuffixMethod", patSuffixMethod);
            putMethod(editor, "patCanSendMethod", patCanSendMethod);
            putClass(editor, "sendPatSceneClass", sendPatSceneClass);
            putMethod(editor, "voiceStartRecordMethod", voiceStartRecordMethod);
            putMethod(editor, "voiceFullPathMethod", voiceFullPathMethod);
            putMethod(editor, "voiceMessagePathMethod", voiceMessagePathMethod);
            editor.putBoolean("voiceMessagePathLookupComplete", voiceMessagePathLookupComplete);
            putMethod(editor, "voiceFinishRecordMethod", voiceFinishRecordMethod);
            putMethod(editor, "voiceInfoQueryMethod", voiceInfoQueryMethod);
            putClass(editor, "voiceUploadClass", voiceUploadClass);
            putMethod(editor, "voicePlaybackStartMethod", voicePlaybackStartMethod);
            putMethod(editor, "voicePlaybackPauseMethod", voicePlaybackPauseMethod);
            putMethod(editor, "voicePlaybackResumeMethod", voicePlaybackResumeMethod);
            putMethod(editor, "voicePlaybackStopMethod", voicePlaybackStopMethod);
            putMethod(editor, "emojiSendMethod", emojiSendMethod);
            putMethod(editor, "emojiManagerSendMethod", emojiManagerSendMethod);
            putMethod(editor, "emojiGetByMd5Method", emojiGetByMd5Method);
            putMethod(editor, "emojiCreateInfoMethod", emojiCreateInfoMethod);
            putMethod(editor, "emojiUpdateInfoMethod", emojiUpdateInfoMethod);
            putMethod(editor, "emojiAccPathMethod", emojiAccPathMethod);
            putMethod(editor, "emojiCheckGifMethod", emojiCheckGifMethod);
            putMethod(editor, "emojiFilePathMethod", emojiFilePathMethod);
            putMethod(editor, "emojiDecodeDataMethod", emojiDecodeDataMethod);
            putMethod(editor, "emojiDecodeManagerGetterMethod", emojiDecodeManagerGetterMethod);
            putClass(editor, "favoriteItemClass", favoriteItemClass);
            putMethod(editor, "favoriteItemConvertFromCursorMethod", favoriteItemConvertFromCursorMethod);
            putClass(editor, "favoriteServiceClass", favoriteServiceClass);
            putMethod(editor, "favoriteServiceResolverMethod", favoriteServiceResolverMethod);
            putMethod(editor, "favoriteStorageGetterMethod", favoriteStorageGetterMethod);
            putMethod(editor, "favoriteListMethod", favoriteListMethod);
            putMethod(editor, "favoriteListNextMethod", favoriteListNextMethod);
            putMethod(editor, "favoriteListCursorMethod", favoriteListCursorMethod);
        putMethod(editor, "favoriteGetMethod", favoriteGetMethod);
        putMethod(editor, "favoriteSendMethod", favoriteSendMethod);
            putClass(editor, "mmKernelClass", mmKernelClass);
            putClass(editor, "coreStorageClass", coreStorageClass);
            putClass(editor, "configStorageClass", configStorageClass);
            putClass(editor, "sqliteDbWrapperClass", sqliteDbWrapperClass);
            putMethod(editor, "coreStorageGetter", coreStorageGetter);
            putMethod(editor, "conversationDeleteMethod", conversationDeleteMethod);
            putMethod(editor, "messageClearByTalkerMethod", messageClearByTalkerMethod);
            putMethod(editor, "messageClearBatchMethod", messageClearBatchMethod);
            putMethod(editor, "contactMuteStateMethod", contactMuteStateMethod);
            putMethod(editor, "contactMuteEnableMethod", contactMuteEnableMethod);
            putMethod(editor, "contactMuteDisableMethod", contactMuteDisableMethod);
            putMethod(editor, "contactStorageGetterMethod", contactStorageGetterMethod);
            putMethod(editor, "contactStorageQueryMethod", contactStorageQueryMethod);
            putMethod(editor, "chatroomMuteServiceGetterMethod", chatroomMuteServiceGetterMethod);
            putMethod(editor, "chatroomMuteBuildMethod", chatroomMuteBuildMethod);
            putMethod(editor, "chatroomMuteSubmitMethod", chatroomMuteSubmitMethod);
            putMethod(editor, "groupMemberDisplayNameMethod", groupMemberDisplayNameMethod);
            putClass(editor, "addChatroomMemberClass", addChatroomMemberClass);
            putClass(editor, "inviteChatroomMemberClass", inviteChatroomMemberClass);
            putClass(editor, "delChatroomMemberClass", delChatroomMemberClass);
            putClass(editor, "revokeMsgClass", revokeMsgClass);
            putClass(editor, "uploadDeviceStepClass", uploadDeviceStepClass);
            putClass(editor, "addContactLabelClass", addContactLabelClass);
            putClass(editor, "modifyContactLabelListClass", modifyContactLabelListClass);
            putClass(editor, "snsUploadPackHelperClass", snsUploadPackHelperClass);
            putClass(editor, "snsUploadManagerClass", snsUploadManagerClass);
            putMethod(editor, "snsUploadManagerGetterMethod", snsUploadManagerGetterMethod);
            putMethod(editor, "snsSetContentMethod", snsSetContentMethod);
            putMethod(editor, "snsSetSdkIdMethod", snsSetSdkIdMethod);
            putMethod(editor, "snsSetSdkAppNameMethod", snsSetSdkAppNameMethod);
            putMethod(editor, "snsAddImageMethod", snsAddImageMethod);
            putMethod(editor, "snsAddVideoMethod", snsAddVideoMethod);
            putMethod(editor, "snsCommitMethod", snsCommitMethod);
            putMethod(editor, "snsShareAppMsgMethod", snsShareAppMsgMethod);
            putMethod(editor, "snsUploadCheckMethod", snsUploadCheckMethod);
            putMethod(editor, "chatPageStartMethod", chatPageStartMethod);
            putMethod(editor, "chatPageFragmentEnterMethod", chatPageFragmentEnterMethod);
            putMethod(editor, "chatPageFragmentExitMethod", chatPageFragmentExitMethod);
            putMethod(editor, "chatFooterSendClickMethod", chatFooterSendClickMethod);
            editor.putBoolean(LOCATOR_WISH_COMPLETE, wishLocatorResolved);
            editor.putBoolean(LOCATOR_PACKET_COMPLETE, packetCompatLocatorResolved);
            editor.putBoolean(LOCATOR_ECS_COMPLETE, ecsGiftLocatorResolved);
            editor.putBoolean(LOCATOR_MSG_DESC_COMPLETE, msgDescLocatorResolved);
            editor.putBoolean(LOCATOR_PROTOBUF_COMPLETE, protobufLocatorResolved);
            boolean snapshotReady = !cacheDescriptorInvalid;
            editor.putString(CACHE_SNAPSHOT_VERSION_KEY,
                    snapshotReady ? CACHE_SNAPSHOT_VERSION : "");
            editor.putBoolean(CACHE_COMPLETE, snapshotReady);
            editor.apply();
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " 保存缓存失败: " + e.getMessage(), e);
        }
    }

    private Class<?> loadClass(String key) {
        try {
            String name = cachePrefs.getString(key, "");
            if (name == null || name.length() == 0) return null;
            Class<?> value = KavaReflector.loadClass(name, classLoader);
            if (value == null) cacheDescriptorInvalid = true;
            return value;
        } catch (Throwable ignored) {
            cacheDescriptorInvalid = true;
            return null;
        }
    }

    private List<Class<?>> loadClassList(String key) {
        List<Class<?>> result = new ArrayList<>();
        try {
            String text = cachePrefs.getString(key, "");
            if (text == null || text.length() == 0) return result;
            String[] names = text.split(",");
            for (String raw : names) {
                String name = raw.trim();
                if (name.length() == 0) continue;
                try {
                    Class<?> clazz = KavaReflector.loadClass(name, classLoader);
                    if (clazz == null) cacheDescriptorInvalid = true;
                    if (!result.contains(clazz)) result.add(clazz);
                } catch (Throwable ignored) { cacheDescriptorInvalid = true; }
            }
        } catch (Throwable ignored) { cacheDescriptorInvalid = true; }
        return result;
    }

    private Method loadMethod(String key) {
        try {
            String spec = cachePrefs.getString(key, "");
            if (spec == null || spec.length() == 0) return null;
            int hash = spec.indexOf('#');
            int left = spec.indexOf('(', hash + 1);
            int right = spec.indexOf(')', left + 1);
            if (hash <= 0 || left <= hash || right < left) {
                cacheDescriptorInvalid = true;
                return null;
            }
            Class<?> owner = KavaReflector.loadClass(spec.substring(0, hash), classLoader);
            String name = spec.substring(hash + 1, left);
            String paramsText = spec.substring(left + 1, right);
            Class<?>[] params = parseParamTypes(paramsText);
            Method value = KavaReflector.findDeclaredMethod(owner, name, params);
            if (value == null) cacheDescriptorInvalid = true;
            return value;
        } catch (Throwable ignored) {
            cacheDescriptorInvalid = true;
            return null;
        }
    }

    private List<Method> loadMethodList(String key) {
        List<Method> result = new ArrayList<>();
        try {
            String text = cachePrefs.getString(key, "");
            if (text == null || text.length() == 0) return result;
            String[] specs = text.split("\\n");
            for (String spec : specs) {
                Method method = loadMethodSpec(spec.trim());
                if (method != null && !result.contains(method)) result.add(method);
            }
        } catch (Throwable ignored) {}
        return result;
    }

    private Method loadMethodSpec(String spec) {
        try {
            if (spec == null || spec.length() == 0) return null;
            int hash = spec.indexOf('#');
            int left = spec.indexOf('(', hash + 1);
            int right = spec.indexOf(')', left + 1);
            if (hash <= 0 || left <= hash || right < left) {
                cacheDescriptorInvalid = true;
                return null;
            }
            Class<?> owner = KavaReflector.loadClass(spec.substring(0, hash), classLoader);
            String name = spec.substring(hash + 1, left);
            String paramsText = spec.substring(left + 1, right);
            Class<?>[] params = parseParamTypes(paramsText);
            return KavaReflector.findDeclaredMethod(owner, name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Class<?>[] parseParamTypes(String paramsText) throws ClassNotFoundException {
        if (paramsText == null || paramsText.length() == 0) return new Class<?>[0];
        String[] names = paramsText.split(",");
        Class<?>[] result = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            result[i] = typeOf(names[i].trim());
        }
        return result;
    }

    private Class<?> typeOf(String name) throws ClassNotFoundException {
        if ("boolean".equals(name)) return boolean.class;
        if ("byte".equals(name)) return byte.class;
        if ("char".equals(name)) return char.class;
        if ("short".equals(name)) return short.class;
        if ("int".equals(name)) return int.class;
        if ("long".equals(name)) return long.class;
        if ("float".equals(name)) return float.class;
        if ("double".equals(name)) return double.class;
        if ("void".equals(name)) return void.class;
        return KavaReflector.loadClass(name, classLoader);
    }

    private void putClass(SharedPreferences.Editor editor, String key, Class<?> clazz) {
        editor.putString(key, clazz != null ? clazz.getName() : "");
    }

    private void putMethod(SharedPreferences.Editor editor, String key, Method method) {
        editor.putString(key, method != null ? methodSpec(method) : "");
    }

    private void putMethodList(SharedPreferences.Editor editor, String key, List<Method> methods) {
        StringBuilder sb = new StringBuilder();
        if (methods != null) {
            for (Method method : methods) {
                if (method == null) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(methodSpec(method));
            }
        }
        editor.putString(key, sb.toString());
    }

    private String joinClassNames(List<Class<?>> classes) {
        if (classes == null || classes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Class<?> clazz : classes) {
            if (clazz == null) continue;
            if (sb.length() > 0) sb.append(',');
            sb.append(clazz.getName());
        }
        return sb.toString();
    }

    private String methodSpec(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getDeclaringClass().getName())
                .append('#')
                .append(method.getName())
                .append('(');
        Class<?>[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(params[i].getName());
        }
        return sb.append(')').toString();
    }

    private String buildRuntimeCacheKey(Context context, ClassLoader loader) {
        return WeChatVersionApi.buildCacheKey(context, loader);
    }

    private String shortKey(String key) {
        if (key == null) return "";
        return key.length() <= 80 ? key : key.substring(0, 80) + "...";
    }

    // ============ 伪造/分裂红包与网络包兼容 ============
    private void resolvePacketCompatClasses() {
        if (packetCompatLocatorResolved) return;
        try {
            packetBaseClasses.clear();
            packetQueueClasses.clear();
            fakePacketClasses.clear();

            collectClassCandidates(packetBaseClasses, 10,
                    new String[][]{{"MicroMsg.NetSceneBase"}});
            collectClassCandidates(packetQueueClasses, 10,
                    new String[][]{{"doSceneImp mmcgi"}});
            collectClassCandidates(fakePacketClasses, 10,
                    new String[][]{
                            {"/cgi-bin/mmpay-bin/requestwxhb"},
                            {"NetScenePrepareLuckyMoney"},
                            {"sendMsgXml"}
                    });

            logDetail("包兼容类: base=" + packetBaseClasses.size()
                    + " queue=" + packetQueueClasses.size()
                    + " fake=" + fakePacketClasses.size());
            packetCompatLocatorResolved = true;
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolvePacketCompat 失败: " + e.getMessage(), e);
        }
    }

    private void collectClassCandidates(List<Class<?>> out, int maxCount, String[][] anchors) {
        if (out == null || anchors == null) return;
        for (String[] anchor : anchors) {
            try {
                List<ClassData> classes = dexKit.findClass(mkClassUsingStrings(anchor));
                int max = Math.min(classes.size(), maxCount);
                for (int i = 0; i < max; i++) {
                    try {
                        Class<?> cl = KavaReflector.loadClass(classes.get(i).getName(), classLoader);
                        if (!out.contains(cl)) out.add(cl);
                    } catch (Throwable ignored) {}
                }
                if (!out.isEmpty()) return;
            } catch (Throwable ignored) {}
        }
    }

    private Class<?> findFirstClassByStrings(String... strings) {
        try {
            List<ClassData> classes = dexKit.findClass(mkClassUsingStrings(strings));
            for (ClassData cd : classes) {
                try {
                    return KavaReflector.loadClass(cd.getName(), classLoader);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Constructor<?> findCtorByArgCount(Class<?> cl, int argCount) {
        if (cl == null) return null;
        for (Constructor<?> c : KavaReflector.declaredConstructors(cl)) {
            if (c.getParameterTypes().length == argCount) {
                return c;
            }
        }
        return null;
    }

    private Constructor<?> findFirstCtorByArgCounts(Class<?> cl, int... argCounts) {
        if (cl == null || argCounts == null) return null;
        for (int argCount : argCounts) {
            Constructor<?> ctor = findCtorByArgCount(cl, argCount);
            if (ctor != null) return ctor;
        }
        return null;
    }

    private Constructor<?> findLocalMessageConstructor(Class<?> cl) {
        Constructor<?> emptyCtor = findCtorByExactTypes(cl);
        if (emptyCtor != null) return emptyCtor;
        return findCtorByExactTypes(cl, String.class);
    }

    private Constructor<?> findRevokeMsgCtor(Class<?> cl) {
        if (cl == null || localMessageClass == null) return null;
        for (Constructor<?> ctor : KavaReflector.declaredConstructors(cl)) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != 3) continue;
            if (!params[0].isAssignableFrom(localMessageClass)
                    && !localMessageClass.isAssignableFrom(params[0])) {
                continue;
            }
            if (params[1] != String.class || params[2] != String.class) continue;
            return KavaReflector.accessible(ctor);
        }
        return null;
    }

    private Constructor<?> findUploadDeviceStepCtor(Class<?> cl) {
        if (cl == null) return null;
        for (Constructor<?> ctor : KavaReflector.declaredConstructors(cl)) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != 7) continue;
            if (params[0] != String.class || params[1] != String.class) continue;
            if (params[2] != int.class || params[3] != int.class || params[4] != int.class) continue;
            if (params[5] != String.class || params[6] != int.class) continue;
            return KavaReflector.accessible(ctor);
        }
        return null;
    }

    private boolean isLocalMessageInsertMethod(Method method) {
        if (method == null || method.getReturnType() != long.class) return false;
        if (!KavaReflector.isStatic(method)) return false;
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 1) return false;
        Class<?> msgClass = params[0];
        if (msgClass == null || !msgClass.getName().startsWith("com.tencent.mm.storage.")) return false;
        return KavaReflector.findConstructor(msgClass) != null
                || KavaReflector.findConstructor(msgClass, String.class) != null;
    }

    public void resolveGroupSolitaireApi() {
        if (ensureSnapshotLoaded()) return;
        try {
            if (groupSolitaireSendMethod != null && groupSolitairePluginClass != null) return;
            List<Class<?>> candidates = new ArrayList<>();
            collectSendTextClassCandidates(candidates, 20,
                    new String[][]{
                            {"sendGroupSolitatire() content ret:%s", "PluginGroupSolitaire"},
                            {"GroupSolitaire", "sendGroupSolitatire"},
                            {"solitaire_info", "PluginGroupSolitaire"}
                    });
            collectSendTextMethodOwnerCandidates(candidates, 20,
                    new String[][]{
                            {"sendGroupSolitatire() content ret:%s", "PluginGroupSolitaire"},
                            {"sendGroupSolitatire"}
                    });
            for (Class<?> candidate : candidates) {
                Method method = findGroupSolitaireSendMethod(candidate);
                if (method == null) continue;
                groupSolitairePluginClass = candidate;
                groupSolitaireSendMethod = method;
                break;
            }
            logDetail("接龙发送API: "
                    + (groupSolitairePluginClass != null ? groupSolitairePluginClass.getName() : "null")
                    + " method=" + methodName(groupSolitaireSendMethod));
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveGroupSolitaireApi 失败: " + e.getMessage(), e);
        }
    }

    private Method findGroupSolitaireSendMethod(Class<?> cl) {
        if (cl == null) return null;
        for (Method method : KavaReflector.declaredMethods(cl)) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 6) continue;
            if (params[0] != String.class || params[1] != String.class) continue;
            if (!params[2].getName().equals("jh2.a")
                    || !params[3].getName().equals("jh2.a")
                    || !params[4].getName().equals("jh2.a")) {
                continue;
            }
            if (params[5] != boolean.class && params[5] != Boolean.class) continue;
            return KavaReflector.accessible(method);
        }
        return null;
    }

    private Method findLocalMessageCreateTimeMethod(Class<?> owner) {
        if (owner == null) return null;
        Method fallback = null;
        for (Method method : KavaReflector.declaredMethods(owner)) {
            if (!isLocalMessageCreateTimeMethod(method)) continue;
            if ("m".equals(method.getName())) {
                return KavaReflector.accessible(method);
            }
            if (fallback == null) {
                fallback = KavaReflector.accessible(method);
            }
        }
        return fallback;
    }

    private boolean isLocalMessageCreateTimeMethod(Method method) {
        if (method == null || method.getReturnType() != long.class) return false;
        if (!KavaReflector.isStatic(method)) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 2
                && params[0] == String.class
                && (params[1] == long.class || params[1] == Long.class);
    }

    private Constructor<?> findCtorByExactTypes(Class<?> cl, Class<?>... types) {
        if (cl == null || types == null) return null;
        for (Constructor<?> c : KavaReflector.declaredConstructors(cl)) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length != types.length) continue;
            boolean match = true;
            for (int i = 0; i < params.length; i++) {
                if (params[i] != types[i]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return c;
            }
        }
        return null;
    }

    private void collectSendTextClassCandidates(List<Class<?>> out, int maxCount, String[][] anchors) {
        if (out == null || anchors == null) return;
        for (String[] anchor : anchors) {
            try {
                List<ClassData> classes = dexKit.findClass(mkClassUsingStrings(anchor));
                int max = Math.min(classes.size(), maxCount);
                for (int i = 0; i < max; i++) {
                    try {
                        Class<?> cl = KavaReflector.loadClass(classes.get(i).getName(), classLoader);
                        if (!out.contains(cl)) out.add(cl);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
    }

    private void collectSendTextMethodOwnerCandidates(List<Class<?>> out, int maxCount, String[][] anchors) {
        if (out == null || anchors == null) return;
        for (String[] anchor : anchors) {
            try {
                List<MethodData> methods = dexKit.findMethod(mkMethodUsingStrings(anchor));
                int max = Math.min(methods.size(), maxCount);
                for (int i = 0; i < max; i++) {
                    try {
                        Class<?> cl = KavaReflector.loadClass(methods.get(i).getClassName(), classLoader);
                        if (!out.contains(cl)) out.add(cl);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
    }

    private void collectAppMsgLogicCandidates(List<Class<?>> out, int maxCount, String[][] anchors) {
        if (out == null || anchors == null) return;
        for (String[] anchor : anchors) {
            try {
                List<MethodData> methods = dexKit.findMethod(mkMethodUsingStrings(anchor));
                int max = Math.min(methods.size(), maxCount);
                for (int i = 0; i < max; i++) {
                    try {
                        Class<?> cl = KavaReflector.loadClass(methods.get(i).getClassName(), classLoader);
                        if (findSendFileAppMsgMethod(cl) != null && !out.contains(cl)) {
                            out.add(cl);
                        }
                    } catch (Throwable ignored) {}
                }
                if (!out.isEmpty()) return;
            } catch (Throwable ignored) {}
        }
    }

    private void addNetQueueCandidate(Class<?> cl) {
        if (cl != null && !netQueueCandidateClasses.contains(cl)) {
            netQueueCandidateClasses.add(cl);
        }
    }

    private Class<?> findFirstLikelyQueueClass() {
        for (Class<?> cl : netQueueCandidateClasses) {
            if (hasQueueSendMethodForRequests(cl)) return cl;
        }
        return null;
    }

    private boolean hasLikelyQueueSendMethod(Class<?> cl) {
        if (cl == null) return false;
        try {
            for (Method method : KavaReflector.declaredMethods(cl)) {
                String name = method.getName();
                if ("equals".equals(name) || "hashCode".equals(name) || "toString".equals(name)
                        || "wait".equals(name) || "notify".equals(name) || "notifyAll".equals(name)
                        || "cancel".equals(name)) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params == null || (params.length != 1 && params.length != 2)) continue;
                if (params.length == 2 && params[1] != int.class && params[1] != Integer.class) {
                    continue;
                }
                Class<?> first = params[0];
                if (first == null || first.isPrimitive() || first == String.class || first == Object.class) {
                    continue;
                }
                Class<?> rt = method.getReturnType();
                if (rt == boolean.class || rt == Boolean.class
                        || rt == int.class || rt == Integer.class
                        || rt == void.class) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private boolean hasQueueSendMethodForRequests(Class<?> cl) {
        return hasQueueSendMethodForRequest(cl, receiveLuckyMoneyClass)
                || hasQueueSendMethodForRequest(cl, receiveLuckyMoneyUnionClass)
                || hasQueueSendMethodForRequest(cl, openLuckyMoneyClass)
                || hasQueueSendMethodForRequest(cl, openLuckyMoneyUnionClass);
    }

    private boolean hasQueueSendMethodForRequest(Class<?> cl, Class<?> requestClass) {
        if (cl == null || requestClass == null) return false;
        try {
            for (Method method : KavaReflector.declaredMethods(cl)) {
                String name = method.getName();
                if ("equals".equals(name) || "hashCode".equals(name) || "toString".equals(name)
                        || "wait".equals(name) || "notify".equals(name) || "notifyAll".equals(name)
                        || "cancel".equals(name)) continue;
                Class<?>[] params = method.getParameterTypes();
                if (params == null || (params.length != 1 && params.length != 2)) continue;
                if (params.length == 2 && params[1] != int.class && params[1] != Integer.class) continue;
                Class<?> first = params[0];
                if (first == null || first.isPrimitive() || first == String.class || first == Object.class) continue;
                if (!first.isAssignableFrom(requestClass) && !requestClass.isAssignableFrom(first)) continue;
                Class<?> rt = method.getReturnType();
                if (rt == boolean.class || rt == Boolean.class || rt == int.class
                        || rt == Integer.class || rt == void.class) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void logMissingCritical() {
        if (addMsgClasses == null || addMsgClasses.isEmpty()) {
            h.Hchat.utils.HLog.e(TAG + " AddMsg类未找到");
        }
        if (receiveLuckyMoneyClass == null) {
            h.Hchat.utils.HLog.e(TAG + " 收红包类未找到");
        }
        if (openLuckyMoneyClass == null) {
            h.Hchat.utils.HLog.e(TAG + " 拆红包类未找到");
        }
        if (netQueueClass == null) {
            h.Hchat.utils.HLog.e(TAG + " 网络队列类未找到");
        }
        if (sendTextMsgClass == null) {
            h.Hchat.utils.HLog.e(TAG + " 文本发送类未找到");
        }
        if (sqliteDbWrapperClass == null) {
            h.Hchat.utils.HLog.e(TAG + " 数据库wrapper未找到");
        }
            if (chatPageStartMethod == null
                    || chatPageFragmentEnterMethod == null
                    || chatPageFragmentExitMethod == null) {
                h.Hchat.utils.HLog.e(TAG + " 聊天页API方法未找到: start="
                        + methodName(chatPageStartMethod)
                        + " fragmentEnter=" + methodName(chatPageFragmentEnterMethod)
                        + " fragmentExit=" + methodName(chatPageFragmentExitMethod));
        }
    }

    // ============ Protobuf 通用抓包/发包 ============
    // ============ ECS 礼物静默处理 ============
    private void resolveEcsGiftApi() {
        if (ecsGiftLocatorResolved) return;
        if (ecsGiftTaskClass != null && ecsGiftServiceClass != null && ecsGiftMsgClass != null) {
            ecsGiftLocatorResolved = true;
            return;
        }
        try {
            for (MethodData md : dexKit.findMethod(mkMethodUsingStrings("updateGiftMsgByCgi"))) {
                Class<?> task = KavaReflector.loadClass(md.getClassName(), classLoader);
                if (task == null) continue;
                for (Constructor<?> ctor : KavaReflector.declaredConstructors(task)) {
                    Class<?>[] ps = ctor.getParameterTypes();
                    if (ps == null || ps.length != 2) continue;
                    Class<?> msg = null;
                    Class<?> svc = null;
                    for (Class<?> pt : ps) {
                        if (pt.getName() != null && pt.getName().startsWith("com.tencent.mm.storage.")) msg = pt;
                        else svc = pt;
                    }
                    if (msg == null || svc == null) continue;
                    ecsGiftTaskClass = task;
                    ecsGiftServiceClass = svc;
                    ecsGiftMsgClass = msg;
                    logDetail("ECS礼物静默类: task=" + task.getName() + " svc=" + svc.getName() + " msg=" + msg.getName());
                    return;
                }
            }
            logDetail("ECS礼物静默类未定位到");
            ecsGiftLocatorResolved = true;
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveEcsGiftApi 失败: " + e.getMessage(), e);
        }
    }

    private void resolveProtobufPacketApi() {
        if (protobufLocatorResolved) return;
        try {
            if (protobufBaseClass == null) protobufBaseClass = findProtobufBaseClass();
            Class<?> protoBase = protobufBaseClass;
            if (protoBase == null) {
                protobufLocatorResolved = true;
                return;
            }
            if (protobufRawReqClass == null) protobufRawReqClass = findRawReqClass();
            if (protobufNewSendMsgReqClass == null) protobufNewSendMsgReqClass = findNewSendMsgReqClass(protoBase);
            if (protobufOplogReqClass == null) protobufOplogReqClass = findOplogReqClass(protoBase);
            if (protobufGenericRespClass == null) protobufGenericRespClass = findGenericRespClass();
            if (protobufConfigBuilderClass == null) protobufConfigBuilderClass = findConfigBuilderClass(protoBase);
            if (protobufNetSceneBaseClass == null) protobufNetSceneBaseClass = findProtobufNetSceneBaseClass();
            if (protobufCallbackClass != null && !protobufCallbackClass.isInterface()) {
                protobufCallbackClass = null;
                protobufStaticDispatchMethod = null;
            }
            if (protobufReqRespClass == null || protobufCallbackClass == null) resolveProtobufCallbackApi();
            if (protobufOnGYNetEndClass == null) protobufOnGYNetEndClass = findOnGYNetEndClass();
            if (protobufReqRespClass == null) protobufReqRespClass = findReqRespClassFromConfigBuilder();
            resolveProtobufDispatchApi();
            if (protobufSceneEndMethods == null || protobufSceneEndMethods.isEmpty()) {
                protobufSceneEndMethods = findProtobufSceneEndMethods();
            }
            logDetail("Protobuf包API: raw=" + className(protobufRawReqClass)
                    + " sendMsg=" + className(protobufNewSendMsgReqClass)
                    + " oplog=" + className(protobufOplogReqClass)
                    + " resp=" + className(protobufGenericRespClass)
                    + " builder=" + className(protobufConfigBuilderClass)
                    + " reqResp=" + className(protobufReqRespClass)
                    + " cb=" + className(protobufCallbackClass)
                    + " gy=" + className(protobufOnGYNetEndClass)
                    + " scene=" + className(protobufNetSceneBaseClass)
                    + " dispatch=" + methodName(protobufStaticDispatchMethod)
                    + " sceneEnd=" + (protobufSceneEndMethods == null ? 0 : protobufSceneEndMethods.size()));
            protobufLocatorResolved = true;
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " resolveProtobufPacketApi 失败: " + e.getMessage(), e);
        }
    }

    private Class<?> findProtobufBaseClass() {
        try {
            FindClass fc = new FindClass();
            ClassMatcher cm = new ClassMatcher();
            cm.usingEqStrings("Cannot use this method");
            MethodsMatcher methods = new MethodsMatcher();
            methods.add(new MethodMatcher().name("op").paramTypes("int", "java.lang.Object[]"));
            cm.methods(methods);
            fc.matcher(cm);
            for (ClassData cd : dexKit.findClass(fc)) {
                try {
                    Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                    if (cl != null) return cl;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Class<?> findRawReqClass() {
        try {
            FindClass fc = new FindClass();
            ClassMatcher cm = new ClassMatcher();
            cm.fieldCount(1);
            cm.addFieldForType(byte[].class);
            cm.addMethod(new MethodMatcher().name("<init>").paramTypes("byte[]"));
            cm.addMethod(new MethodMatcher().name("toByteArray").returnType("byte[]"));
            fc.matcher(cm);
            for (ClassData cd : dexKit.findClass(fc)) {
                try {
                    Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                    if (KavaReflector.findConstructor(cl, byte[].class) != null
                            && KavaReflector.findMethod(cl, "toByteArray") != null) {
                        return cl;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Class<?> findOplogReqClass(Class<?> protoBase) {
        if (protoBase == null) return null;
        try {
            FindClass fc = new FindClass();
            ClassMatcher cm = new ClassMatcher();
            cm.superClass(protoBase.getName());
            cm.usingStrings("/cgi-bin/micromsg-bin/oplog");
            cm.fieldCount(1);
            cm.addMethod(new MethodMatcher().name("op").paramTypes("int", "java.lang.Object[]"));
            fc.matcher(cm);
            for (ClassData cd : dexKit.findClass(fc)) {
                try {
                    Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                    if (cl != null && protoBase.isAssignableFrom(cl)) return cl;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            FindClass fc = new FindClass();
            ClassMatcher cm = new ClassMatcher();
            MethodsMatcher methods = new MethodsMatcher();
            methods.add(new MethodMatcher().name("getFuncId").returnType(int.class).usingNumbers(681));
            methods.add(new MethodMatcher().name("toProtoBuf").returnType(byte[].class));
            cm.methods(methods);
            fc.matcher(cm);
            for (ClassData cd : dexKit.findClass(fc)) {
                try {
                    Class<?> wrapper = KavaReflector.loadClass(cd.getName(), classLoader);
                    for (java.lang.reflect.Field field : KavaReflector.declaredFields(wrapper)) {
                        Class<?> type = field.getType();
                        if (type != null
                                && type != int.class
                                && type != Integer.class
                                && protoBase.isAssignableFrom(type)) {
                            return type;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Class<?> findNewSendMsgReqClass(Class<?> protoBase) {
        Class<?> wrapperBase = protobufRawReqClass != null ? protobufRawReqClass.getSuperclass() : null;
        if (wrapperBase == null) return null;
        try {
            FindClass fc = new FindClass();
            ClassMatcher cm = new ClassMatcher();
            cm.superClass(wrapperBase.getName());
            cm.fieldCount(2);
            cm.addFieldForType(int.class);
            cm.addFieldForType(java.util.LinkedList.class);
            fc.matcher(cm);
            for (ClassData cd : dexKit.findClass(fc)) {
                try {
                    Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                    if (cl != null && hasParseFromMethod(cl)) return cl;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            FindClass fc = mkClassUsingStrings("/cgi-bin/micromsg-bin/newsendmsg");
            for (ClassData cd : dexKit.findClass(fc)) {
                try {
                    Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                    if (cl != null
                            && protoBase.isAssignableFrom(cl)
                            && hasParseFromMethod(cl)) {
                        return cl;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean hasParseFromMethod(Class<?> cl) {
        return KavaReflector.findMethodRecursive(cl, "parseFrom", byte[].class) != null;
    }

    public Class<?> findNativeNetSceneClass(String uri, int cgiId) {
        if (uri == null || uri.length() == 0 || cgiId <= 0) return null;
        try {
            FindClass fc = new FindClass();
            ClassMatcher cm = new ClassMatcher();
            cm.usingStrings(uri);
            MethodsMatcher methods = new MethodsMatcher();
            methods.add(new MethodMatcher().name("getType").returnType(int.class).usingNumbers(cgiId));
            cm.methods(methods);
            fc.matcher(cm);
            for (ClassData cd : dexKit.findClass(fc)) {
                Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                if (isNativeNetSceneCandidate(cl, cgiId)) {
                    logDetail("原生NetScene定位: uri=" + uri + " type=" + cgiId + " class=" + cl.getName());
                    return cl;
                }
            }
        } catch (Throwable ignored) {}

        try {
            FindMethod fm = mkMethodUsingStrings(uri);
            for (MethodData md : dexKit.findMethod(fm)) {
                Class<?> cl = KavaReflector.loadClass(md.getClassName(), classLoader);
                if (isNativeNetSceneCandidate(cl, cgiId)) {
                    logDetail("原生NetScene定位: uri=" + uri + " type=" + cgiId + " class=" + cl.getName());
                    return cl;
                }
            }
        } catch (Throwable e) {
            h.Hchat.utils.HLog.e(TAG + " findNativeNetSceneClass 失败: uri=" + uri + " type=" + cgiId + " | " + e.getMessage(), e);
        }
        return null;
    }

    private boolean isNativeNetSceneCandidate(Class<?> cl, int cgiId) {
        if (cl == null) return false;
        if (protobufNetSceneBaseClass != null && !protobufNetSceneBaseClass.isAssignableFrom(cl)) {
            return false;
        }
        try {
            Method method = KavaReflector.findMethodRecursive(cl, "getType");
            if (method != null && method.getParameterTypes().length == 0) {
                Object value = KavaReflector.invoke(method, KavaReflector.newInstance(KavaReflector.findConstructor(cl)));
                if (value instanceof Number && ((Number) value).intValue() == cgiId) return true;
            }
        } catch (Throwable ignored) {}
        try {
            for (Method method : KavaReflector.declaredMethods(cl)) {
                if (!"getType".equals(method.getName())) continue;
                if (method.getParameterTypes().length != 0) continue;
                if (method.getReturnType() == int.class || method.getReturnType() == Integer.class) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private Class<?> findGenericRespClass() {
        try {
            FindClass fc = new FindClass();
            ClassMatcher cm = new ClassMatcher();
            cm.fieldCount(0, 1);
            cm.addMethod(new MethodMatcher().name("<init>"));
            cm.addMethod(new MethodMatcher().name("op").paramTypes("int", "java.lang.Object[]").returnType("int"));
            fc.matcher(cm);
            for (ClassData cd : dexKit.findClass(fc)) {
                try {
                    Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                    if (KavaReflector.findConstructor(cl) != null
                            && KavaReflector.findMethod(cl, "op", int.class, Object[].class) != null) {
                        return cl;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Class<?> findConfigBuilderClass(Class<?> protoBase) {
        if (protoBase == null) return null;
        try {
            FindClass fc = new FindClass();
            ClassMatcher cm = new ClassMatcher();
            cm.superClass("java.lang.Object");
            cm.fieldCount(10, 80);
            cm.addFieldForType(protoBase);
            cm.addFieldForType(protoBase);
            cm.addFieldForType(String.class);
            fc.matcher(cm);
            for (ClassData cd : dexKit.findClass(fc)) {
                try {
                    Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                    if (cl != null && hasConfigBuilderShape(cl, protoBase)) return cl;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean hasConfigBuilderShape(Class<?> cl, Class<?> protoBase) {
        if (cl == null || protoBase == null) return false;
        int protoFields = 0;
        int stringFields = 0;
        int intFields = 0;
        boolean hasBuild = false;
        for (java.lang.reflect.Field field : KavaReflector.declaredFields(cl)) {
            Class<?> type = field.getType();
            if (protoBase.isAssignableFrom(type)) protoFields++;
            if (type == String.class) stringFields++;
            if (type == int.class || type == Integer.class) intFields++;
        }
        for (Method method : KavaReflector.declaredMethods(cl)) {
            if (method.getParameterTypes().length == 0 && method.getReturnType() != void.class) {
                hasBuild = true;
                break;
            }
        }
        return protoFields >= 2 && stringFields >= 1 && intFields >= 3 && hasBuild;
    }

    private Class<?> findReqRespClassFromConfigBuilder() {
        if (protobufConfigBuilderClass == null) return null;
        for (Method method : KavaReflector.declaredMethods(protobufConfigBuilderClass)) {
            try {
                if (method.getParameterTypes().length != 0) continue;
                Class<?> rt = method.getReturnType();
                if (rt == null || rt == void.class || rt.isPrimitive() || rt == String.class) continue;
                if (hasReqRespShape(rt)) return rt;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private boolean hasReqRespShape(Class<?> cl) {
        if (cl == null) return false;
        boolean hasType = false;
        boolean hasUri = false;
        boolean hasReq = false;
        boolean hasResp = false;
        for (Method method : KavaReflector.declaredMethods(cl)) {
            if (method.getParameterTypes().length != 0) continue;
            if ("getType".equals(method.getName()) && method.getReturnType() == int.class) hasType = true;
            if ("getUri".equals(method.getName()) && method.getReturnType() == String.class) hasUri = true;
            if ("getReqObj".equals(method.getName()) && method.getReturnType() != void.class) hasReq = true;
            if ("getRespObj".equals(method.getName()) && method.getReturnType() != void.class) hasResp = true;
        }
        return hasType && hasUri && hasReq && hasResp;
    }

    private Class<?> findProtobufNetSceneBaseClass() {
        try {
            FindClass fc = mkClassUsingStrings("MicroMsg.NetSceneBase");
            for (ClassData cd : dexKit.findClass(fc)) {
                try {
                    Class<?> cl = KavaReflector.loadClass(cd.getName(), classLoader);
                    if (isProtobufNetSceneBaseClass(cl)) return cl;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        if (packetBaseClasses != null && !packetBaseClasses.isEmpty()) {
            return packetBaseClasses.get(0);
        }
        return null;
    }

    private boolean isProtobufNetSceneBaseClass(Class<?> cl) {
        if (cl == null) return false;
        for (Method method : KavaReflector.declaredMethods(cl)) {
            try {
                if ("dispatch".equals(method.getName())
                        && method.getParameterTypes().length == 3
                        && method.getReturnType() == int.class) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private void resolveProtobufCallbackApi() {
        if (protobufNetSceneBaseClass == null) return;
        try {
            FindMethod fm = new FindMethod();
            MethodMatcher mm = new MethodMatcher();
            mm.name("callback");
            mm.returnType(int.class);
            mm.paramCount(5);
            fm.matcher(mm);
            for (MethodData md : dexKit.findMethod(fm)) {
                try {
                    Method method = md.getMethodInstance(classLoader);
                    if (!isProtobufCallbackMethod(method)) continue;
                    protobufCallbackClass = method.getDeclaringClass();
                    protobufReqRespClass = method.getParameterTypes()[3];
                    return;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private boolean isProtobufCallbackMethod(Method method) {
        if (method == null) return false;
        if (!"callback".equals(method.getName()) || method.getReturnType() != int.class) return false;
        Class<?> owner = method.getDeclaringClass();
        Class<?>[] params = method.getParameterTypes();
        return owner != null
                && owner.isInterface()
                && params.length == 5
                && params[0] == int.class
                && params[1] == int.class
                && params[2] == String.class
                && params[3] != null
                && params[3] != Object.class
                && protobufNetSceneBaseClass != null
                && params[4] == protobufNetSceneBaseClass;
    }

    private Class<?> findOnGYNetEndClass() {
        try {
            FindMethod fm = new FindMethod();
            MethodMatcher mm = new MethodMatcher();
            mm.name("onGYNetEnd");
            mm.paramCount(6);
            fm.matcher(mm);
            for (MethodData md : dexKit.findMethod(fm)) {
                try {
                    Method method = md.getMethodInstance(classLoader);
                    if (isOnGYNetEndMethod(method)) {
                        return method.getDeclaringClass();
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isOnGYNetEndMethod(Method method) {
        if (method == null || !"onGYNetEnd".equals(method.getName())) return false;
        Class<?> owner = method.getDeclaringClass();
        Class<?>[] params = method.getParameterTypes();
        return owner != null
                && owner.isInterface()
                && params != null
                && params.length == 6
                && params[0] == int.class
                && params[1] == int.class
                && params[2] == int.class
                && params[3] == String.class;
    }

    private List<Method> findProtobufSceneEndMethods() {
        List<Method> result = new ArrayList<>();
        if (protobufNetSceneBaseClass == null) return result;
        try {
            FindMethod fm = new FindMethod();
            MethodMatcher mm = new MethodMatcher();
            mm.name("onSceneEnd");
            mm.paramCount(4);
            fm.matcher(mm);
            for (MethodData md : dexKit.findMethod(fm)) {
                try {
                    Method method = md.getMethodInstance(classLoader);
                    if (isProtobufSceneEndMethod(method) && !result.contains(method)) {
                        result.add(KavaReflector.accessible(method));
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return result;
    }

    private boolean isProtobufSceneEndMethod(Method method) {
        if (method == null || !"onSceneEnd".equals(method.getName())) return false;
        Class<?> owner = method.getDeclaringClass();
        Class<?>[] params = method.getParameterTypes();
        return owner != null
                && !owner.isInterface()
                && method.getReturnType() == void.class
                && params != null
                && params.length == 4
                && params[0] == int.class
                && params[1] == int.class
                && params[2] == String.class
                && protobufNetSceneBaseClass != null
                && protobufNetSceneBaseClass.isAssignableFrom(params[3]);
    }

    private void resolveProtobufDispatchApi() {
        try {
            if (protobufReqRespClass != null && protobufCallbackClass != null && protobufStaticDispatchMethod != null) {
                return;
            }
            if (protobufReqRespClass == null || protobufCallbackClass == null) return;
            Method dispatch = findStaticDispatch(protobufReqRespClass, protobufCallbackClass);
            if (dispatch != null) {
                protobufStaticDispatchMethod = dispatch;
            }
        } catch (Throwable ignored) {}
    }

    private Method findStaticDispatch(Class<?> reqResp, Class<?> callback) {
        if (reqResp == null || callback == null) return null;
        Method broad = findStaticDispatchByName(reqResp, callback);
        if (broad != null) return broad;
        try {
            FindMethod fm = new FindMethod();
            MethodMatcher mm = new MethodMatcher();
            mm.paramCount(3);
            mm.addParamType(reqResp);
            mm.addParamType(callback);
            mm.addParamType(boolean.class);
            fm.matcher(mm);
            for (MethodData md : dexKit.findMethod(fm)) {
                try {
                    Method method = md.getMethodInstance(classLoader);
                    Class<?>[] params = method != null ? method.getParameterTypes() : null;
                    if (method != null
                            && KavaReflector.isStatic(method)
                            && params != null
                            && params.length == 3
                            && params[0] == reqResp
                            && params[1] == callback
                            && params[2] == boolean.class) {
                        return KavaReflector.accessible(method);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private Method findStaticDispatchByName(Class<?> reqResp, Class<?> callback) {
        try {
            FindMethod fm = new FindMethod();
            MethodMatcher mm = new MethodMatcher();
            mm.name("d");
            mm.paramCount(3);
            fm.matcher(mm);
            for (MethodData md : dexKit.findMethod(fm)) {
                try {
                    Method method = md.getMethodInstance(classLoader);
                    if (isProtobufStaticDispatch(method, reqResp, callback)) {
                        return KavaReflector.accessible(method);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isProtobufStaticDispatch(Method method, Class<?> reqResp, Class<?> callback) {
        if (method == null || reqResp == null || callback == null) return false;
        Class<?>[] params = method.getParameterTypes();
        return KavaReflector.isStatic(method)
                && params != null
                && params.length == 3
                && params[0] == reqResp
                && params[1] == callback
                && params[2] == boolean.class
                && protobufNetSceneBaseClass != null
                && method.getReturnType() == protobufNetSceneBaseClass;
    }

    private boolean isChatPageStartMethod(Method method) {
        if (method == null || method.getReturnType() != void.class) return false;
        Class<?>[] params = method.getParameterTypes();
        return params.length == 3
                && params[0] == String.class
                && params[1] == Bundle.class
                && (params[2] == boolean.class || params[2] == Boolean.class);
    }

    private boolean isNoArgVoidMethod(Method method) {
        return method != null
                && method.getReturnType() == void.class
                && method.getParameterTypes().length == 0;
    }

    private boolean isChatFooterSendClickMethod(Method method) {
        if (method == null) return false;
        if (!"onClick".equals(method.getName())) return false;
        if (method.getReturnType() != void.class) return false;
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 1 || params[0] != android.view.View.class) return false;
        Class<?> owner = method.getDeclaringClass();
        if (owner == null) return false;
        if (!"com.tencent.mm.pluginsdk.ui.chat".equals(owner.getPackage() != null ? owner.getPackage().getName() : "")) {
            return false;
        }
        for (java.lang.reflect.Field field : KavaReflector.declaredFields(owner)) {
            Class<?> type = field.getType();
            if (type != null && "com.tencent.mm.pluginsdk.ui.chat.ChatFooter".equals(type.getName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isGetContactAddMethod(Method method) {
        if (method == null || method.getReturnType() != void.class) return false;
        Class<?>[] params = method.getParameterTypes();
        if (params == null) return false;
        if (params.length == 2) {
            return params[0] == String.class && params[1] == String.class;
        }
        return params.length == 3
                && params[0] == String.class
                && params[1] == String.class
                && (params[2] == int.class || params[2] == Integer.class);
    }

    private boolean isGetContactServiceGetter(Method method, Class<?> serviceType) {
        if (method == null || serviceType == null) return false;
        return KavaReflector.isStatic(method)
                && method.getParameterTypes().length == 0
                && serviceType.isAssignableFrom(method.getReturnType());
    }

    private String methodName(Method method) {
        if (method == null) return "null";
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }

    private String className(Class<?> clazz) {
        return clazz != null ? clazz.getName() : "null";
    }

    private void logDetail(String message) {
        if (VERBOSE) {
            XposedBridge.log(TAG + " " + message);
        }
    }

    private void collectKnownNetworkQueueClasses() {
        String[] names = {
                "tk0.j1",
                "gp0.j1",
                "com.tencent.mm.modelbase.r1",
                "com.tencent.mm.kernel.h",
                "com.tencent.mm.kernel.g",
                "com.tencent.mm.model.bh",
                "com.tencent.mm.model.ak",
                "com.tencent.mm.model.az"
        };
        for (String name : names) {
            try {
                addNetQueueCandidate(KavaReflector.loadClass(name, classLoader));
            } catch (Throwable ignored) {}
        }
    }

    private void collectNetworkQueueClassesByAnchors() {
        String[][] anchors = {
                {"MicroMsg.NetSceneQueue"},
                {"NetSceneQueue"},
                {"doSceneImp start"},
                {"doSceneImp mmcgi"},
                {"On doscene  mmcgi"},
                {"doscene mmcgi Failed"},
                {"waitingQueue_size"},
                {"MicroMsg.MMKernel"},
                {"Kernel not initialized by MMApplication"},
                {"Initialize kernel, create whole WeChat world"},
                {"mCoreNetwork not initialized"},
                {"MMKernel.CoreNetwork"},
                {"MicroMsg.CoreNetwork"},
                {"doSceneImp err"},
                {"dispatcher is null"}
        };
        for (String[] anchor : anchors) {
            try {
                List<ClassData> classes = dexKit.findClass(mkClassUsingStrings(anchor));
                int max = Math.min(classes.size(), 30);
                for (int i = 0; i < max; i++) {
                    try {
                        addNetQueueCandidate(KavaReflector.loadClass(classes.get(i).getName(), classLoader));
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
    }
}
