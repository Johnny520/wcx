package com.Johnny.wcx.features.api.ui

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.view.ContextMenu
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.DexMethodDelegate
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.BString
import java.lang.reflect.Modifier
import java.math.BigInteger

@Feature(name = "朋友圈菜单增强扩展", categories = ["API"], description = "为朋友圈消息长按菜单提供添加菜单项功能")
object WeMomentsContextMenuApi : ApiFeature(), IResolveDex {

    private const val TAG = "WeMomentsContextMenuApi"

    /** 朋友圈单条动态的存储对象；不混淆，可直接按类名找。 */
    private const val SNS_INFO_CLASS = "com.tencent.mm.plugin.sns.storage.SnsInfo"

    fun interface IMenuItemsProvider {
        fun getMenuItems(): List<MenuItem>
    }

    private val menuItems = MenuRegistry<MenuItem>()

    fun addProvider(provider: IMenuItemsProvider) {
        menuItems.register(provider, provider.getMenuItems())
    }

    fun removeProvider(provider: IMenuItemsProvider) {
        menuItems.unregister(provider)
    }

    data class MenuItem(
        val id: Int,
        val text: String, val drawable: Drawable,
        val shouldShow: (context: MomentsContext, itemId: Int) -> Boolean,
        val onClick: (context: MomentsContext) -> Unit
    )

    data class MomentsContext(
        val activity: Activity,
        val snsInfo: Any?,
        val timelineObject: Any?,
        val source: Any? = null
    )

    private val methodOnCreateMenu by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.ui.listener")
        matcher {
            usingStrings("onMMCreateContextMenu error")
        }
    }
    private val methodOnItemSelected by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.ui.listener")
        matcher {
            usingStrings(
                "delete comment fail!!! snsInfo is null",
                "send photo fail, mediaObj is null",
                "mediaObj is null, send failed!"
            )
        }
    }
    private val methodImproveOnItemSelectedRegister2 by dexMethod(allowFailure = true) {
        // 8.0.80 适配：这三个 click 实现类已被 R8 混淆进 zk4 包（原包名只留在字符串常量里），
        // searchPackages 会把它们全部过滤掉。现在靠「register lambda 类名串（全 APK 唯一）
        // + onMMMenuItemSelected 串 + (MenuItem,int)->void 签名」定位，不再限制包名。
        matcher {
            paramCount(2)
            paramTypes("android.view.MenuItem", "int")
            returnType(Void.TYPE)
            usingStrings(
                "onMMMenuItemSelected",
                "com.tencent.mm.plugin.sns.ui.improve.item.click.BaseImproveClick${'$'}register${'$'}2"
            )
        }
    }
    private val methodImproveOnItemSelectedRegister3 by dexMethod(allowFailure = true) {
        // 8.0.80 适配：这三个 click 实现类已被 R8 混淆进 zk4 包（原包名只留在字符串常量里），
        // searchPackages 会把它们全部过滤掉。现在靠「register lambda 类名串（全 APK 唯一）
        // + onMMMenuItemSelected 串 + (MenuItem,int)->void 签名」定位，不再限制包名。
        matcher {
            paramCount(2)
            paramTypes("android.view.MenuItem", "int")
            returnType(Void.TYPE)
            usingStrings(
                "onMMMenuItemSelected",
                "com.tencent.mm.plugin.sns.ui.improve.item.click.BaseImproveClick${'$'}register${'$'}3"
            )
        }
    }
    private val methodImproveMultiPhotoOnItemSelected by dexMethod(allowFailure = true) {
        // 8.0.80 适配：这三个 click 实现类已被 R8 混淆进 zk4 包（原包名只留在字符串常量里），
        // searchPackages 会把它们全部过滤掉。现在靠「register lambda 类名串（全 APK 唯一）
        // + onMMMenuItemSelected 串 + (MenuItem,int)->void 签名」定位，不再限制包名。
        matcher {
            paramCount(2)
            paramTypes("android.view.MenuItem", "int")
            returnType(Void.TYPE)
            usingStrings(
                "onMMMenuItemSelected",
                "com.tencent.mm.plugin.sns.ui.improve.item.click.ImproveMultiPhotoClick${'$'}register${'$'}1${'$'}1${'$'}1"
            )
        }
    }
    private val methodSnsInfoStorage by dexMethod {
        matcher {
            paramCount(1)
            paramTypes("java.lang.String")
            usingStrings(
                "getByLocalId",
                "com.tencent.mm.plugin.sns.storage.SnsInfoStorage"
            )
            returnType("com.tencent.mm.plugin.sns.storage.SnsInfo")
        }
    }
    private val methodGetSnsInfoStorage by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.model")
        matcher {
            modifiers = Modifier.STATIC
            returnType(methodSnsInfoStorage.method.declaringClass)
            paramCount(0)
            usingStrings(
                "com.tencent.mm.plugin.sns.model.SnsCore",
                "getSnsInfoStorage"
            )
        }
    }

    override fun onEnable() {
        methodOnCreateMenu.method.hookAfter {
            handleCreateMenu(this)
        }

        methodOnItemSelected.method.hookAfter {
            handleSelectMenu(this)
        }

        hookOptionalImproveSelect(methodImproveOnItemSelectedRegister2, "register2")
        hookOptionalImproveSelect(methodImproveOnItemSelectedRegister3, "register3")
        hookOptionalImproveSelect(methodImproveMultiPhotoOnItemSelected, "multiPhoto")
    }

    private fun hookOptionalImproveSelect(target: DexMethodDelegate, label: String) {
        runCatching {
            if (target.isPlaceholder) return
            target.method.hookAfter {
                handleSelectMenu(this)
            }
        }.onFailure {
            WeLogger.w(TAG, "Improve Moments menu item selected hook unavailable ($label): ${it.message}")
        }
    }

    private fun handleCreateMenu(param: XC_MethodHook.MethodHookParam) {
        val menu = param.args.getOrNull(0) as? ContextMenu ?: return
        val context = resolveContext(param.thisObject)
        // 拿不到菜单上下文（连 Activity 都找不到）时**一项都不加**。
        // 原来的写法是「context 为 null 就不判断 shouldShow，照常添加」，结果是：
        // 关闭开关后菜单项照样出现（开关看起来失灵），且点下去也拿不到 snsInfo。
        if (context == null) {
            if (menuItems.snapshotFiltered(::aiMenuOwnerAllowed).isNotEmpty()) {
                WeLogger.d(TAG, "菜单上下文解析失败，跳过注入（listener=${param.thisObject?.javaClass?.name}）")
            }
            return
        }

        // snsInfo 为 null 时再找出路：新版朋友圈信息流的监听器（Improve 系列）里既没有
        // TimeLineObject 也没有 localId 字符串，但被长按的 View 上通常挂着 SnsInfo。
        val resolved = if (context.snsInfo == null) {
            resolveSnsInfoFromArgs(param.args)?.let { context.copy(snsInfo = it) } ?: context
        } else {
            context
        }
        if (resolved.snsInfo == null) {
            // 一行诊断：args 类型 + 监听器字段，下一次遇到新的页面结构能直接看出该往哪找。
            WeLogger.w(
                TAG,
                "菜单参数里也没有 SnsInfo，listener=${param.thisObject?.javaClass?.name}, " +
                        "args=${describeArgs(param.args)}, fields=${readFieldTypes(param.thisObject ?: return)}"
            )
        }

        for (item in menuItems.snapshotFiltered(::aiMenuOwnerAllowed)) {
            try {
                if (!item.shouldShow(resolved, item.id)) continue
                menu.reflekt()
                    .firstMethod {
                        parameters(Int::class, CharSequence::class, Drawable::class)
                    }
                    .invoke(item.id, item.text, item.drawable)
            } catch (e: Throwable) {
                WeLogger.e(TAG, "shouldShow/add callback failed", e)
            }
        }
    }

    /**
     * 从菜单参数里找 SnsInfo：被长按的 View 往往把它挂在 `tag` 上。
     *
     * 沿 View 树往上找几层 —— 长按落点常常是文字气泡，SnsInfo 在整条 item 的根上。
     * tag 是别的包装对象时，再看它的一层字段。
     */
    private fun resolveSnsInfoFromArgs(args: Array<Any?>): Any? {
        for (arg in args) {
            var view = arg as? View ?: continue
            var depth = 0
            while (depth++ < 10) {
                val tag = runCatching { view.tag }.getOrNull()
                if (tag != null) {
                    if (tag.javaClass.name == SNS_INFO_CLASS) return tag
                    if (tag.javaClass.name.startsWith("com.tencent.mm.")) {
                        findObjectInFields(tag) { it.javaClass.name == SNS_INFO_CLASS }?.let { return it }
                    }
                }
                view = view.parent as? View ?: break
            }
        }
        return null
    }

    /** 诊断用：参数列表的运行时类型。 */
    private fun describeArgs(args: Array<Any?>): String =
        args.joinToString(", ") { arg ->
            arg?.javaClass?.simpleName ?: "null"
        }

    private fun handleSelectMenu(param: XC_MethodHook.MethodHookParam) {
        val menuItem = param.args.getOrNull(0) as? android.view.MenuItem ?: return
        val clickedId = menuItem.itemId
        if (menuItems.snapshotFiltered(::aiMenuOwnerAllowed).none { it.id == clickedId }) return

        val context = resolveContext(param.thisObject)
        if (context == null) {
            WeLogger.w(TAG, "failed to resolve Moments context, listener=${param.thisObject.javaClass.name}, item=$clickedId")
            return
        }

        for (item in menuItems.snapshotFiltered(::aiMenuOwnerAllowed)) {
            try {
                if (item.id == clickedId) {
                    item.onClick(context)
                    // 仅当原方法返回 void 时才设置 result = null
                    if (param.method is java.lang.reflect.Method) {
                        val returnType = (param.method as java.lang.reflect.Method).returnType
                        if (returnType == Void.TYPE) {
                            param.result = null
                        }
                    }
                    return
                }
            } catch (e: Throwable) {
                WeLogger.e(TAG, "onSelect callback failed", e)
            }
        }
    }

    private fun resolveContext(listener: Any): MomentsContext? {
        resolveImproveContext(listener)?.let { return it }

        val reflected = listener.reflekt()
        // Activity 不一定在直接字段上：有的监听器把它包在内部对象里，或者只存了一个 Context。
        // 依次试「直接字段 → 一层子对象 / Context 链 → ActivityThread 的 mActivities」，
        // 全都拿不到才算解析失败（那时才跳过注入，否则开关会看起来失灵）。
        val activity = reflected.firstFieldOrNull {
            type = Activity::class
        }?.get() as? Activity
            ?: findActivityInFields(listener)
            ?: topActivityOrNull()

        if (activity == null) {
            WeLogger.w(
                TAG,
                "菜单上下文里没有 Activity，listener=${listener.javaClass.name}, " +
                        "fields=${readFieldTypes(listener)}"
            )
            return null
        }

        val timeLineObject = reflected.firstFieldOrNull {
            type = "com.tencent.mm.protocal.protobuf.TimeLineObject"
        }?.get()

        val snsInfo = resolveSnsInfo(listener, timeLineObject)
        if (snsInfo == null) {
            WeLogger.w(
                TAG,
                "snsInfo is null, activity=${activity.javaClass.name}, listener=${listener.javaClass.name}, " +
                        "timeline=${describeTimeline(timeLineObject)}, stringFields=${readStringFields(listener)}"
            )
        }
        return MomentsContext(activity, snsInfo, timeLineObject, listener)
    }

    /**
     * 从 listener 的字段里找 Activity。
     *
     * 两步：字段本身就是 Activity / Context（沿 `baseContext` 链解包），
     * 或者字段是微信自己的对象（把 Activity 存在里面），再往下看一层。
     * 只看一层子对象 —— 深挖会触发大量类加载，长按菜单扛不住。
     */
    private fun findActivityInFields(target: Any): Activity? {
        var current: Class<*>? = target.javaClass
        var depth = 0
        while (current != null && current != Any::class.java && depth++ < 6) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                val type = field.type
                val interesting = Activity::class.java.isAssignableFrom(type) ||
                    Context::class.java.isAssignableFrom(type) ||
                    type.name.startsWith("com.tencent.mm.")
                if (!interesting) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull() ?: continue

                unwrapActivity(value)?.let { return it }
                if (value.javaClass.name.startsWith("com.tencent.mm.")) {
                    findActivityOneLevelDown(value)?.let { return it }
                }
            }
            current = current.superclass
        }
        return null
    }

    /** 在 [target] 的直接字段里找 Activity / Context（不再递归）。 */
    private fun findActivityOneLevelDown(target: Any): Activity? {
        var current: Class<*>? = target.javaClass
        var depth = 0
        while (current != null && current != Any::class.java && depth++ < 6) {
            for (field in current.declaredFields) {
                if (Modifier.isStatic(field.modifiers)) continue
                if (!Activity::class.java.isAssignableFrom(field.type) &&
                    !Context::class.java.isAssignableFrom(field.type)
                ) {
                    continue
                }
                val value = runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull() ?: continue
                unwrapActivity(value)?.let { return it }
            }
            current = current.superclass
        }
        return null
    }

    /** `ContextWrapper` 链一路解到 Activity；普通 Context 原样返回 null。 */
    private fun unwrapActivity(value: Any?): Activity? {
        if (value is Activity) return value
        var context = value as? Context ?: return null
        var guard = 0
        while (guard++ < 8) {
            if (context is Activity) return context
            context = (context as? android.content.ContextWrapper)?.baseContext ?: break
        }
        return context as? Activity
    }

    /** 最后一道兜底：`ActivityThread.mActivities` 里最近的一个 Activity。 */
    private fun topActivityOrNull(): Activity? = runCatching {
        val thread = Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread")
            .invoke(null) ?: return@runCatching null
        val activities = thread.javaClass.getDeclaredField("mActivities")
            .apply { isAccessible = true }
            .get(thread) as? Map<*, *> ?: return@runCatching null
        activities.values.asSequence()
            .mapNotNull { record ->
                runCatching {
                    record?.javaClass?.getDeclaredField("activity")
                        ?.apply { isAccessible = true }
                        ?.get(record) as? Activity
                }.getOrNull()
            }
            .lastOrNull()
    }.getOrNull()

    /** 解析失败时把字段类型列出来，下一次遇到新的 listener 能直接看出该往哪儿找。 */
    private fun readFieldTypes(target: Any): String = runCatching {
        val names = mutableListOf<String>()
        var current: Class<*>? = target.javaClass
        var depth = 0
        while (current != null && current != Any::class.java && depth++ < 3) {
            current.declaredFields
                .filterNot { Modifier.isStatic(it.modifiers) }
                .forEach { names += it.name + ":" + it.type.simpleName }
            current = current.superclass
        }
        names.take(24).joinToString(", ")
    }.getOrDefault("?")

    private fun resolveImproveContext(listener: Any): MomentsContext? {
        val click = findObjectInFields(listener) { obj -> hasImproveInfoField(obj) } ?: return null
        val activity = click.reflekt().firstFieldOrNull {
            type { Context::class.java.isAssignableFrom(it) }
        }?.get() as? Activity ?: return null

        val info = findObjectInFields(click) { obj ->
            hasNoArgMethodReturning(obj, "com.tencent.mm.plugin.sns.storage.SnsInfo") &&
                    hasNoArgMethodReturning(obj, "com.tencent.mm.protocal.protobuf.TimeLineObject")
        } ?: return null

        val snsInfo = invokeNoArgReturning(info, "com.tencent.mm.plugin.sns.storage.SnsInfo")
        val timeLineObject = invokeNoArgReturning(info, "com.tencent.mm.protocal.protobuf.TimeLineObject")
        if (snsInfo == null || timeLineObject == null) {
            WeLogger.w(
                TAG,
                "failed to resolve Improve Moments context values, listener=${listener.javaClass.name}, " +
                        "click=${click.javaClass.name}, info=${info.javaClass.name}, snsInfo=${snsInfo?.javaClass?.name}, " +
                        "timeline=${timeLineObject?.javaClass?.name}"
            )
        }
        return MomentsContext(activity, snsInfo, timeLineObject, listener)
    }

    private fun resolveSnsInfo(listener: Any, timeLineObject: Any?): Any? {
        for (localId in readStringFields(listener)) {
            getSnsInfoByLocalId(localId)?.let { return it }
        }

        readTimelineSnsId(timeLineObject)?.let { snsId ->
            WeMomentsApi.getSnsInfoBySnsId(snsId)?.let { return it }
        }

        for (snsId in readLongFields(listener)) {
            WeMomentsApi.getSnsInfoBySnsId(snsId)?.let { return it }
        }

        return null
    }

    private fun getSnsInfoByLocalId(localId: String): Any? {
        if (localId.isBlank()) return null
        return runCatching {
            val storage = methodGetSnsInfoStorage.method.invoke(null)
            methodSnsInfoStorage.method.invoke(storage, localId)
        }.getOrElse { error ->
            WeLogger.e(TAG, "failed to get Moments snsInfo by localId=$localId", error)
            null
        }
    }

    private fun readStringFields(target: Any): List<String> {
        return runCatching {
            target.reflekt().fields {
                type = BString
                modifiers { !it.contains(Modifiers.FINAL) && !it.contains(Modifiers.STATIC) }
            }.mapNotNull { it.get() as? String }
                .filter { it.isNotBlank() }
                .distinct()
        }.getOrElse {
            WeLogger.e(TAG, "failed to read Moments context string fields", it)
            emptyList()
        }
    }

    private fun readLongFields(target: Any): List<Long> {
        return runCatching {
            target.javaClass.declaredFields.mapNotNull { field ->
                val modifiers = field.modifiers
                if (Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers)) return@mapNotNull null
                if (field.type != Long::class.javaPrimitiveType && field.type != Long::class.javaObjectType) return@mapNotNull null
                field.isAccessible = true
                (field.get(target) as? Number)?.toLong()?.takeIf { it != 0L }
            }.distinct()
        }.getOrElse {
            WeLogger.e(TAG, "failed to read Moments context long fields", it)
            emptyList()
        }
    }

    private fun readTimelineSnsId(timeLineObject: Any?): Long? {
        if (timeLineObject == null) return null
        val id = runCatching {
            timeLineObject.reflekt().firstFieldOrNull { name = "Id" }?.get() as? String
        }.getOrNull() ?: return null
        return runCatching { BigInteger(id).toLong() }.getOrNull()
    }

    private fun findObjectInFields(target: Any, predicate: (Any) -> Boolean): Any? {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                val modifiers = field.modifiers
                if (Modifier.isStatic(modifiers)) continue
                val value = runCatching {
                    field.isAccessible = true
                    field.get(target)
                }.getOrNull() ?: continue
                if (predicate(value)) return value
            }
            current = current.superclass
        }
        return null
    }

    private fun hasImproveInfoField(target: Any): Boolean {
        return findObjectInFields(target) { obj ->
            hasNoArgMethodReturning(obj, "com.tencent.mm.plugin.sns.storage.SnsInfo") &&
                    hasNoArgMethodReturning(obj, "com.tencent.mm.protocal.protobuf.TimeLineObject")
        } != null
    }

    private fun hasNoArgMethodReturning(target: Any, returnTypeName: String): Boolean =
        target.javaClass.methods.any { method ->
            method.parameterTypes.isEmpty() && method.returnType.name == returnTypeName
        }

    private fun invokeNoArgReturning(target: Any, returnTypeName: String): Any? =
        target.javaClass.methods.firstOrNull { method ->
            method.parameterTypes.isEmpty() && method.returnType.name == returnTypeName
        }?.let { method ->
            runCatching {
                method.isAccessible = true
                method.invoke(target)
            }.getOrElse {
                WeLogger.e(TAG, "failed to invoke Moments context method returning $returnTypeName", it)
                null
            }
        }

    private fun describeTimeline(timeLineObject: Any?): String {
        if (timeLineObject == null) return "null"
        return runCatching {
            val id = timeLineObject.reflekt().firstFieldOrNull { name = "Id" }?.get() as? String
            val desc = timeLineObject.reflekt().firstFieldOrNull { name = "ContentDesc" }?.get() as? String
            "id=$id, descLen=${desc?.length ?: 0}"
        }.getOrDefault(timeLineObject.javaClass.name)
    }
}
