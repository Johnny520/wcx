package h.Hchat.hooks.api.sns

import android.app.Activity
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog
import h.Hchat.utils.KavaReflector
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Collection
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale
import java.util.Map
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

internal data class SnsContextMenuTarget(
    val snsId: String?,
    val snapshot: SnsForwardSnapshot,
    val nativeInfo: Any?,
    val anchorView: WeakReference<View>?
)

internal object SnsContextMenuDispatcher {
    data class Entry(
        val owner: String,
        val itemId: Int,
        val title: String,
        val order: Int,
        val titleProvider: (() -> String)? = null,
        val iconName: String = "",
        val isEnabled: () -> Boolean,
        val isApplicable: (SnsContextMenuTarget) -> Boolean = { true },
        val onClick: (Activity, SnsContextMenuTarget) -> Unit
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val hookedMethods = Collections.newSetFromMap(ConcurrentHashMap<Method, Boolean>())
    private val bindings = Collections.synchronizedMap(WeakHashMap<MenuItem, SnsContextMenuTarget>())
    private val pendingByItemId = ConcurrentHashMap<Int, WeakReference<SnsContextMenuTarget>>()
    private val foldResolverExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "Hchat-SnsFoldMenu").apply { isDaemon = true }
    }
    @Volatile private var resolver: SnsForwardContentResolver? = null
    @Volatile private var logger: ((String, Throwable?) -> Unit)? = null
    @Volatile private var lastHandledItem: WeakReference<MenuItem>? = null
    @Volatile private var lastHandledAt = 0L

    fun register(entry: Entry) {
        entries[entry.owner] = entry
    }

    fun unregister(owner: String) {
        entries.remove(owner)
    }

    @Synchronized
    fun install(
        context: FeatureContext,
        resolver: SnsForwardContentResolver,
        logger: (String, Throwable?) -> Unit
    ): Boolean {
        this.resolver = resolver
        this.logger = logger
        val createHooked = SnsContextMenuLocator.menuCreateMethods(context, logger).count { method ->
            hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    addMenus(param)
                }
            })
        }
        val clickHooked = SnsContextMenuLocator.menuClickMethods(context, logger).count { method ->
            hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    handleClick(param)
                }
            })
        }
        val foldHooked = SnsContextMenuLocator.foldBindCompletionMethods(context, logger).count { method ->
            hook(method, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    bindFoldLongClick(param)
                }
            })
        }
        if (createHooked <= 0) logger("朋友圈共享菜单创建Hook未安装", null)
        if (clickHooked <= 0) logger("朋友圈共享菜单点击Hook未安装", null)
        if (foldHooked <= 0) logger("朋友圈折叠卡片长按Hook未安装", null)
        return createHooked > 0 && clickHooked > 0
    }

    private fun hook(method: Method, callback: XC_MethodHook): Boolean {
        if (Modifier.isAbstract(method.modifiers) || method.declaringClass.isInterface) return false
        if (!hookedMethods.add(method)) return true
        return runCatching {
            HookRegistry.get().hook(KavaReflector.accessible(method) ?: method, callback)
            true
        }.getOrElse {
            hookedMethods.remove(method)
            logger?.invoke("朋友圈共享菜单Hook安装失败: ${method.toGenericString()}", it)
            false
        }
    }

    private fun addMenus(param: XC_MethodHook.MethodHookParam) {
        val target = resolveTarget(param) ?: return
        val active = activeEntries(target)
        if (active.isEmpty()) return
        val menu = param.args?.getOrNull(0) ?: return
        val view = param.args?.getOrNull(1) as? View
        bindings.clear()
        pendingByItemId.clear()
        active.forEach { entry ->
            val item = addMenuItem(menu, view, entry) ?: return@forEach
            bindings[item] = target
            pendingByItemId[entry.itemId] = WeakReference(target)
        }
    }

    private fun bindFoldLongClick(param: XC_MethodHook.MethodHookParam) {
        val owner = param.thisObject ?: return
        val fields = KavaReflector.declaredFields(owner.javaClass)
            .filterNot { Modifier.isStatic(it.modifiers) }
        val textView = fields.asSequence()
            .filter { TextView::class.java.isAssignableFrom(it.type) }
            .mapNotNull { KavaReflector.readField(it, owner) as? TextView }
            .firstOrNull()
            ?: return
        textView.setOnLongClickListener(null)
        val foldItem = fields.asSequence()
            .mapNotNull { KavaReflector.readField(it, owner) }
            .firstOrNull { value ->
                KavaReflector.invokeMethod(value, "name") == FOLD_ITEM_NAME
            }
            ?: return
        val childIds = extractFoldSnsIds(foldItem)
        if (childIds.isEmpty()) return
        textView.setOnLongClickListener { anchor ->
            if (activeEntries().isEmpty()) return@setOnLongClickListener false
            val activity = currentActivity() ?: return@setOnLongClickListener false
            resolveFoldTargets(activity, anchor, childIds.toList())
            true
        }
    }

    private fun extractFoldSnsIds(foldItem: Any): List<String> {
        val data = KavaReflector.invokeMethod(foldItem, "getData") ?: return emptyList()
        val candidates = shortNoArgObjectValues(data).flatMap { model ->
            shortNoArgObjectValues(model).mapNotNull(::numericCollection)
        }
        return candidates.maxByOrNull { it.size }.orEmpty()
    }

    private fun shortNoArgObjectValues(receiver: Any): List<Any> {
        return KavaReflector.declaredMethods(receiver.javaClass).asSequence()
            .filter { method ->
                !Modifier.isStatic(method.modifiers) &&
                    method.name.length <= 3 &&
                    method.parameterTypes.isEmpty() &&
                    !method.returnType.isPrimitive &&
                    method.returnType != Void.TYPE &&
                    method.returnType != String::class.java &&
                    !method.returnType.isArray &&
                    !Collection::class.java.isAssignableFrom(method.returnType) &&
                    !Map::class.java.isAssignableFrom(method.returnType)
            }
            .distinctBy { "${it.name}:${it.returnType.name}" }
            .mapNotNull { KavaReflector.invoke(it, receiver) }
            .toList()
    }

    private fun numericCollection(value: Any): List<String>? {
        val collections = KavaReflector.declaredFields(value.javaClass).asSequence()
            .filter { field ->
                !Modifier.isStatic(field.modifiers) &&
                    Collection::class.java.isAssignableFrom(field.type)
            }
            .mapNotNull { KavaReflector.readField(it, value) as? Collection<*> }
        return collections.mapNotNull { collection ->
            if (collection.isEmpty() || collection.any { it !is Long }) return@mapNotNull null
            val ids = LinkedHashSet<String>()
            collection.forEach { raw ->
                (raw as? Long)?.takeIf { it != 0L }?.let {
                    ids += java.lang.Long.toUnsignedString(it)
                }
            }
            ids.toList().takeIf { it.isNotEmpty() }
        }.maxByOrNull { it.size }
    }

    private fun resolveFoldTargets(activity: Activity, anchor: View, childIds: List<String>) {
        val activityRef = WeakReference(activity)
        val anchorRef = WeakReference(anchor)
        foldResolverExecutor.execute {
            val currentResolver = resolver ?: return@execute
            val snsApi = WeChatApis.snsApi() ?: return@execute
            val choices = childIds.mapNotNull { snsId ->
                val nativeInfo = snsApi.cachedNativeSnsInfo(snsId) ?: return@mapNotNull null
                val snapshot = currentResolver.snapshotFromSnsInfo(nativeInfo) ?: return@mapNotNull null
                val target = SnsContextMenuTarget(
                    snsId = snsId,
                    snapshot = snapshot,
                    nativeInfo = nativeInfo,
                    anchorView = anchorRef
                )
                val actions = activeEntries(target)
                FoldChoice(target, actions).takeIf { actions.isNotEmpty() }
            }
            anchor.post {
                val currentActivity = activityRef.get()
                    ?.takeUnless { it.isFinishing || it.isDestroyed }
                    ?: return@post
                if (choices.isEmpty()) {
                    Toast.makeText(currentActivity, "没有可操作的折叠内容", Toast.LENGTH_SHORT).show()
                    return@post
                }
                showFoldPostChoices(currentActivity, choices)
            }
        }
    }

    private fun showFoldPostChoices(activity: Activity, choices: List<FoldChoice>) {
        VoiceForwardMiuixDialog.showListChoices(
            activity = activity,
            title = "选择朋友圈",
            summary = "${choices.size} 条折叠内容",
            choices = choices.map { choice ->
                foldPreview(choice.target.snapshot) to foldSummary(choice.target)
            },
            onSelected = { index ->
                choices.getOrNull(index)?.let { showFoldActionChoices(activity, it) }
            },
            onDismiss = {}
        )
    }

    private fun showFoldActionChoices(activity: Activity, choice: FoldChoice) {
        val actions = activeEntries(choice.target)
        if (actions.isEmpty()) return
        VoiceForwardMiuixDialog.showListChoices(
            activity = activity,
            title = "朋友圈操作",
            summary = foldPreview(choice.target.snapshot),
            choices = actions.map { entryTitle(it) to "" },
            onSelected = { index ->
                val selected = actions.getOrNull(index) ?: return@showListChoices
                val current = activeEntries(choice.target).firstOrNull { it.owner == selected.owner }
                    ?: return@showListChoices
                current.onClick(activity, choice.target)
            },
            onDismiss = {}
        )
    }

    private fun activeEntries(target: SnsContextMenuTarget? = null): List<Entry> {
        return entries.values.asSequence()
            .filter { runCatching(it.isEnabled).getOrDefault(false) }
            .filter { target == null || runCatching { it.isApplicable(target) }.getOrDefault(false) }
            .sortedWith(compareBy<Entry> { it.order }.thenBy { it.itemId })
            .toList()
    }

    private fun entryTitle(entry: Entry): String {
        return runCatching { entry.titleProvider?.invoke() }
            .getOrNull()
            ?.trim()
            .orEmpty()
            .ifEmpty { entry.title }
    }

    private fun foldPreview(snapshot: SnsForwardSnapshot): String {
        val text = snapshot.text.replace(WHITESPACE_REGEX, " ").trim()
        return text.take(FOLD_PREVIEW_LENGTH).ifEmpty {
            SnsContentTypes.classify(snapshot.type).label
        }
    }

    private fun foldSummary(target: SnsContextMenuTarget): String {
        val parts = ArrayList<String>(2)
        createTimeText(target.nativeInfo)?.let(parts::add)
        parts += SnsContentTypes.classify(target.snapshot.type).label
        return parts.joinToString(" · ")
    }

    private fun createTimeText(nativeInfo: Any?): String? {
        val raw = (KavaReflector.readField(nativeInfo, "field_createTime") as? Number)?.toLong()
            ?: (KavaReflector.readField(nativeInfo, "createTime") as? Number)?.toLong()
            ?: return null
        if (raw <= 0L) return null
        val millis = if (raw < 10_000_000_000L) raw * 1_000L else raw
        return runCatching {
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
        }.getOrNull()
    }

    @Synchronized
    private fun handleClick(param: XC_MethodHook.MethodHookParam) {
        val item = param.args?.firstNotNullOfOrNull { it as? MenuItem } ?: return
        val entry = entries.values.firstOrNull { it.itemId == item.itemId } ?: return
        if (!runCatching(entry.isEnabled).getOrDefault(false)) return
        val now = SystemClock.elapsedRealtime()
        if (lastHandledItem?.get() === item && now - lastHandledAt < CLICK_DEDUP_MS) return
        val target = bindings.remove(item)
            ?: pendingByItemId.remove(item.itemId)?.get()
            ?: resolveTarget(param)
            ?: return
        bindings.clear()
        pendingByItemId.clear()
        val activity = currentActivity() ?: return
        lastHandledItem = WeakReference(item)
        lastHandledAt = now
        activity.window?.decorView?.post {
            if (!activity.isFinishing && !activity.isDestroyed) {
                entry.onClick(activity, target)
            }
        }
    }

    private fun resolveTarget(param: XC_MethodHook.MethodHookParam): SnsContextMenuTarget? {
        val currentResolver = resolver ?: return null
        val snapshot = currentResolver.resolve(param.thisObject, param.args) ?: return null
        var nativeInfo = currentResolver.resolveNativeSnsInfo(param.thisObject, param.args)
        val nativeId = nativeInfo?.let(::snsIdFromNative)
        val snapshotId = normalizeSnsId(snapshot.id)
        if (nativeInfo != null && snapshotId != null && nativeId != snapshotId) {
            nativeInfo = null
        }
        val stableId = snapshotId ?: nativeId
        if (nativeInfo == null && stableId != null) {
            nativeInfo = WeChatApis.snsApi()?.cachedNativeSnsInfo(stableId)
        }
        return SnsContextMenuTarget(
            snsId = stableId,
            snapshot = snapshot,
            nativeInfo = nativeInfo,
            anchorView = param.args?.firstNotNullOfOrNull { it as? View }?.let { WeakReference(it) }
        )
    }

    private fun snsIdFromNative(nativeInfo: Any): String? {
        val raw = KavaReflector.readField(nativeInfo, "field_snsId")
            ?: KavaReflector.readField(nativeInfo, "snsId")
        return normalizeSnsId(raw?.toString())
    }

    private fun normalizeSnsId(raw: String?): String? {
        val value = raw.orEmpty().trim().trim('\'', '"').takeIf { it.isNotEmpty() } ?: return null
        value.toLongOrNull()?.let { return java.lang.Long.toUnsignedString(it) }
        return runCatching { java.lang.Long.parseUnsignedLong(value) }
            .getOrNull()
            ?.let(java.lang.Long::toUnsignedString)
    }

    private fun addMenuItem(menu: Any, view: View?, entry: Entry): MenuItem? {
        val title = entryTitle(entry)
        findMenuItem(menu, entry.itemId)?.let { existing ->
            runCatching { existing.title = title }
            return existing
        }
        val iconRes = iconResourceId(view, entry.iconName)
        val icon = loadIcon(view, iconRes, entry.iconName)
        if (icon != null) {
            val drawableMethod = KavaReflector.declaredMethods(menu.javaClass).firstOrNull { method ->
                val types = method.parameterTypes
                types.size == 3 &&
                    types[0] == Integer.TYPE &&
                    CharSequence::class.java.isAssignableFrom(types[1]) &&
                    Drawable::class.java.isAssignableFrom(types[2])
            }
            if (KavaReflector.invokeSuccessfully(drawableMethod, menu, entry.itemId, title, icon)) {
                return findMenuItem(menu, entry.itemId)
            }
        }
        if (iconRes != 0) {
            val resourceMethod = KavaReflector.declaredMethods(menu.javaClass).firstOrNull { method ->
                val types = method.parameterTypes
                method.name == "c" &&
                    types.size == 5 &&
                    types[0] == Integer.TYPE &&
                    types[1] == Integer.TYPE &&
                    types[2] == Integer.TYPE &&
                    CharSequence::class.java.isAssignableFrom(types[3]) &&
                    types[4] == Integer.TYPE
            }
            if (KavaReflector.invokeSuccessfully(resourceMethod, menu, 0, entry.itemId, 0, title, iconRes)) {
                return findMenuItem(menu, entry.itemId)
            }
        }
        val added = KavaReflector.invokeMethod(menu, "add", 0, entry.itemId, entry.order, title)
            ?: KavaReflector.invokeMethod(menu, "add", 0, entry.itemId, entry.order, title as CharSequence)
        if (added is MenuItem) {
            if (icon != null) runCatching { added.setIcon(icon) }
            else if (iconRes != 0) runCatching { added.setIcon(iconRes) }
            return added
        }
        return findMenuItem(menu, entry.itemId)
    }

    private fun findMenuItem(menu: Any, itemId: Int): MenuItem? {
        return KavaReflector.invokeMethod(menu, "findItem", itemId) as? MenuItem
    }

    private fun iconResourceId(view: View?, iconName: String): Int {
        if (iconName.isBlank()) return 0
        val iconContext = view?.context ?: currentActivity() ?: return 0
        for (type in arrayOf("raw", "drawable")) {
            val id = iconContext.resources.getIdentifier(iconName, type, iconContext.packageName)
            if (id != 0) return id
        }
        return 0
    }

    @Suppress("DEPRECATION")
    private fun loadIcon(view: View?, resourceId: Int, iconName: String): Drawable? {
        if (resourceId == 0) return null
        val iconContext = view?.context ?: currentActivity() ?: return null
        return runCatching { iconContext.resources.getDrawable(resourceId, iconContext.theme) }.getOrNull()
            ?: runCatching {
                iconContext.resources.openRawResource(resourceId).use { input ->
                    Drawable.createFromStream(input, iconName)
                }
            }.getOrNull()
    }

    private fun currentActivity(): Activity? {
        return (WeChatApis.currentActivity()?.currentActivity() as? Activity)
            ?.takeUnless { it.isFinishing || it.isDestroyed }
    }

    private data class FoldChoice(
        val target: SnsContextMenuTarget,
        val actions: List<Entry>
    )

    private val WHITESPACE_REGEX = Regex("\\s+")
    private const val FOLD_ITEM_NAME = "FoldImproveTimelineItem"
    private const val FOLD_PREVIEW_LENGTH = 48
    private const val CLICK_DEDUP_MS = 1_500L
}
