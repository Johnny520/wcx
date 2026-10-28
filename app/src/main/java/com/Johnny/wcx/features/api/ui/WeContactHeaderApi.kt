package com.Johnny.wcx.features.api.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContextWrapper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.utils.findViewsWhich
import com.Johnny.wcx.utils.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 联系人资料页「头部文字」扩展点。
 *
 * 微信的资料页头部（昵称 / 微信号 / 地区那几行）由 `NormalProfileHeaderPreference.onBindView`
 * 一次性绑定，本 API 在它绑定完成后，往那个竖向容器里追加自定义 TextView，
 * 因此注入的文案会紧跟在「地区」下面，而不是跑到资料页的 Preference 列表末尾。
 *
 * 实现要点（都沿用原生行为，避免被微信的内部刷新覆盖或点错行）：
 * - 用原生自己的 longClick 监听器身份定位模板 TextView，不依赖混淆后的资源 ID 或文案；
 * - 新建的 TextView 复制模板的字号 / 颜色 / 内边距 / 行距 / gravity；
 * - 复刻原生的 OnLongClickListener 与 touch 监听（后者同时设置了原生弹窗定位用的坐标 tag）。
 */
@Feature(name = "联系人头部扩展", categories = ["API"])
object WeContactHeaderApi : ApiFeature(), IResolveDex {

    private const val TAG = "WeContactHeaderApi"
    private const val ROW_TAG = "wcx_contact_header_row"

    fun interface Provider {
        /** 返回 null 表示这个资料页不需要额外一行。 */
        fun getHeaderText(activity: Activity): String?
    }

    private val providers = CopyOnWriteArrayList<Provider>()

    /** `[onBindView] never attach!` 在 8.0.71~8.0.79 均存在，跨版本稳定。 */
    private val bindHeader by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.plugin.profile.ui.NormalProfileHeaderPreference"
            paramTypes(View::class.java)
            returnType(Void.TYPE)
            usingStrings("[onBindView] never attach!")
        }
    }

    fun addProvider(provider: Provider) {
        providers.addIfAbsent(provider)
    }

    fun removeProvider(provider: Provider) {
        providers.remove(provider)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onEnable() {
        if (bindHeader.isPlaceholder) {
            WeLogger.w(TAG, "当前微信版本未找到联系人头部锚点，头部扩展不可用")
            return
        }
        bindHeader.hookAfter {
            // args / thisObject 只在 hook 回调作用域内可见，先取出来再交给普通函数处理。
            val root = args.getOrNull(0) as? View
            val header = thisObject as? View.OnLongClickListener
            runCatching { injectRows(root, header) }.onFailure {
                WeLogger.w(TAG, "注入头部文字失败：" + (it.message ?: it.javaClass.simpleName))
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun injectRows(root: View?, header: View.OnLongClickListener?) {
        if (root == null || header == null) return
        // 宿主只有在 preference attach 之后才创建 ViewHolder。
        if (root.tag == null) return

        // 每次重新绑定都先清掉上一次注入的行，避免重复添加。
        root.findViewsWhich<View> { it.tag == ROW_TAG }.toList().forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        if (providers.isEmpty()) return

        var context = root.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val activity = context as? Activity ?: return

        // 原生 ViewHolder 把昵称、备注、微信号、地区这几行都指向同一个 longClick 监听器，
        // 用这个身份定位模板，比按文案或混淆 ID 都稳。
        val templates = root.findViewsWhich<TextView> { view ->
            view is TextView && runCatching {
                view.reflekt().getField("mListenerInfo", true)?.let { info ->
                    info.reflekt().getField("mOnLongClickListener") === header
                } == true
            }.getOrDefault(false)
        }.toList()
        if (templates.isEmpty()) return

        val parent = templates.mapNotNull { it.parent }.distinct().singleOrNull() as? LinearLayout ?: return
        if (parent.orientation != LinearLayout.VERTICAL) return

        val template = templates.first()
        val touch = runCatching {
            template.reflekt().getField("mListenerInfo", true)!!
                .reflekt().getField("mOnTouchListener") as View.OnTouchListener
        }.getOrNull() ?: return

        for (provider in providers) {
            val headerText = provider.getHeaderText(activity) ?: continue
            val row = TextView(template.context).apply {
                id = View.generateViewId()
                tag = ROW_TAG
                (template.layoutParams as? LinearLayout.LayoutParams)?.let {
                    layoutParams = LinearLayout.LayoutParams(it)
                }
                setTextSize(TypedValue.COMPLEX_UNIT_PX, template.textSize)
                setTextColor(template.textColors)
                typeface = template.typeface
                gravity = template.gravity
                includeFontPadding = template.includeFontPadding
                textDirection = template.textDirection
                setPaddingRelative(
                    template.paddingStart,
                    template.paddingTop,
                    template.paddingEnd,
                    template.paddingBottom,
                )
                setLineSpacing(template.lineSpacingExtra, template.lineSpacingMultiplier)
                text = headerText
                // 这一步同时设置了原生弹窗定位所需的坐标 tag。
                setOnTouchListener(touch)
                setOnLongClickListener {
                    val ownId = id
                    try {
                        id = template.id
                        header.onLongClick(this)
                    } finally {
                        id = ownId
                    }
                }
            }
            parent.addView(row)
        }
    }
}
