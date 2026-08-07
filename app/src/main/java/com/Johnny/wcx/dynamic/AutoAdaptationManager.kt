package com.Johnny.wcx.dynamic

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.cache.DexCacheManager
import com.Johnny.wcx.dexkit.dsl.BaseDexDelegate
import com.Johnny.wcx.features.core.BaseFeature
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import com.Johnny.wcx.utils.reflection.DexKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.luckypray.dexkit.DexKitBridge
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

/**
 * 全自动适配管理器 — 动态适配引擎的总控中心。
 *
 * 核心职责：
 * 1. 检测微信版本升级/APK 变更，自动触发后台静默适配
 * 2. 调度动态扫描引擎扫描微信字节码
 * 3. 自动匹配全部功能 Hook 并完成注入
 * 4. 管理适配缓存和云端特征同步
 * 5. 监控适配状态，自动修复故障
 *
 * 取消手动"完美适配"按钮，全程全自动。
 */

object AutoAdaptationManager {

    private const val TAG = "AutoAdaptationManager"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // 适配状态
    enum class AdaptationState {
        IDLE,           // 空闲
        SCANNING,       // 正在扫描
        INJECTING,      // 正在注入
        COMPLETED,      // 适配完成
        PARTIAL,        // 部分适配（某些功能使用备用链路）
        FAILED          // 适配失败
    }

    private var state = AdaptationState.IDLE
    private var lastAdaptedVersion = ""
    private val featureClassFeatures = mutableMapOf<String, ClassFeature>()
    private val scanResults = mutableMapOf<String, ScanResult>()

    @Volatile
    private var isInitialized = false

    // 适配完成回调
    private val onAdaptationCompleteCallbacks = mutableListOf<() -> Unit>()

    /**
     * 初始化适配管理器。
     * 在模块启动时调用一次。
     */
    fun init() {
        if (isInitialized) return
        isInitialized = true

        WeLogger.i(TAG, "initializing auto adaptation manager")
        WeLogger.i(TAG, "wechat version: ${HostInfo.versionName}")

        // 初始化云端特征库
        CloudFeatureDB.init(HostInfo.versionName)

        // 注册所有功能的 ClassFeature
        registerAllFeatures()

        // 检查是否需要重新适配
        checkAndAdapt()
    }

    /**
     * 注册适配完成回调。
     */
    fun onAdaptationComplete(callback: () -> Unit) {
        onAdaptationCompleteCallbacks.add(callback)
        if (state == AdaptationState.COMPLETED || state == AdaptationState.PARTIAL) {
            callback()
        }
    }

    /**
     * 获取当前适配状态。
     */
    fun getState(): AdaptationState = state

    /**
     * 获取适配统计信息。
     */
    fun getStats(): Map<String, Any> {
        return mapOf(
            "state" to state.name,
            "lastAdaptedVersion" to lastAdaptedVersion,
            "currentVersion" to HostInfo.versionName,
            "totalFeatures" to featureClassFeatures.size,
            "scanResults" to scanResults.size,
            "fallbackStats" to DynamicFallbackChain.getStats(),
            "renderStatus" to RenderMonitor.getStatus()
        )
    }

    /**
     * 强制重新适配（用于调试）。
     */
    fun forceReadapt() {
        WeLogger.i(TAG, "force re-adaptation requested")
        DexCacheManager.clearAllCache()
        DynamicFallbackChain.resetAll()
        RenderMonitor.reset()
        scanResults.clear()
        state = AdaptationState.IDLE
        lastAdaptedVersion = ""
        checkAndAdapt()
    }

    /**
     * 重试单个功能的适配。
     */
    fun retryFeature(featureName: String) {
        WeLogger.i(TAG, "retrying feature: $featureName")
        DynamicFallbackChain.reset(featureName)

        val feature = FeaturesProvider.ALL_HOOK_ITEMS.find { it.name == featureName }
        val classFeature = featureClassFeatures[featureName]

        if (feature != null && classFeature != null) {
            scope.launch {
                try {
                    val dexKit = withContext(Dispatchers.IO) {
                        acquireDexKitBridge()
                    }
                    if (dexKit != null) {
                        val result = DynamicClassScanner.scan(dexKit, classFeature)
                        if (result != null) {
                            scanResults[featureName] = result
                            DynamicHookInjector.inject(feature, result)
                            WeLogger.i(TAG, "retry successful for $featureName")
                        } else {
                            DynamicFallbackChain.handleFailure(feature)
                        }
                    }
                } catch (e: Exception) {
                    WeLogger.e(TAG, "retry failed for $featureName", e)
                    DynamicFallbackChain.handleFailure(feature, e)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------------

    /**
     * 检查微信版本，决定是否需要重新适配。
     */
    private fun checkAndAdapt() {
        val currentVersion = HostInfo.versionName
        if (currentVersion == lastAdaptedVersion && state == AdaptationState.COMPLETED) {
            WeLogger.i(TAG, "already adapted for version $currentVersion, skipping")
            return
        }

        WeLogger.i(TAG, "version changed or first run: $lastAdaptedVersion -> $currentVersion")
        startAdaptation()
    }

    /**
     * 启动全自动适配流程。
     */
    private fun startAdaptation() {
        if (state == AdaptationState.SCANNING || state == AdaptationState.INJECTING) {
            WeLogger.w(TAG, "adaptation already in progress, state=$state")
            return
        }

        state = AdaptationState.SCANNING

        scope.launch {
            try {
                val elapsed = measureTime {
                    performAdaptation()
                }
                WeLogger.i(TAG, "adaptation completed in $elapsed")
            } catch (e: Exception) {
                WeLogger.e(TAG, "adaptation failed", e)
                state = AdaptationState.FAILED
            }
        }
    }

    /**
     * 执行适配流程：
     * 1. 获取 DexKitBridge
     * 2. 批量扫描所有功能
     * 3. 注入扫描结果
     * 4. 处理失败的功能
     * 5. 保存缓存
     * 6. 初始化兼容层
     */
    private suspend fun performAdaptation() {
        // 步骤 1: 获取 DexKitBridge
        val dexKit = withContext(Dispatchers.IO) {
            acquireDexKitBridge()
        }

        if (dexKit == null) {
            WeLogger.e(TAG, "failed to acquire DexKitBridge, aborting adaptation")
            state = AdaptationState.FAILED
            return
        }

        // 步骤 2: 批量扫描
        WeLogger.i(TAG, "scanning ${featureClassFeatures.size} features...")
        val batchResult = withContext(Dispatchers.IO) {
            DynamicClassScanner.scanBatch(dexKit, featureClassFeatures.values.toList())
        }

        scanResults.putAll(batchResult.success)
        WeLogger.i(TAG, "scan complete: ${batchResult.success.size} success, ${batchResult.failed.size} failed")

        // 步骤 3: 注入扫描结果
        state = AdaptationState.INJECTING
        val allFeatures = FeaturesProvider.ALL_HOOK_ITEMS
        val injectResults = withContext(Dispatchers.IO) {
            DynamicHookInjector.injectBatch(allFeatures, batchResult.success)
        }

        val injectSuccess = injectResults.count { it.value }
        val injectFailed = injectResults.count { !it.value }
        WeLogger.i(TAG, "injection complete: $injectSuccess success, $injectFailed failed")

        // 步骤 4: 处理失败的功能
        val failedFeatures = batchResult.failed
        for (failedFeature in failedFeatures) {
            val feature = allFeatures.find { it.name == failedFeature.id }
            if (feature != null) {
                DynamicFallbackChain.handleFailure(feature)
            }
        }

        // 步骤 5: 保存缓存
        saveScanCache()

        // 步骤 6: 初始化页面兼容层
        initPageLifecycleCompat()

        // 步骤 7: 启动所有功能
        startAllFeatures(allFeatures, batchResult)

        // 更新状态
        lastAdaptedVersion = HostInfo.versionName
        state = if (batchResult.failed.isEmpty()) {
            AdaptationState.COMPLETED
        } else {
            AdaptationState.PARTIAL
        }

        WeLogger.i(TAG, "adaptation finished: state=$state, version=${HostInfo.versionName}")

        // 通知适配完成
        onAdaptationCompleteCallbacks.forEach { it() }
    }

    /**
     * 获取 DexKitBridge 实例。
     */
    private fun acquireDexKitBridge(): DexKitBridge? {
        return try {
            DexKit
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to acquire DexKitBridge", e)
            null
        }
    }

    /**
     * 保存扫描结果到缓存。
     */
    private fun saveScanCache() {
        for ((featureId, result) in scanResults) {
            val feature = FeaturesProvider.ALL_HOOK_ITEMS.find { it.name == featureId }
            if (feature is IResolveDex) {
                try {
                    DexCacheManager.saveItemCache(feature)
                } catch (e: Exception) {
                    WeLogger.w(TAG, "failed to save cache for $featureId: ${e.message}")
                }
            }
        }
    }

    /**
     * 初始化页面生命周期兼容层。
     */
    private fun initPageLifecycleCompat() {
        try {
            val launcherUIClassName = scanResults.entries
                .firstOrNull { (id, _) -> id.contains("LauncherUI", ignoreCase = true) }
                ?.value?.className

            PageLifecycleCompat.init(launcherUIClassName)
        } catch (e: Exception) {
            WeLogger.w(TAG, "page lifecycle compat init failed: ${e.message}")
        }
    }

    /**
     * 启动所有功能，跳过仍处于故障状态的。
     */
    private fun startAllFeatures(
        allFeatures: List<BaseFeature>,
        batchResult: DynamicClassScanner.BatchScanResult
    ) {
        val failedIds = batchResult.failed.map { it.id }.toSet()

        for (feature in allFeatures) {
            val isScanFailed = feature is IResolveDex && feature.name in failedIds
            val isFallbackFailed = DynamicFallbackChain.isFailed(feature.name)

            if (isScanFailed && isFallbackFailed) {
                WeLogger.w(TAG, "skipping ${feature.displayName} — scan failed and no fallback active")
                continue
            }

            try {
                feature.startup()
            } catch (e: Exception) {
                WeLogger.e(TAG, "failed to start ${feature.displayName}", e)
                DynamicFallbackChain.handleFailure(feature, e)
            }
        }
    }

    /**
     * 注册所有功能对应的 ClassFeature 描述符。
     * 这些描述符定义了如何通过字节码特征在微信中找到目标类/方法/字段。
     */
    private fun registerAllFeatures() {
        // ===================================================================
        // 核心 UI 类特征
        // ===================================================================

        // LauncherUI — 微信主界面
        featureClassFeatures["LauncherUI"] = ClassFeature(
            id = "LauncherUI",
            superClass = "android.app.Activity",
            interfaces = listOf(
                "com.tencent.mm.ui.MMFragmentActivity",
                "com.tencent.mm.ui.base.ActivityAttribute"
            ),
            stringConstants = listOf("ChattingUI", "LauncherUI", "main"),
            methodFeatures = listOf(
                MethodFeature(
                    id = "getInstance",
                    returnType = "Lcom/tencent/mm/ui/LauncherUI;",
                    paramCount = 0,
                    isStatic = true
                )
            ),
            fallbackKeywords = listOf("LauncherUI", "MainUI", "HomeUI"),
            description = "微信主界面 LauncherUI"
        )

        // ===================================================================
        // 侧边栏相关特征
        // ===================================================================

        featureClassFeatures["HomeSidePanel"] = ClassFeature(
            id = "HomeSidePanel",
            superClass = "android.widget.FrameLayout",
            stringConstants = listOf("drawer", "side", "panel"),
            methodFeatures = listOf(
                MethodFeature(
                    id = "openDrawer",
                    returnType = "V",
                    paramCount = 0,
                    nameKeywords = listOf("open", "drawer", "side")
                ),
                MethodFeature(
                    id = "closeDrawer",
                    returnType = "V",
                    paramCount = 0,
                    nameKeywords = listOf("close", "drawer", "side")
                )
            ),
            fallbackKeywords = listOf("Drawer", "SidePanel", "SlideMenu"),
            description = "微信主页侧边栏"
        )

        // ===================================================================
        // 会话列表相关特征
        // ===================================================================

        featureClassFeatures["ConversationList"] = ClassFeature(
            id = "ConversationList",
            superClass = "android.widget.ListView",
            interfaces = listOf("android.widget.AbsListView"),
            stringConstants = listOf("conversation", "chat", "message"),
            methodFeatures = listOf(
                MethodFeature(
                    id = "getAdapter",
                    returnType = "Landroid/widget/ListAdapter;",
                    paramCount = 0
                )
            ),
            fallbackKeywords = listOf("Conversation", "ChatList", "MessageList"),
            description = "微信会话列表"
        )

        // ===================================================================
        // 通讯录相关特征
        // ===================================================================

        featureClassFeatures["ContactList"] = ClassFeature(
            id = "ContactList",
            superClass = "android.widget.ListView",
            stringConstants = listOf("contact", "friend", "address"),
            methodFeatures = listOf(
                MethodFeature(
                    id = "getAdapter",
                    returnType = "Landroid/widget/ListAdapter;",
                    paramCount = 0
                )
            ),
            fallbackKeywords = listOf("Contact", "Friend", "AddressBook"),
            description = "微信通讯录列表"
        )

        // ===================================================================
        // 朋友圈相关特征
        // ===================================================================

        featureClassFeatures["TimelineUI"] = ClassFeature(
            id = "TimelineUI",
            superClass = "android.app.Activity",
            stringConstants = listOf("timeline", "sns", "moment"),
            methodFeatures = listOf(
                MethodFeature(
                    id = "onCreate",
                    returnType = "V",
                    paramTypes = listOf("Landroid/os/Bundle;"),
                    paramCount = 1
                )
            ),
            fallbackKeywords = listOf("Timeline", "Sns", "Moment"),
            description = "微信朋友圈界面"
        )

        featureClassFeatures["SnsTimeLineUI"] = ClassFeature(
            id = "SnsTimeLineUI",
            superClass = "android.app.Activity",
            stringConstants = listOf("SnsTimeLineUI", "SnsTimeLine"),
            fallbackKeywords = listOf("SnsTimeLine", "SnsUserUI"),
            description = "朋友圈时间线"
        )

        // ===================================================================
        // CGI 抓包相关特征
        // ===================================================================

        featureClassFeatures["NetSceneBase"] = ClassFeature(
            id = "NetSceneBase",
            stringConstants = listOf("NetScene", "cgi", "doScene"),
            methodFeatures = listOf(
                MethodFeature(
                    id = "doScene",
                    returnType = "I",
                    paramCount = 2,
                    nameKeywords = listOf("doScene", "dispatch")
                )
            ),
            fallbackKeywords = listOf("NetScene", "CGI", "Network"),
            description = "微信网络请求基类"
        )

        // ===================================================================
        // 数据库相关特征
        // ===================================================================

        featureClassFeatures["SQLiteDatabase"] = ClassFeature(
            id = "SQLiteDatabase",
            stringConstants = listOf("envelope", "message", "conversation"),
            methodFeatures = listOf(
                MethodFeature(
                    id = "insert",
                    returnType = "J",
                    paramCount = 3,
                    nameKeywords = listOf("insert")
                ),
                MethodFeature(
                    id = "update",
                    returnType = "I",
                    paramCount = 4,
                    nameKeywords = listOf("update")
                ),
                MethodFeature(
                    id = "delete",
                    returnType = "I",
                    paramCount = 3,
                    nameKeywords = listOf("delete")
                )
            ),
            fallbackKeywords = listOf("Database", "SQLite", "DB"),
            description = "微信数据库操作"
        )

        // ===================================================================
        // 聊天界面相关特征
        // ===================================================================

        featureClassFeatures["ChattingUI"] = ClassFeature(
            id = "ChattingUI",
            superClass = "android.app.Activity",
            stringConstants = listOf("ChattingUI", "chatting", "message"),
            methodFeatures = listOf(
                MethodFeature(
                    id = "onCreate",
                    returnType = "V",
                    paramTypes = listOf("Landroid/os/Bundle;"),
                    paramCount = 1
                ),
                MethodFeature(
                    id = "onResume",
                    returnType = "V",
                    paramCount = 0
                )
            ),
            fallbackKeywords = listOf("Chatting", "ChatUI", "MessageUI"),
            description = "微信聊天界面"
        )

        // ===================================================================
        // WebView 相关特征
        // ===================================================================

        featureClassFeatures["WebViewUI"] = ClassFeature(
            id = "WebViewUI",
            superClass = "android.app.Activity",
            stringConstants = listOf("WebView", "webview", "url"),
            methodFeatures = listOf(
                MethodFeature(
                    id = "getWebView",
                    returnType = "Landroid/webkit/WebView;",
                    paramCount = 0,
                    nameKeywords = listOf("getWebView", "webView")
                )
            ),
            fallbackKeywords = listOf("WebView", "Browser"),
            description = "微信 WebView 界面"
        )

        // ===================================================================
        // Activity 启动相关特征
        // ===================================================================

        featureClassFeatures["ActivityStart"] = ClassFeature(
            id = "ActivityStart",
            stringConstants = listOf("startActivity", "Activity"),
            methodFeatures = listOf(
                MethodFeature(
                    id = "startActivity",
                    returnType = "V",
                    paramTypes = listOf("Landroid/content/Intent;"),
                    paramCount = 1,
                    nameKeywords = listOf("startActivity")
                )
            ),
            fallbackKeywords = listOf("Activity", "Intent", "Start"),
            description = "微信 Activity 启动逻辑"
        )

        // ===================================================================
        // 输入栏相关特征
        // ===================================================================

        featureClassFeatures["ChatFooter"] = ClassFeature(
            id = "ChatFooter",
            superClass = "android.widget.LinearLayout",
            stringConstants = listOf("chat", "footer", "input", "send"),
            methodFeatures = listOf(
                MethodFeature(
                    id = "getInputText",
                    returnType = "Ljava/lang/String;",
                    paramCount = 0,
                    nameKeywords = listOf("getText", "getInput", "text")
                ),
                MethodFeature(
                    id = "sendMessage",
                    returnType = "V",
                    paramCount = 1,
                    nameKeywords = listOf("send", "dispatch")
                )
            ),
            fallbackKeywords = listOf("ChatFooter", "InputBar", "SendPanel"),
            description = "微信聊天输入栏"
        )

        WeLogger.i(TAG, "registered ${featureClassFeatures.size} class features")
    }
}