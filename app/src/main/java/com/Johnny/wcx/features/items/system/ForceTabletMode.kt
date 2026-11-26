package com.Johnny.wcx.features.items.system

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.compose.material3.Text
import androidx.core.view.isVisible
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge

/**
 * 强制平板模式：让微信把当前手机识别成平板，从而在「登录设备」页出现
 * 「作为其他设备登录」入口，实现手机 + 平板同时在线。
 *
 * ## 8.0.80 上为什么一直用不了
 *
 * 判断「是否平板」的锚点（设备型号白名单串 `Lenovo TB-9707F`）在 8.0.80 仍然有效
 * （落在 `com.tencent.mm.ui.k9#a()Z`），所以「微信以为自己是平板」这半截其实是生效的。
 *
 * 坏在另一半：把 `loginAsOtherDeviceBtn` 显示出来。旧实现是
 * `args[0] as? Button` —— 8.0.76/8.0.79 这个方法第一个参数确实是那个 Button，
 * 但 8.0.80 变成了整页的根 View（签名 `a(Landroid/view/View;Landroidx/lifecycle/y;)V`），
 * 转型恒为 null，直接 return，按钮永远是 GONE —— 用户看不到入口，就认为「平板模式没用」。
 *
 * ## 现在的做法
 *
 * 1. 设备型号判断照旧 hook 成 true（返回类型是 boolean 才改，避免误伤同名方法）；
 * 2. 按钮改为「按资源名拿 id」：`Resources.getIdentifier("loginAsOtherDeviceBtn", "id", 包名)`
 *    再 `findViewById`，找到了就直接设为可见 —— 不依赖参数类型、不依赖混淆后的类名；
 * 3. 资源名查不到时退化为文本匹配（「其他设备 / 平板 / other device」）；
 * 4. 进「登录设备」页（`LoginHistoryUI#initView`）后再扫一遍整棵树兜底。
 *
 * 所有 hook 都单独 runCatching + allowFailure，任何一个锚点解析失败都不会让整个功能装不上。
 */
@Feature(
    name = "强制平板模式",
    categories = ["系统与隐私"],
    description = "让微信将当前设备识别为平板，可用于手机与平板同时在线"
)
object ForceTabletMode : SwitchFeature(), IResolveDex {

    private const val TAG = "ForceTabletMode"

    /** 「是否平板」判断：8.0.80 → com.tencent.mm.ui.k9#a()Z。 */
    private val methodIsTablet by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("Lenovo TB-9707F")
        }
    }

    /** 「作为其他设备登录」按钮的绑定方法：8.0.80 → qb1.h0#a(View, LifecycleOwner)V。 */
    private val methodLoginAsOtherDevice by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("loginAsOtherDeviceBtn")
        }
    }

    private const val LOGIN_HISTORY_UI = "com.tencent.mm.plugin.account.ui.LoginHistoryUI"

    /**
     * 真正决定「作为其他设备登录」按钮显示的地方。
     *
     * 8.0.80 实测：`qb1.h0#a(View, LifecycleOwner)` 里通过 DynFeature 反射调用
     * `LoginUtil$Companion#updateLoginAsExdevice(View, LifecycleOwner)`，由它按账号状态
     * 决定按钮的可见性 —— 所以光显示一次没用，必须在这个函数之后再兜一遍。
     */
    private const val LOGIN_UTIL_COMPANION = "com.tencent.mm.plugin.account.model.LoginUtil\$Companion"
    private const val LOGIN_UTIL = "com.tencent.mm.plugin.account.model.LoginUtil"

    /** 按钮的资源名（微信一直保留这个名字，运行时可按名取 id）。 */
    private const val ID_LOGIN_AS_OTHER_DEVICE = "loginAsOtherDeviceBtn"

    /** 找不到资源 id 时的文本兜底关键字。 */
    private val TEXT_KEYWORDS = arrayOf("其他设备", "其它设备", "平板", "other device", "pad")

    override fun onEnable() {
        hookIsTablet()
        hookLoginAsOtherDeviceButton()
        hookUpdateLoginAsExdevice()
        hookLoginHistoryUi()
    }

    /** 只改「返回 boolean」的方法，避免对同名非判断方法（如 getInstance）造成 ClassCastException。 */
    private fun hookIsTablet() {
        if (methodIsTablet.isPlaceholder) {
            WeLogger.w(TAG, "设备型号判断锚点未解析（微信版本变化），跳过")
            return
        }
        runCatching {
            methodIsTablet.hookBefore {
                val target = method as? java.lang.reflect.Method ?: return@hookBefore
                val type = target.returnType
                if (type == Boolean::class.javaPrimitiveType || type == java.lang.Boolean::class.java) {
                    result = true
                }
            }
        }.onFailure { WeLogger.e(TAG, "hook 设备型号判断失败", it) }
    }

    /**
     * 8.0.80 这个方法拿到的是整页根 View，不是按钮本身，所以在 after 里从根 View 往下找。
     */
    private fun hookLoginAsOtherDeviceButton() {
        if (methodLoginAsOtherDevice.isPlaceholder) {
            WeLogger.w(TAG, "「作为其他设备登录」锚点未解析，跳过按钮显示")
            return
        }
        runCatching {
            methodLoginAsOtherDevice.hookAfter {
                val root = args.firstOrNull { it is View } as? View ?: return@hookAfter
                revealOtherDeviceLogin(root)
            }
        }.onFailure { WeLogger.e(TAG, "hook 其他设备登录按钮失败", it) }
    }

    /** 进「登录设备」页后再兜一遍：有些版本按钮是在 initView 之后才被隐藏的。 */
    private fun hookLoginHistoryUi() {
        runCatching {
            val clazz = Class.forName(LOGIN_HISTORY_UI, false, ClassLoaders.HOST)
            val initView = clazz.declaredMethods.firstOrNull {
                it.name == "initView" && it.parameterTypes.isEmpty()
            } ?: run {
                WeLogger.w(TAG, "$LOGIN_HISTORY_UI 里没找到 initView，跳过兜底")
                return
            }
            XposedBridge.hookMethod(initView, object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    val activity = param.thisObject as? android.app.Activity ?: return
                    val decor = activity.window?.decorView ?: return
                    runCatching { revealOtherDeviceLogin(decor) }
                        .onFailure { WeLogger.w(TAG, "登录设备页兜底失败", it) }
                }
            })
        }.onFailure { WeLogger.w(TAG, "hook $LOGIN_HISTORY_UI#initView 失败", it) }
    }

    /**
     * 钩住微信自己用来刷新这个按钮的函数。
     *
     * 它会按账号状态把按钮设成 GONE / VISIBLE，我们只在其之后补一次显示，
     * 并延迟 400ms 再补一次（微信随后还有异步回调会再刷一次可见性）。
     */
    private fun hookUpdateLoginAsExdevice() {
        val candidates = buildList {
            runCatching {
                val companion = Class.forName(LOGIN_UTIL_COMPANION, false, ClassLoaders.HOST)
                addAll(companion.declaredMethods.filter { it.name == "updateLoginAsExdevice" })
            }
            runCatching {
                val util = Class.forName(LOGIN_UTIL, false, ClassLoaders.HOST)
                addAll(util.declaredMethods.filter { it.name == "updateLoginAsExdevice" })
            }
        }.distinct()
        if (candidates.isEmpty()) {
            WeLogger.w(TAG, "没找到 updateLoginAsExdevice（版本变化），只用登录设备页兜底")
            return
        }
        candidates.forEach { method ->
            runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val root = param.args.firstOrNull { it is View } as? View ?: return
                        runCatching { revealOtherDeviceLogin(root) }
                        runCatching { root.postDelayed({ runCatching { revealOtherDeviceLogin(root) } }, 400L) }
                    }
                })
                WeLogger.i(TAG, "已 hook updateLoginAsExdevice（${method.parameterTypes.size} 参）")
            }.onFailure { WeLogger.w(TAG, "hook updateLoginAsExdevice 失败", it) }
        }
    }

    /**
     * 把「作为其他设备登录」按钮显示出来。
     *
     * 先按资源名取 id（最准）；取不到再在整棵树里按文本找。
     */
    private fun revealOtherDeviceLogin(root: View) {
        val res = root.context.resources
        val id = runCatching {
            res.getIdentifier(ID_LOGIN_AS_OTHER_DEVICE, "id", root.context.packageName)
        }.getOrDefault(0)
        if (id != 0) {
            val button = runCatching { root.findViewById<View>(id) }.getOrNull()
            if (button != null) {
                if (!button.isVisible) {
                    button.visibility = View.VISIBLE
                    WeLogger.i(TAG, "已显示「作为其他设备登录」（按资源 id）")
                }
                return
            }
        }
        // 退路：按文本找。只认文本命中的控件，避免把「退出登录」之类的 GONE 按钮一起放出来。
        var hit = 0
        walk(root) { view ->
            // Button 是 TextView 的子类，这一个分支就够。
            val text = if (view is TextView) view.text?.toString().orEmpty() else ""
            if (text.isNotBlank() && TEXT_KEYWORDS.any { text.contains(it, ignoreCase = true) }) {
                if (!view.isVisible) {
                    view.visibility = View.VISIBLE
                    hit++
                    WeLogger.i(TAG, "已显示按钮：$text")
                }
            }
        }
        if (hit == 0) WeLogger.d(TAG, "没找到需要显示的按钮（资源 id 与文本都没命中）")
    }

    private fun walk(view: View, depth: Int = 0, action: (View) -> Unit) {
        if (depth > 20) return
        action(view)
        if (view is android.view.ViewGroup) {
            for (index in 0 until view.childCount) {
                walk(view.getChildAt(index), depth + 1, action)
            }
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(text = "警告") },
                    text = { Text(text = "此功能可能导致账号异常, 确定要启用吗?") },
                    confirmButton = {
                        Button(onClick = {
                            applyToggle(true)
                            onDismiss()
                        }) {
                            Text("确定")
                        }
                    },
                    dismissButton = {
                        TextButton(onDismiss) {
                            Text("取消")
                        }
                    }
                )
            }
            return false
        }

        return true
    }
}
