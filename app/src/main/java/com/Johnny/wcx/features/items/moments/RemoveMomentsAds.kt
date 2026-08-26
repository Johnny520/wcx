package com.Johnny.wcx.features.items.moments

import android.app.Activity
import android.content.ContentValues
import android.view.View
import android.view.ViewGroup
import com.tencent.mm.plugin.sns.storage.ADInfo
import com.tencent.mm.plugin.sns.ui.SnsUserUI
import com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI
import com.tencent.mm.view.recyclerview.WxRecyclerView
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.ui.WeMomentsApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.utils.findViewWhich
import com.Johnny.wcx.ui.utils.rootView
import com.Johnny.wcx.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap

@Feature(name = "拦截朋友圈广告", categories = ["朋友圈"], description = "拦截朋友圈广告")
object RemoveMomentsAds : SwitchFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "RemoveMomentsAds"
    private const val TBL_SNS_INFO = "SnsInfo"

    // 已挂载的根视图集合，避免重复挂载
    private val attachedRoots: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap())

    override fun onEnable() {
        WeLogger.i(TAG, "========== 拦截朋友圈广告: 已开启 ==========")

        // ── 第①层：数据源拦截 ──────────────────────────────────────────
        // Hook 1: ADInfo 构造函数 — 阻断广告数据对象创建
        try {
            ADInfo::class.reflekt()
                .firstConstructor { parameters(String::class) }
                .hookBefore {
                    try {
                        WeLogger.i(TAG, "数据源层拦截: 阻断 ADInfo 构造")
                        if (method is java.lang.reflect.Method) {
                            val returnType = (method as java.lang.reflect.Method).returnType
                            if (returnType == Void.TYPE) {
                                result = null
                            }
                        }
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "ADInfo 构造 Hook 异常", e)
                    }
                }
            WeLogger.d(TAG, "Hook ① ADInfo 构造 注册成功")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Hook ① ADInfo 构造 注册失败", e)
        }

        // Hook 2: SnsInfo 数据库插入监听 — 判断广告并标记（日志记录）
        try {
            WeDatabaseListenerApi.addListener(this)
            WeLogger.d(TAG, "Hook ② SnsInfo 数据库监听 注册成功")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Hook ② SnsInfo 数据库监听 注册失败", e)
        }

        // ── 第②层：View 渲染层兜底过滤 ────────────────────────────────────
        // Hook 3: 朋友圈时间线页面生命周期 — 挂载 RecyclerView 监听
        try {
            listOf(ImproveSnsTimelineUI::class.java, SnsUserUI::class.java).forEach { clazz ->
                clazz.reflekt().firstMethod { name = "onCreate" }
                    .hookAfter { scheduleViewLayerAttach(thisObject as Activity) }
                clazz.reflekt().firstMethod { name = "onResume" }
                    .hookAfter { scheduleViewLayerAttach(thisObject as Activity) }
            }
            WeLogger.d(TAG, "Hook ③ 时间线页面生命周期 注册成功")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Hook ③ 时间线页面生命周期 注册失败", e)
        }

        WeLogger.i(TAG, "========== 拦截朋友圈广告: 全部 Hook 注册完成 ==========")
    }

    override fun onDisable() {
        WeLogger.i(TAG, "拦截朋友圈广告: 已关闭，恢复原生广告展示")
        try {
            WeDatabaseListenerApi.removeListener(this)
        } catch (_: Throwable) {}
        attachedRoots.clear()
    }

    // =========================================================================
    // 数据源层：SnsInfo 数据库插入监听
    // =========================================================================
    override fun onInsert(table: String, values: ContentValues) {
        try {
            if (table != TBL_SNS_INFO) return
            val snsId = values.getAsLong("snsId") ?: return
            if (snsId == 0L) return

            // 通过 SnsId 获取 SnsInfo 对象，检查是否为广告
            val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: run {
                WeLogger.d(TAG, "数据源层: snsId=$snsId 无法获取 SnsInfo，可能是新插入尚未可读")
                return
            }

            if (WeMomentsApi.isAd(snsInfo)) {
                WeLogger.i(TAG, "数据源层: 检测到广告 snsId=$snsId，已通过 ADInfo 构造拦截")
            } else {
                WeLogger.d(TAG, "数据源层: snsId=$snsId 非广告，正常放行")
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "数据源层 SnsInfo 监听异常", e)
        }
    }

    // =========================================================================
    // View 渲染层兜底过滤
    // =========================================================================
    private fun scheduleViewLayerAttach(activity: Activity) {
        val root = activity.rootView
        intArrayOf(0, 200, 800, 2_000).forEach { delayMs ->
            root.postDelayed({
                runCatching { attachToTimelineList(root) }
                    .onFailure { WeLogger.w(TAG, "View层挂载失败, delay=${delayMs}ms", it) }
            }, delayMs.toLong())
        }
    }

    private fun attachToTimelineList(root: ViewGroup) {
        val list = root.findViewWhich<ViewGroup> { it is WxRecyclerView } ?: return
        synchronized(attachedRoots) {
            if (!attachedRoots.add(root)) return
        }
        WeLogger.d(TAG, "View层: 挂载时间线 RecyclerView 监听")

        // 监听布局变化，过滤可见广告项
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            filterVisibleAds(list)
        }
        list.viewTreeObserver.addOnGlobalLayoutListener {
            filterVisibleAds(list)
        }
        // 首次立即过滤
        list.post { filterVisibleAds(list) }
    }

    /**
     * 遍历当前可见的列表项，识别广告 View 并隐藏。
     * 作为数据源层拦截的兜底防护。
     */
    private fun filterVisibleAds(list: ViewGroup) {
        try {
            var blockedCount = 0
            var checkedCount = 0
            for (i in 0 until list.childCount) {
                val itemView = list.getChildAt(i) ?: continue
                checkedCount++
                try {
                    val snsInfo = locateSnsInfo(itemView)
                    if (snsInfo != null && WeMomentsApi.isAd(snsInfo)) {
                        // 隐藏广告 View
                        itemView.visibility = View.GONE
                        val lp = itemView.layoutParams
                        if (lp != null) {
                            lp.height = 0
                            itemView.layoutParams = lp
                        }
                        blockedCount++
                        WeLogger.i(TAG, "View层兜底: 隐藏广告 View, index=$i, snsId=${getSnsId(snsInfo)}")
                    }
                } catch (e: Throwable) {
                    WeLogger.d(TAG, "View层: 检查 item[$i] 异常，跳过: ${e.message}")
                }
            }
            if (checkedCount > 0) {
                WeLogger.d(TAG, "View层: 检查 $checkedCount 个可见项, 拦截 $blockedCount 个广告")
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "View层过滤异常", e)
        }
    }

    /**
     * 从 itemView 中定位 SnsInfo 对象。
     * 复用 AutoMomentsBase 的定位逻辑。
     */
    private fun locateSnsInfo(itemView: View): Any? {
        try {
            // 方式1: 直接从 itemView 提取 ImproveSnsInfo
            val improveSnsInfo = extractImproveSnsInfo(itemView)
            if (improveSnsInfo != null) return improveSnsInfo

            // 方式2: 通过 InteractionLayout 定位
            val interactionView = itemView.findViewWhich<View> {
                WeMomentsApi.classImproveInteractionLayout.clazz.isInstance(it)
            } ?: return null

            return extractImproveSnsInfo(interactionView)
                ?: WeMomentsApi.fieldInteractionSnsInfo.field.get(interactionView)
        } catch (e: Throwable) {
            return null
        }
    }

    private fun extractImproveSnsInfo(receiver: Any): Any? {
        try {
            if (WeMomentsApi.classImproveSnsInfo.clazz.isInstance(receiver)) return receiver

            receiver.reflekt()
                .firstMethodOrNull {
                    parameters()
                    superclass()
                    returnType { WeMomentsApi.classImproveSnsInfo.clazz.isAssignableFrom(it) }
                }?.invoke()?.let { return it }

            receiver.reflekt().firstMethodOrNull {
                name = "getImproveListItem"
                parameters()
                superclass()
            }?.invoke()?.let { listItem ->
                listItem.reflekt()
                    .firstMethodOrNull {
                        parameters()
                        superclass()
                        returnType { WeMomentsApi.classImproveSnsInfo.clazz.isAssignableFrom(it) }
                    }?.invoke()?.let { return it }
                listItem.reflekt()
                    .firstFieldOrNull {
                        superclass()
                        type { WeMomentsApi.classImproveSnsInfo.clazz.isAssignableFrom(it) }
                    }?.get()?.let { return it }
            }

            return receiver.reflekt()
                .firstFieldOrNull {
                    superclass()
                    type { WeMomentsApi.classImproveSnsInfo.clazz.isAssignableFrom(it) }
                }?.get()
        } catch (e: Throwable) {
            return null
        }
    }

    private fun getSnsId(snsInfo: Any): Long {
        return try {
            (snsInfo.reflekt().firstFieldOrNull { name = "field_snsId"; superclass() }?.get() as? Number)?.toLong() ?: 0L
        } catch (_: Throwable) { 0L }
    }
}