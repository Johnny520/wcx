package com.Johnny.wcx.features.items.moments

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.tencent.mm.plugin.sns.ui.SnsUserUI
import com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI
import com.tencent.mm.view.recyclerview.WxRecyclerView
import com.Johnny.reflekt.reflekt
import com.Johnny.reflekt.utils.isSubclassOf
import com.Johnny.wcx.features.api.ui.WeMomentsApi
import com.Johnny.wcx.features.api.ui.WeMomentsApi.classImproveInteractionLayout
import com.Johnny.wcx.features.api.ui.WeMomentsApi.classImproveSnsInfo
import com.Johnny.wcx.features.api.ui.WeMomentsApi.fieldInteractionSnsInfo
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.ui.utils.findViewWhich
import com.Johnny.wcx.ui.utils.rootView
import com.Johnny.wcx.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap

/**
 * Shared base for [AutoLikeMoments] and [AutoRepostMoments].
 *
 * Owns the view-tree wiring (timeline hooks, attach/scroll listeners) and the
 * SnsInfo location logic so neither subclass has to duplicate it.
 */
abstract class AutoMomentsBase : ClickableFeature() {

    @Suppress("PropertyName")
    protected abstract val TAG: String

    protected val attachedRoots: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap())

    @Volatile
    private var timelineHooksInstalled = false

    // ==================== Timeline hooks ====================

    protected fun installTimelineHooks() {
        if (timelineHooksInstalled) return
        timelineHooksInstalled = true
        listOf(
            ImproveSnsTimelineUI::class.java,
            SnsUserUI::class.java
        ).forEach { clazz ->
            clazz.reflekt()
                .firstMethod { name = "onCreate" }
                .hookAfter { scheduleAttach(thisObject as Activity) }
            clazz.reflekt()
                .firstMethod { name = "onResume" }
                .hookAfter { scheduleAttach(thisObject as Activity) }
        }
    }

    private fun scheduleAttach(activity: Activity) {
        val root = activity.rootView
        intArrayOf(0, 200, 800, 2_000).forEach { delayMs ->
            root.postDelayed({
                runCatching { attachToTimelineList(root) }
                    .onFailure { WeLogger.w(TAG, "failed to attach Moments list observer", it) }
            }, delayMs.toLong())
        }
    }

    private fun attachToTimelineList(root: ViewGroup) {
        val list = root.findViewWhich<ViewGroup> { it is WxRecyclerView } ?: return
        synchronized(attachedRoots) {
            if (!attachedRoots.add(root)) return
        }
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            processVisibleItems(list)
        }
        list.viewTreeObserver.addOnGlobalLayoutListener {
            processVisibleItems(list)
        }
        processVisibleItems(list)
    }

    /** Called whenever visible list items may have changed. */
    protected abstract fun processVisibleItems(list: ViewGroup)

    // ==================== SnsInfo location ====================

    protected fun locateSnsInfo(itemView: View): Any? {
        extractImproveSnsInfo(itemView)?.let { return it }

        val interactionView = itemView.findViewWhich<View> {
            classImproveInteractionLayout.clazz.isInstance(it)
        } ?: return null

        return extractImproveSnsInfo(interactionView)
            ?: fieldInteractionSnsInfo.field.get(interactionView)
    }

    private fun extractImproveSnsInfo(receiver: Any): Any? {
        if (classImproveSnsInfo.clazz.isInstance(receiver)) return receiver

        receiver.reflekt()
            .firstMethodOrNull { parameters(); superclass(); returnType { it isSubclassOf classImproveSnsInfo.clazz } }
            ?.invoke()?.let { return it }

        receiver.reflekt().firstMethodOrNull {
            name = "getImproveListItem"
            parameters()
            superclass()
        }?.invoke()?.let { listItem ->
            listItem.reflekt()
                .firstMethodOrNull { parameters(); superclass(); returnType { it isSubclassOf classImproveSnsInfo.clazz } }
                ?.invoke()?.let { return it }
            listItem.reflekt()
                .firstFieldOrNull { superclass(); type { it isSubclassOf classImproveSnsInfo.clazz } }
                ?.get()?.let { return it }
        }

        return receiver.reflekt()
            .firstFieldOrNull { superclass(); type { it isSubclassOf classImproveSnsInfo.clazz } }
            ?.get()
    }

    // ==================== AntiMomentsDelete interception check ====================

    protected fun isIntercepted(snsInfo: Any): Boolean {
        val content = WeMomentsApi.getContentText(snsInfo) ?: return false
        return content.contains(AntiMomentsDelete.INTERCEPT_MARKER)
    }
}
