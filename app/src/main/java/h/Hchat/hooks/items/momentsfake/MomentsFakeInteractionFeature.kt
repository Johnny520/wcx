package h.Hchat.hooks.items.momentsfake

import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.api.sns.SnsContextMenuDispatcher
import h.Hchat.hooks.api.sns.SnsForwardContentResolver
import h.Hchat.hooks.api.sns.WeChatSnsPostObserver
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.KavaReflector

class MomentsFakeInteractionFeature : BaseFeature() {
    private var runtime: MomentsFakeInteractionRuntime? = null
    private var codec: MomentsFakeInteractionCodec? = null
    private var resolver: SnsForwardContentResolver? = null
    private var forwardRuntime: MomentsFakeForwardRuntime? = null
    private var postSubscription: WeChatSnsPostObserver.Subscription? = null

    override fun featureId(): String = ID

    override fun name(): String = "朋友圈伪互动"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MomentsFakeLikeSettingsProvider())
        registerSettingsProvider(MomentsFakeCommentSettingsProvider())
        registerSettingsProvider(MomentsFakeForwardSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val store = MomentsFakeInteractionStore(context.hostContext())
        val codec = MomentsFakeInteractionCodec(context, ::logFeatureError)
        val runtime = MomentsFakeInteractionRuntime(context, store, codec, ::logFeatureError)
        val dialogs = MomentsFakeInteractionDialogs(
            context.hostContext(),
            store,
            runtime,
            ::logFeatureError
        )
        val resolver = SnsForwardContentResolver(context, ::logFeatureError)
        val forwardRuntime = MomentsFakeForwardRuntime(context, ::logFeatureError)
        this.codec = codec
        this.runtime = runtime
        this.resolver = resolver
        this.forwardRuntime = forwardRuntime
        MomentsFakeInteractionRuntimeRegistry.attach(runtime)
        MomentsFakeForwardRuntimeRegistry.attach(forwardRuntime)
        postSubscription = WeChatApis.snsApi()?.observePosts(runtime::onPostStored)

        if (!runtime.installRecordHooks()) {
            logFeatureError("朋友圈伪互动记录Hook未安装", null)
        }
        val prefs = HchatStorage.preferences(
            context.hostContext(),
            MomentsFakeInteractionSettings.PREFS_NAME
        )
        SnsContextMenuDispatcher.register(
            SnsContextMenuDispatcher.Entry(
                owner = LIKE_OWNER,
                itemId = MENU_FAKE_LIKE_ID,
                title = "伪集赞[H]",
                titleProvider = {
                    menuTitle(
                        prefs.getString(MomentsFakeInteractionSettings.KEY_FAKE_LIKE_MENU_TEXT, ""),
                        MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_MENU_TEXT
                    )
                },
                order = 20,
                iconName = "icons_filled_like",
                isEnabled = {
                    runtime.isBaseReady() && !prefs.getBoolean(
                        MomentsFakeInteractionSettings.KEY_FAKE_LIKE_HIDE_MENU,
                        MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_HIDE_MENU
                    ) && prefs.getBoolean(
                        MomentsFakeInteractionSettings.KEY_FAKE_LIKE_ENABLE,
                        MomentsFakeInteractionSettings.DEFAULT_FAKE_LIKE_ENABLE
                    )
                },
                isApplicable = ::isNormalMomentsPost,
                onClick = dialogs::showFakeLikes
            )
        )
        SnsContextMenuDispatcher.register(
            SnsContextMenuDispatcher.Entry(
                owner = FORWARD_OWNER,
                itemId = MENU_FAKE_FORWARD_ID,
                title = MomentsFakeInteractionSettings.DEFAULT_FAKE_FORWARD_MENU_TEXT,
                titleProvider = {
                    menuTitle(
                        prefs.getString(MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_MENU_TEXT, ""),
                        MomentsFakeInteractionSettings.DEFAULT_FAKE_FORWARD_MENU_TEXT
                    )
                },
                order = 40,
                iconName = "icons_filled_share",
                isEnabled = {
                    forwardRuntime.isReady() && prefs.getBoolean(
                        MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_ENABLE,
                        MomentsFakeInteractionSettings.DEFAULT_FAKE_FORWARD_ENABLE
                    ) && !prefs.getBoolean(
                        MomentsFakeInteractionSettings.KEY_FAKE_FORWARD_HIDE_MENU,
                        MomentsFakeInteractionSettings.DEFAULT_FAKE_FORWARD_HIDE_MENU
                    )
                },
                isApplicable = ::isNormalMomentsPost,
                onClick = { activity, target -> dialogs.showFakeForward(activity, target, forwardRuntime) }
            )
        )
        SnsContextMenuDispatcher.register(
            SnsContextMenuDispatcher.Entry(
                owner = COMMENT_OWNER,
                itemId = MENU_FAKE_COMMENT_ID,
                title = "伪评论[H]",
                titleProvider = {
                    menuTitle(
                        prefs.getString(MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_MENU_TEXT, ""),
                        MomentsFakeInteractionSettings.DEFAULT_FAKE_COMMENT_MENU_TEXT
                    )
                },
                order = 30,
                iconName = "icons_filled_comment",
                isEnabled = {
                    runtime.isBaseReady() && runtime.isCommentGuardReady() && !prefs.getBoolean(
                        MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_HIDE_MENU,
                        MomentsFakeInteractionSettings.DEFAULT_FAKE_COMMENT_HIDE_MENU
                    ) && prefs.getBoolean(
                        MomentsFakeInteractionSettings.KEY_FAKE_COMMENT_ENABLE,
                        MomentsFakeInteractionSettings.DEFAULT_FAKE_COMMENT_ENABLE
                    )
                },
                isApplicable = ::isNormalMomentsPost,
                onClick = dialogs::showFakeComments
            )
        )
        scheduleInstall(context)
        subscribe(Events.DexReady::class.java) { scheduleInstall(context) }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        SnsContextMenuDispatcher.unregister(LIKE_OWNER)
        SnsContextMenuDispatcher.unregister(COMMENT_OWNER)
        SnsContextMenuDispatcher.unregister(FORWARD_OWNER)
        runtime?.let(MomentsFakeInteractionRuntimeRegistry::detach)
        forwardRuntime?.let(MomentsFakeForwardRuntimeRegistry::detach)
        postSubscription?.unsubscribe()
        postSubscription = null
        runtime?.destroy()
        runtime = null
        codec = null
        resolver = null
        forwardRuntime = null
    }

    private fun scheduleInstall(context: FeatureContext) {
        DexInstallScheduler.schedule("${ID}_sns_menu", name()) {
            val currentResolver = resolver ?: return@schedule false
            val storageReady = WeChatApis.snsApi()?.warmupCachedPostReadWrite() == true
            forwardRuntime?.warmup()
            val nodeReady = codec?.warmup() == true
            val guardReady = runtime?.installInteractionGuard() == true
            val observerReady = WeChatApis.snsApi()?.installPostObserver() == true
            val menuReady = SnsContextMenuDispatcher.install(context, currentResolver, ::logFeatureError)
            val baseReady = menuReady && observerReady && storageReady && nodeReady
            runtime?.setBaseReady(baseReady)
            if (baseReady) runtime?.processPendingRestore()
            val ready = baseReady && guardReady
            ready
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    private fun isNormalMomentsPost(target: h.Hchat.hooks.api.sns.SnsContextMenuTarget): Boolean {
        if (target.snsId == null) return false
        val nativeInfo = target.nativeInfo ?: return false
        return KavaReflector.invokeMethod(nativeInfo, "isAd") != true
    }

    private fun menuTitle(value: String?, fallback: String): String {
        return value.orEmpty().trim().ifEmpty { fallback }
    }

    companion object {
        const val ID = "moments_fake_interaction"
        private const val LIKE_OWNER = "moments_fake_like"
        private const val COMMENT_OWNER = "moments_fake_comment"
        private const val FORWARD_OWNER = "moments_fake_forward"
        private const val MENU_FAKE_LIKE_ID = 0x4843464c
        private const val MENU_FAKE_COMMENT_ID = 0x48434643
        private const val MENU_FAKE_FORWARD_ID = 0x48434646
    }
}
