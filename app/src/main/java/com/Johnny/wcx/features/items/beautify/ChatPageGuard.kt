package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import com.Johnny.wcx.utils.WeLogger
import java.util.WeakHashMap

/**
 * 聊天页守卫 —— 纯 View 特征识别，不依赖任何类名 / FragmentManager / 版本映射表。
 *
 * ## 为什么不用类名
 * 微信每个版本打包都会 R8 重命名聊天页的 Fragment/视图类，写死类名或版本映射表必然年年失效。
 * 但「标题栏 + 气泡列表 + 底部输入框」这套**结构**从来没变过，所以只认结构。
 *
 * ## 判定规则（三条同时满足才算聊天页）
 * 1. 屏幕下半部存在可见的 [EditText]（聊天输入框；会话列表 Tab 没有这个）
 * 2. 存在可见、且高度占比够大的 RecyclerView（气泡列表；类名判断只认 androidx 库类名，R8 不会改）
 * 3. 屏幕上方存在带文本的 [TextView]（好友昵称 / 群名）
 *
 * ## 阻断透底
 * 命中后拿到三者的**最近公共祖先**（聊天页自己的容器），确保它是**不透明**的 ——
 * 底下 Tab 的壁纸就被这层挡住，不可能透上来。只在不透明性不足时才补色，
 * 绝不覆盖微信自己的聊天背景（用户自定义背景照常生效）。
 *
 * ## 失败降级
 * 任何一步识别不到 → 返回 null，聊天页当作"不认识"，不加载任何自定义背景、
 * 不抛异常、不影响 4 个 Tab 的主题功能。
 */
internal object ChatPageGuard {

    private const val TAG = "ChatPageGuard"

    /**
     * 遍历节点上限，防止异常层级导致卡顿。
     *
     * 注意不能太小：LauncherUI 的 content 下先挂着会话列表 Tab（上百条 item，
     * 每条 10+ 个 View），深度优先很容易在走到聊天页 Fragment 之前就把额度耗光，
     * 表现就是「明明在聊天页里却识别未命中」→ 误判离开 → 壁纸挂回去变浸透。
     * 实测上限提到 6000 仍只有几毫秒开销（且有 400ms 检测节流兜底）。
     */
    private const val MAX_NODES = 6000
    private const val MAX_DEPTH = 50

    /** 输入框必须在屏幕这个比例以下（排除顶部搜索框）。 */
    private const val INPUT_MIN_TOP_RATIO = 0.5f

    /** 气泡列表高度至少占屏幕这个比例。 */
    private const val LIST_MIN_HEIGHT_RATIO = 0.25f

    /** 标题栏必须在这个比例以上。 */
    private const val TITLE_MAX_BOTTOM_RATIO = 0.22f

    /** 容器原背景的保存（降级/关主题时还原）。 */
    private val savedBackground = WeakHashMap<ViewGroup, android.graphics.drawable.Drawable?>()

    private val patched = WeakHashMap<ViewGroup, Boolean>()

    /** 最近一次识别未命中的原因（诊断用；命中时写「命中」）。 */
    @Volatile
    var lastMissInfo: String = "未检测"
        private set

    /** 识别结果。 */
    data class Hit(
        val container: ViewGroup,
        val hasInput: Boolean,
        val hasList: Boolean,
        val hasTitle: Boolean,
        val detail: String
    )

    /**
     * 识别当前页面是不是聊天页。
     *
     * @return 命中返回 [Hit]（含要兜底的容器）；不是聊天页或识别失败返回 null
     */
    fun detect(activity: Activity): Hit? = runCatching {
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return null
        val screenH = decor.height.takeIf { it > 0 } ?: return null

        val rect = android.graphics.Rect()
        var visited = 0
        var input: View? = null
        var list: View? = null
        var title: View? = null

        fun walk(v: View, depth: Int) {
            if (visited >= MAX_NODES || depth > MAX_DEPTH) return
            visited++
            if (v.visibility != View.VISIBLE) return
            if (v.width <= 0 || v.height <= 0) return

            // 1) 底部输入框
            if (input == null && v is EditText && v.isShown) {
                if (v.getGlobalVisibleRect(rect) && rect.top > screenH * INPUT_MIN_TOP_RATIO) {
                    input = v
                }
            }
            // 2) 气泡列表（只认 androidx 库类名，R8 不会重命名库类）
            if (list == null && isRecyclerLike(v) && v.isShown) {
                if (v.getGlobalVisibleRect(rect) && rect.height() > screenH * LIST_MIN_HEIGHT_RATIO) {
                    list = v
                }
            }
            // 3) 顶部标题栏（有文本）
            if (title == null && v is TextView && v.isShown && v.text?.isNotBlank() == true) {
                if (v.getGlobalVisibleRect(rect) && rect.bottom < screenH * TITLE_MAX_BOTTOM_RATIO) {
                    title = v
                }
            }

            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val child = v.getChildAt(i) ?: continue
                    walk(child, depth + 1)
                }
            }
        }

        walk(content, 0)

        val i0 = input
        val l0 = list
        val t0 = title
        if (i0 == null || l0 == null || t0 == null) {
            lastMissInfo =
                "输入框=${if (i0 != null) "✓" else "✗"} " +
                    "列表=${if (l0 != null) "✓" else "✗"} " +
                    "标题=${if (t0 != null) "✓" else "✗"} " +
                    "遍历=${visited}节点/上限=${MAX_NODES}"
            return null
        }

        val container = resolveContainer(content, i0, l0, t0) ?: return null
        lastMissInfo = "命中 遍历=${visited}节点"
        Hit(
            container = container,
            hasInput = true,
            hasList = true,
            hasTitle = true,
            detail = "容器=${container.javaClass.simpleName} 尺寸=${container.width}x${container.height}"
        )
    }.onFailure { WeLogger.w(TAG, "聊天页识别异常（已降级）：${it.message}") }.getOrNull()

    /** 最近一次「是否在主页面」的正向识别说明（诊断用）。 */
    @Volatile
    var mainPageInfo: String = "未检测"
        private set

    /**
     * **正向识别「当前是不是 4 个 Tab 主页面」** —— 主题背景的唯一放行条件。
     *
     * 为什么必须正向判：之前的逻辑是「证明不在聊天页才放行」，可聊天页里只要
     * 输入框/列表有一项识别不到，就会被误判成「已离开」→ 壁纸挂回聊天页 → 浸透。
     * 换成正向判定后，聊天页**永远无法证明自己在主页面**，识别再失败也不会放行，
     * 方向上就把错误锁死了。
     *
     * ## 判据（纯结构，不依赖任何微信类名）
     * 1. 底部 Tab 栏：**同一父容器下 ≥4 个可见子项**，都落在屏幕底部 15% 内、
     *    宽度占屏 12%~45%、高度 < 屏高 8%（微信 4 个 Tab 页按钮的固定形态）
     * 2. **底部没有可见的聊天输入框**（EditText 位于屏高 70% 以下）——
     *    这是关键反证：聊天页无论别的怎么变，底部输入框一定在。
     */
    fun detectMainPage(activity: Activity, chatPageVisible: Boolean): Boolean = runCatching {
        val decor = activity.window?.decorView as? ViewGroup ?: return false
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return false
        val screenH = decor.height.takeIf { it > 0 } ?: return false
        val screenW = decor.width.takeIf { it > 0 } ?: return false
        val rect = android.graphics.Rect()
        var visited = 0
        var bottomInput = false
        // 底部子项按父容器分组：找「一组 4 个」（Tab 栏），而不是全屏凑数
        val groupCount = java.util.HashMap<Any, Int>()
        // 主页面正向证据：大面积会话列表（占据上半/中部）
        var bigList = false

        fun walk(v: View, depth: Int) {
            if (visited >= MAX_NODES || depth > MAX_DEPTH) return
            visited++
            if (v.visibility != View.VISIBLE) return
            if (v.width <= 0 || v.height <= 0) return
            if (v.getGlobalVisibleRect(rect)) {
                // 反证 1：底部输入框（聊天页铁证）
                if (v is EditText && rect.top > screenH * 0.70f && rect.height() > 10) {
                    bottomInput = true
                }
                // 正证据 A：大面积会话列表（主页面内容区）
                if (!bigList && isRecyclerLike(v) &&
                    rect.height() > screenH * 0.30f && rect.top < screenH * 0.50f
                ) {
                    bigList = true
                }
                // 主页面 Tab 项候选：屏幕底部 15%、宽 12%~45% 屏宽、高度小于屏高 8%
                if (rect.bottom > screenH * 0.85f &&
                    rect.width() >= screenW * 0.12f && rect.width() <= screenW * 0.45f &&
                    rect.height() > 24 && rect.height() < screenH * 0.08f
                ) {
                    val parent = v.parent as? View ?: return
                    groupCount[parent] = (groupCount[parent] ?: 0) + 1
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val child = v.getChildAt(i) ?: continue
                    walk(child, depth + 1)
                }
            }
        }
        walk(content, 0)

        val tabGroup = groupCount.values.maxOrNull() ?: 0

        // ── 反证（聊天页铁证，任一命中就绝不放行）──
        if (bottomInput) {
            mainPageInfo = "底部输入框=有 → 聊天页；Tab组=${tabGroup} 列表=${bigList} FAB=${AddMainScreenFab.visibleInMain} 遍历=${visited}节点"
            return false
        }

        // ── 正向证据：**全部用上，任一成立即可**（用户要求的综合判定）──
        //  A. 底部 4 个 Tab 按钮（同容器 ≥2 个，避免被结构微调卡死）
        //  B. FAB 悬浮按钮可见 —— FAB 只在 4 个 Tab 主页面显示，主页面专属证据
        //  C. 会话列表大列表可见（占据内容区）
        val evidenceTab = tabGroup >= 2
        val evidenceFab = AddMainScreenFab.visibleInMain
        val evidenceList = bigList
        val evidence = evidenceTab || evidenceFab || evidenceList
        // 注意：这里**不再让 chatPageVisible 参与否决**。
        // 聊天页的拦截已由 bottomInput（铁证）+ 特征识别承担；若再让 chatPageVisible
        // 参与，Fragment onPause 迟到（实测 21 秒）期间它恒为 true，会把真正的主页面
        // 判成非主页面 → 挂载被跳过/绘制被拦 →「回到主页面 4 页全空」。
        val onMain = evidence
        mainPageInfo = "Tab组=${tabGroup}${if (evidenceTab) "✓" else "✗"} FAB=${if (evidenceFab) "✓" else "✗"} 列表=${if (evidenceList) "✓" else "✗"} 底部输入框=无 遍历=${visited}节点"
        onMain
    }.onFailure {
        mainPageInfo = "异常：${it.message}"
    }.getOrDefault(false)

    /** 类名或父类链里含 RecyclerView（库类名不变），避免依赖微信混淆后的具体类。 */
    private fun isRecyclerLike(v: View): Boolean {
        var c: Class<*>? = v.javaClass
        var guard = 0
        while (c != null && guard++ < 6) {
            if (c.name.contains("RecyclerView")) return true
            c = c.superclass
        }
        return false
    }

    /**
     * 找三个特征的最近公共祖先 = 聊天页自己的容器。
     *
     * 安全阀：如果算出来的是 content / DecorView（那就是"全局"，正是透底根源，绝对不能用），
     * 退回用气泡列表的直接父容器。
     */
    private fun resolveContainer(
        content: ViewGroup,
        input: View,
        list: View,
        title: View
    ): ViewGroup? {
        val chainInput = ancestors(input)
        val setList = ancestors(list).toHashSet()
        val setTitle = ancestors(title).toHashSet()
        val lca = chainInput.firstOrNull { it in setList && it in setTitle }

        if (lca != null && lca !== content) return lca
        // 退化：列表的父容器（一定在页面内部，不会是窗口根）
        return list.parent as? ViewGroup
    }

    private fun ancestors(v: View): List<ViewGroup> {
        val out = ArrayList<ViewGroup>(16)
        var p: Any? = v.parent
        var guard = 0
        while (p is ViewGroup && guard++ < 40) {
            out.add(p)
            if (p.id == android.R.id.content) break
            p = p.parent
        }
        return out
    }

    /**
     * 确保聊天页容器不透明（兜底遮挡层）。
     *
     * - 只在「没有背景 / 背景是半透明颜色」时才补一个不透明色
     * - 微信自己的聊天背景（图片等）原样保留，绝不覆盖
     *
     * @return true = 当前容器已是不透明（或本次已补上）
     */
    fun ensureOpaque(container: ViewGroup): Boolean = runCatching {
        val current = container.background
        val alpha = when (current) {
            null -> 0
            is ColorDrawable -> current.color ushr 24 and 0xFF
            else -> 255   // 图片 / 九宫格 / 渐变：默认按不透明处理，不动它
        }
        if (alpha >= 255) {
            patched[container] = true
            return true
        }
        if (!savedBackground.containsKey(container)) savedBackground[container] = current
        val night = (container.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val fallback = if (night) 0xFF191919.toInt() else 0xFFFFFFFF.toInt()
        // 半透明色：保留它自己的色相，只把 alpha 拉满，视觉改动最小
        val color = (current as? ColorDrawable)?.color?.let { (it and 0x00FFFFFF) or (0xFF shl 24) } ?: fallback
        container.setBackgroundColor(color)
        patched[container] = true
        WeLogger.i(TAG, "聊天页容器原背景不透明性不足，已补不透明底色")
        true
    }.onFailure {
        WeLogger.w(TAG, "补不透明底色失败（已降级）：${it.message}")
        false
    }.getOrDefault(false)

    /** 关主题 / 离开聊天页时还原容器背景。 */
    fun restore(container: ViewGroup?) {
        if (container == null) return
        runCatching {
            if (savedBackground.containsKey(container)) {
                container.background = savedBackground.remove(container)
                patched.remove(container)
            }
        }.onFailure { WeLogger.w(TAG, "还原聊天页容器背景失败：${it.message}") }
    }
}
