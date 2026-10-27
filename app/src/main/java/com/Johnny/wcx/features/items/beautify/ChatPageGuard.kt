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

    /** 遍历节点上限，防止异常层级导致卡顿。 */
    private const val MAX_NODES = 1500
    private const val MAX_DEPTH = 32

    /** 输入框必须在屏幕这个比例以下（排除顶部搜索框）。 */
    private const val INPUT_MIN_TOP_RATIO = 0.5f

    /** 气泡列表高度至少占屏幕这个比例。 */
    private const val LIST_MIN_HEIGHT_RATIO = 0.25f

    /** 标题栏必须在这个比例以上。 */
    private const val TITLE_MAX_BOTTOM_RATIO = 0.22f

    /** 容器原背景的保存（降级/关主题时还原）。 */
    private val savedBackground = WeakHashMap<ViewGroup, android.graphics.drawable.Drawable?>()

    private val patched = WeakHashMap<ViewGroup, Boolean>()

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

        val i0 = input ?: return null
        val l0 = list ?: return null
        val t0 = title ?: return null

        val container = resolveContainer(content, i0, l0, t0) ?: return null
        Hit(
            container = container,
            hasInput = true,
            hasList = true,
            hasTitle = true,
            detail = "容器=${container.javaClass.simpleName} 尺寸=${container.width}x${container.height}"
        )
    }.onFailure { WeLogger.w(TAG, "聊天页识别异常（已降级）：${it.message}") }.getOrNull()

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
