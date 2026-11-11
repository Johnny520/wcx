package h.Hchat.hooks.items.gameemoji

import android.app.Activity
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.event.Events
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

class GameEmojiFeature : BaseFeature() {
    private var runtime: GameEmojiRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "指定骰子猜拳"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(GameEmojiSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        runtime = GameEmojiRuntime(context, ::logRuntimeError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name(), stage = DexInstallScheduler.Stage.WARMUP) {
            runtime?.install() == true
        }
    }

    private fun logRuntimeError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    companion object {
        const val ID = "game_emoji_result"
    }
}

private class GameEmojiRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val replaying = ThreadLocal<Boolean>()
    private val dialogShowing = AtomicBoolean(false)
    private var emojiFields: Map<String, Field> = emptyMap()

    @Volatile private var installed = false

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        val method = context.dexFinder().emojiSendMethod ?: return false
        if (!isEmojiSendMethod(method)) return false
        val fields = resolveEmojiFields(method.parameterTypes[1]) ?: return false
        emojiFields = fields
        return runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (replaying.get() == true) return
                    val emojiInfo = param.args?.getOrNull(1) ?: return
                    val gameType = detectGameType(emojiInfo) ?: return
                    when {
                        GameEmojiSettings.pickBeforeSendEnabled(context.hostContext()) -> {
                            interceptForChoice(method, param, gameType)
                        }
                        GameEmojiSettings.fixedResultEnabled(context.hostContext()) -> {
                            val result = configuredResult(gameType)
                            if (!applyResult(emojiInfo, gameType, result)) {
                                logger("改写游戏表情结果失败", null)
                            }
                        }
                    }
                }
            })
            installed = true
            true
        }.getOrElse {
            emojiFields = emptyMap()
            logger("游戏表情发送 Hook 安装失败", it)
            false
        }
    }

    private fun interceptForChoice(
        method: Method,
        param: XC_MethodHook.MethodHookParam,
        gameType: GameType
    ) {
        val activity = (WeChatApis.currentActivity()?.currentActivity() as? Activity)
            ?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: return
        if (!dialogShowing.compareAndSet(false, true)) {
            param.result = null
            return
        }
        val pending = PendingSend(method, param.thisObject, param.args.copyOf(), gameType)
        param.result = null
        mainHandler.post {
            if (activity.isFinishing || activity.isDestroyed) {
                dialogShowing.set(false)
                replay(pending, null)
                return@post
            }
            val choices = resultsFor(gameType).map { it.label to "" }
            val handle = VoiceForwardMiuixDialog.showChoices(
                activity = activity,
                title = if (gameType == GameType.DICE) "选择骰子点数" else "选择猜拳结果",
                summary = "",
                choices = choices,
                onSelected = { index ->
                    resultsFor(gameType).getOrNull(index)?.let { replay(pending, it) }
                },
                onDismiss = { dialogShowing.set(false) }
            )
            if (!handle.isShowing()) {
                dialogShowing.set(false)
                replay(pending, null)
            }
        }
    }

    private fun replay(pending: PendingSend, selected: GameResult?) {
        val emojiInfo = pending.args.getOrNull(1) ?: return
        if (selected != null && !applyResult(emojiInfo, pending.gameType, selected.value)) {
            logger("应用所选游戏表情结果失败", null)
            return
        }
        replaying.set(true)
        try {
            KavaReflector.invokeOrThrow(pending.method, pending.receiver, *pending.args)
        } catch (e: Throwable) {
            logger("重新发送游戏表情失败", e)
        } finally {
            replaying.remove()
        }
    }

    private fun configuredResult(gameType: GameType): Int {
        return if (gameType == GameType.DICE) {
            GameEmojiSettings.diceResult(context.hostContext())
        } else {
            GameEmojiSettings.rpsResult(context.hostContext())
        }
    }

    private fun detectGameType(emojiInfo: Any): GameType? {
        val name = readString(emojiInfo, FIELD_NAME)
        val content = readString(emojiInfo, FIELD_CONTENT)
        val md5 = readString(emojiInfo, FIELD_MD5)
        return when {
            name.startsWith("dice", ignoreCase = true) ||
                content.contains("type=\"2\"") || DICE_MD5.contains(md5) -> GameType.DICE
            name.startsWith("jsb", ignoreCase = true) ||
                content.contains("type=\"1\"") || RPS_MD5.contains(md5) -> GameType.RPS
            else -> null
        }
    }

    private fun applyResult(emojiInfo: Any, gameType: GameType, value: Int): Boolean {
        val result = resultsFor(gameType).firstOrNull { it.value == value } ?: return false
        val contentValue = if (gameType == GameType.DICE) value + 3 else value
        val values = mapOf<String, Any?>(
            FIELD_MD5 to result.md5,
            FIELD_SIZE to result.size,
            FIELD_CONTENT to "<gameext type=\"${gameType.protocolType}\" content=\"$contentValue\" ></gameext>",
            FIELD_NAME to result.fileName,
            FIELD_SVR_ID to "",
            FIELD_CATALOG to 50,
            FIELD_RESERVED_3 to 0,
            FIELD_RESERVED_4 to 0,
            FIELD_GROUP_ID to "50",
            FIELD_SOURCE to 0,
            FIELD_DESIGNER_ID to null,
            FIELD_THUMB_URL to null
        )
        var success = true
        values.forEach { (name, fieldValue) ->
            success = KavaReflector.writeField(emojiFields[name], emojiInfo, fieldValue) && success
        }
        return success
    }

    private fun readString(emojiInfo: Any, name: String): String {
        return KavaReflector.readField(emojiFields[name], emojiInfo) as? String ?: ""
    }

    private fun resolveEmojiFields(emojiClass: Class<*>): Map<String, Field>? {
        val fields = LinkedHashMap<String, Field>()
        REQUIRED_FIELDS.forEach { name ->
            val field = KavaReflector.findFieldRecursive(emojiClass, name) ?: run {
                logger("EmojiInfo 缺少字段: $name", null)
                return null
            }
            fields[name] = field
        }
        return fields
    }

    private fun isEmojiSendMethod(method: Method): Boolean {
        val types = method.parameterTypes
        return method.returnType == java.lang.Void.TYPE &&
            types.size >= 4 &&
            types[0] == String::class.java &&
            types[1].name == "com.tencent.mm.storage.emotion.EmojiInfo"
    }

    private fun resultsFor(gameType: GameType): List<GameResult> {
        return if (gameType == GameType.DICE) DICE_RESULTS else RPS_RESULTS
    }

    private data class PendingSend(
        val method: Method,
        val receiver: Any?,
        val args: Array<Any?>,
        val gameType: GameType
    )

    private enum class GameType(val protocolType: Int) {
        RPS(1),
        DICE(2)
    }

    private data class GameResult(
        val value: Int,
        val label: String,
        val md5: String,
        val size: Int,
        val fileName: String
    )

    companion object {
        private const val FIELD_MD5 = "field_md5"
        private const val FIELD_SVR_ID = "field_svrid"
        private const val FIELD_CATALOG = "field_catalog"
        private const val FIELD_SIZE = "field_size"
        private const val FIELD_NAME = "field_name"
        private const val FIELD_CONTENT = "field_content"
        private const val FIELD_RESERVED_3 = "field_reserved3"
        private const val FIELD_RESERVED_4 = "field_reserved4"
        private const val FIELD_GROUP_ID = "field_groupId"
        private const val FIELD_SOURCE = "field_source"
        private const val FIELD_DESIGNER_ID = "field_designerID"
        private const val FIELD_THUMB_URL = "field_thumbUrl"

        private val REQUIRED_FIELDS = listOf(
            FIELD_MD5,
            FIELD_SVR_ID,
            FIELD_CATALOG,
            FIELD_SIZE,
            FIELD_NAME,
            FIELD_CONTENT,
            FIELD_RESERVED_3,
            FIELD_RESERVED_4,
            FIELD_GROUP_ID,
            FIELD_SOURCE,
            FIELD_DESIGNER_ID,
            FIELD_THUMB_URL
        )

        private val RPS_RESULTS = listOf(
            GameResult(1, "剪刀", "514914788fc461e7205bf0b6ba496c49", 2782, "jsb_j.png"),
            GameResult(2, "石头", "f790e342a02e0f99d34b316547f9aeab", 2278, "jsb_s.png"),
            GameResult(3, "布", "091577322c40c05aa3dd701da29d6423", 3612, "jsb_b.png")
        )
        private val DICE_RESULTS = listOf(
            GameResult(1, "1 点", "da1c289d4e363f3ce1ff36538903b92f", 2342, "dice_1.png"),
            GameResult(2, "2 点", "9e3f303561566dc9342a3ea41e6552a6", 2278, "dice_2.png"),
            GameResult(3, "3 点", "dbcc51db2765c1d0106290bae6326fc4", 2404, "dice_3.png"),
            GameResult(4, "4 点", "9a21c57defc4974ab5b7c842e3232671", 2422, "dice_4.png"),
            GameResult(5, "5 点", "3a8e16d650f7e66ba5516b2780512830", 2538, "dice_5.png"),
            GameResult(6, "6 点", "5ba8e9694b853df10b9f2a77b312cc09", 2536, "dice_6.png")
        )
        private val RPS_MD5 = RPS_RESULTS.mapTo(HashSet()) { it.md5 }
        private val DICE_MD5 = DICE_RESULTS.mapTo(HashSet()) { it.md5 }
    }
}
