package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ListView
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.chat.ConversationGrouping
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.invokeOriginal
import java.util.concurrent.ConcurrentHashMap

@Feature(
    name = "隐藏主页下滑「最近」页",
    categories = ["界面美化"],
    description = "禁用主页下滑进入「最近」页：在首页会话列表顶部拦截向下拖拽，并吞掉下拉弹层的下拉手势"
)
object HideHomeScreenSwipeDownPage : SwitchFeature() {

    private const val TAG = "HideHomeScreenSwipe"
    private const val BOUNCE_VIEW_NAME = "com.tencent.mm.ui.widget.pulldown.WeUIBounceViewV2"

    /** bounce 视图 id -> [downRawY, downRawX]（跨事件记录下拉起点）。 */
    private val downPos = ConcurrentHashMap<Int, FloatArray>()

    override fun onEnable() {
        // ① 历史兜底：Hook ListView.addHeaderView —— 拦截「最近」页 TaskBarContainer 的添加
        //    （新版微信不再走 addHeaderView 添加下拉容器，此钩子仅作兼容，无害）
        try {
            ListView::class.reflekt()
                .firstMethod {
                    name = "addHeaderView"
                    parameterCount = 3
                }
                .hookBefore {
                    val view = args[0] as? View ?: return@hookBefore
                    val className = view.javaClass.name
                    if (!className.contains("TaskBar")) return@hookBefore

                    try {
                        // 用等高的空白 spacer 替换 TaskBarContainer
                        val heightDp = if (!ConversationGrouping.isEnabled) 48 else 94
                        val heightPx = (heightDp * view.resources.displayMetrics.density).toInt()
                        val spacer = View(view.context).apply {
                            layoutParams = AbsListView.LayoutParams(
                                AbsListView.LayoutParams.MATCH_PARENT, heightPx
                            )
                        }
                        // 先调用原始方法添加 spacer，再阻止原 TaskBarContainer 的添加
                        invokeOriginal(args = arrayOf(spacer, null, true))
                        result = null
                    } catch (e: Throwable) {
                        WeLogger.w(TAG, "replace TaskBarContainer failed, blocking instead", e)
                        result = null
                    }
                }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "注册 addHeaderView 钩子失败", e)
        }

        // ② 下拉弹层兜底：WeUIBounceViewV2 是「最近」页的下拉容器。
        //    只在【向下拖拽 + 会话列表在顶部】时吞掉它的 MOVE（DOWN 一律放行，
        //    保证点击/长按仍是原生行为；其他方向手势也放行）。
        //    钩子同时挂在 View 与 ViewGroup 的 dispatchTouchEvent 上：若下拉容器
        //    覆写了 dispatchTouchEvent，View 层钩子不会触发，ViewGroup 层兜住；
        //    ViewGroup 若也被覆写，View 层钩子兜住（两个位置都判断类名，开销极小）。
        try {
            View::class.reflekt()
                .firstMethod {
                    name = "dispatchTouchEvent"
                    parameterCount = 1
                }
                .hookBefore {
                    if (!isActive) return@hookBefore
                    val v = thisObject as? View ?: return@hookBefore
                    if (!isPullContainer(v)) return@hookBefore
                    val ev = args[0] as? MotionEvent ?: return@hookBefore
                    val id = System.identityHashCode(v)
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downPos[id] = floatArrayOf(ev.rawY, ev.rawX)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val start = downPos[id] ?: return@hookBefore
                            val dy = ev.rawY - start[0]
                            val dx = ev.rawX - start[1]
                            val pullSlop = 6 * v.resources.displayMetrics.density
                            val act = contextActivity(v.context) ?: return@hookBefore
                            if (dy > pullSlop && Math.abs(dx) <= dy * 1.2f && shouldBlock(act, start[0])) {
                                WeLogger.d(TAG, "View层已吞掉下拉 MOVE dy=$dy")
                                result = true
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            downPos.remove(id)
                        }
                    }
                }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "注册 View 层下拉拦截钩子失败", e)
        }

        try {
            ViewGroup::class.reflekt()
                .firstMethod {
                    name = "dispatchTouchEvent"
                    parameterCount = 1
                }
                .hookBefore {
                    if (!isActive) return@hookBefore
                    val v = thisObject as? ViewGroup ?: return@hookBefore
                    if (!isPullContainer(v)) return@hookBefore
                    val ev = args[0] as? MotionEvent ?: return@hookBefore
                    val id = System.identityHashCode(v)
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downPos[id] = floatArrayOf(ev.rawY, ev.rawX)
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val start = downPos[id] ?: return@hookBefore
                            val dy = ev.rawY - start[0]
                            val dx = ev.rawX - start[1]
                            val pullSlop = 6 * v.resources.displayMetrics.density
                            val act = contextActivity(v.context) ?: return@hookBefore
                            if (dy > pullSlop && Math.abs(dx) <= dy * 1.2f && shouldBlock(act, start[0])) {
                                WeLogger.d(TAG, "ViewGroup层已吞掉下拉 MOVE dy=$dy")
                                result = true
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            downPos.remove(id)
                        }
                    }
                }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "注册 ViewGroup 层下拉拦截钩子失败", e)
        }
    }

    /** 是否属于「最近」页下拉容器家族（类名匹配，宽松覆盖不同版本命名）。 */
    private fun isPullContainer(v: View): Boolean {
        val cn = v.javaClass.name
        return cn.contains("WeUIBounceView") || cn.contains("BounceView") ||
                cn.contains("pulldown") || cn.contains("PullDown") || cn.contains("TaskBar")
    }

    override fun onDisable() {
        // 钩子由 BaseFeature 自动 unhookAll 卸载，这里清理残留状态
        downPos.clear()
    }

    /**
     * 是否应阻止下拉：会话列表在顶部，或列表判定失败时下拉起点在屏幕上方 20% 区域。
     */
    private fun shouldBlock(act: Activity, downY: Float): Boolean {
        return try {
            // 只在微信首页 Tab（会话列表）拦截：通讯录/发现/我等 Tab 的手势完全放行
            if (!HomeSidePanelFeature.isHomePageActive(act)) return false
            val list = findConversationList(act.window?.decorView)
            if (list != null) {
                if (list is AbsListView) {
                    list.firstVisiblePosition <= 1
                } else {
                    runCatching {
                        val lm = list.javaClass.getMethod("getLayoutManager").invoke(list)
                        val fv = lm?.javaClass
                            ?.getMethod("findFirstCompletelyVisibleItemPosition")
                            ?.invoke(lm) as? Int ?: return@runCatching false
                        fv <= 1
                    }.getOrElse { false }
                }
            } else {
                val h = act.window?.decorView?.height ?: 0
                h > 0 && downY < h * 0.2f
            }
        } catch (e: Throwable) {
            WeLogger.d(TAG, "判定是否阻止下拉失败: ${e.message}")
            false
        }
    }

    /** 从 View 的 Context 链中寻找 Activity（用于判定会话列表是否在顶部）。 */
    private fun contextActivity(ctx: Context?): Activity? {
        var c = ctx
        while (c != null) {
            if (c is Activity) return c
            c = (c as? android.content.ContextWrapper)?.baseContext
        }
        return null
    }

    /** 首页会话列表是否在顶部。 */
    private fun isTopConversationList(act: Activity): Boolean {
        return try {
            val list = findConversationList(act.window?.decorView) ?: return false
            if (list is AbsListView) {
                list.firstVisiblePosition <= 1
            } else {
                runCatching {
                    val lm = list::class.java.getMethod("getLayoutManager").invoke(list)
                    val fv = lm?.javaClass
                        ?.getMethod("findFirstCompletelyVisibleItemPosition")
                        ?.invoke(lm) as? Int ?: return false
                    fv <= 1
                }.getOrElse { false }
            }
        } catch (e: Throwable) {
            WeLogger.d(TAG, "判定会话列表顶部失败: ${e.message}")
            false
        }
    }

    /** 在视图树中查找会话列表（ConversationListView 优先，其次首个 AbsListView）。 */
    private fun findConversationList(root: View?): View? {
        if (root == null) return null
        var firstAbsList: View? = null
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val v = queue.removeFirst()
            val cn = v.javaClass.name
            if (cn.contains("ConversationListView")) return v
            if (firstAbsList == null && v is AbsListView) firstAbsList = v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) queue.addLast(v.getChildAt(i))
            }
        }
        return firstAbsList
    }
}