package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge

@Feature(name = "自动查看原图", categories = ["聊天"], description = "在打开图片和视频时自动点击查看原图")
object AutoViewOriginalMedia : SwitchFeature(), IResolveDex {

    private const val TAG = "AutoViewOriginalMedia"

    /** 原图 / 原视频按钮的文案（中英文都认），只在本页面视图树内匹配。 */
    private val BUTTON_KEYWORDS = listOf("查看原图", "Full Image", "查看原视频", "Original quality")

    /** 命中即节流：这两个方法在图片查看页每切一张图都会触发。 */
    @Volatile
    private var lastHitAt = 0L

    private const val CLICK_THROTTLE_MS = 1200L

    private val methodSetImageHdImgBtnVisibility by dexMethod()
    private val methodCheckNeedShowOriginVideoBtn by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        val results = dexKit.findMethod {
            matcher {
                declaredClass = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
                usingEqStrings("setHdImageActionDownloadable")
            }
        }.ifEmpty {
            dexKit.findMethod {
                matcher {
                    declaredClass = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
                    usingEqStrings("setImageHdImgBtnVisibility")
                }
            }
        }
        // 原实现用 single()：results 为空抛 NoSuchElementException、多结果抛 IllegalArgumentException，
        // 均发生在 resolveDex 阶段且无兜底。改为空安全、多候选取首个。
        results.firstOrNull()?.let { methodSetImageHdImgBtnVisibility.setDescriptor(it) }

        methodCheckNeedShowOriginVideoBtn.find(dexKit) {
            matcher {
                declaredClass = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
                usingEqStrings("checkNeedShowOriginVideoBtn")
            }
        }
    }

    override fun onEnable() {
        listOf(
            methodSetImageHdImgBtnVisibility,
            methodCheckNeedShowOriginVideoBtn
        ).forEach { method ->
            if (method.isPlaceholder) {
                WeLogger.w(TAG, "锚点未解析，跳过：当前微信版本的 ImageGalleryUI 里没找到对应方法")
                return@forEach
            }

            method.hookAfter {
                val now = SystemClock.elapsedRealtime()
                // 命中即节流（不管有没有点到）：之前只在点击成功后才节流，
                // 没找到按钮时每次命中都做全树遍历，图片查看页连滑几张就明显卡顿。
                if (now - lastHitAt < CLICK_THROTTLE_MS) return@hookAfter
                lastHitAt = now
                runCatching {
                    if (clickOriginalButton(thisObject)) {
                        WeLogger.i(TAG, "已自动点击「查看原图 / 查看原视频」")
                    }
                }.onFailure { WeLogger.w(TAG, "自动点击原图按钮失败：${it.message}") }
            }
        }
    }

    /**
     * 在当前界面里找「查看原图 / 查看原视频」按钮并点击。
     *
     * 原实现只遍历 thisObject 里 `Button` 类型的字段：8.0.80 上这个按钮既可能不是 Button
     * （外面包一层 TextView/FrameLayout），也可能不再是字段而是动态 findViewById 出来的，
     * 结果永远点不到。改为「字段（任意 View 子类）+ Activity 视图树」两路查找，
     * 只点「可见 + 可点击 + 文案命中关键字」的 View，范围限定在当前 Activity 内。
     */
    private fun clickOriginalButton(host: Any?): Boolean {
        if (host == null) return false
        val roots = mutableListOf<View>()
        roots += collectFieldViews(host)
        (host as? Activity)?.window?.decorView?.let { roots += it }
        roots.forEach { root -> if (clickInTree(root)) return true }
        return false
    }

    private fun collectFieldViews(host: Any): List<View> {
        val out = mutableListOf<View>()
        var cls: Class<*>? = host.javaClass
        while (cls != null && cls != Any::class.java) {
            cls.declaredFields.forEach { field ->
                if (!View::class.java.isAssignableFrom(field.type)) return@forEach
                runCatching {
                    field.isAccessible = true
                    (field.get(host) as? View)?.let { out += it }
                }
            }
            cls = cls.superclass
        }
        return out
    }

    private fun clickInTree(root: View): Boolean {
        var clicked = false

        fun walk(view: View) {
            if (clicked) return
            if (view.isShown && view.isClickable) {
                val text = (view as? TextView)?.text?.toString()
                if (!text.isNullOrBlank() && BUTTON_KEYWORDS.any { text.contains(it, ignoreCase = true) }) {
                    view.performClick()
                    clicked = true
                    return
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
        }

        walk(root)
        return clicked
    }
}
