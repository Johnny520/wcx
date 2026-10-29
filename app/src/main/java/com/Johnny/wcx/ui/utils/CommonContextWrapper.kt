@file:Suppress("DEPRECATION")

package com.Johnny.wcx.ui.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import com.Johnny.wcx.utils.reflection.ClassLoaders

/**
 * 模块注入 UI 时使用的 Context 包装。
 *
 * 历史实现曾把 Resources 也包装成 `ResourcesWrapper : Resources(base.assets, ...)`，
 * 它用「base 的 AssetManager 新建一个 Resources 实例」来代替委托。这种写法在
 * Android 15/16（targetSdk 36）上会让**微信自己的资源**查找失败：
 * `Resources.getDrawableForDensity` 走到新建实例的 impl，资源 cookie 与微信原包不匹配，
 * 于是 `WeImageView.setImageResource()` 抛
 * `Resources$NotFoundException: Drawable com.tencent.mm:raw/xxx`，微信直接崩溃。
 *
 * 因此这里改为**完全不碰 Resources**：getResources() 直接返回宿主原 Resources，
 * 保证微信所有 drawable/color/dimen 查找行为与未注入时完全一致。
 * 只把「字符串查找失败降级」挪到 Context 层（Context.getString/getText 可 override），
 * 保留原先避免 plurals/缺串崩溃的保护。
 */
class CommonContextWrapper(val base: Context) : ContextWrapper(base) {

    override fun getClassLoader(): ClassLoader {
        return ClassLoaders.MODULE
    }

    /**
     * 必须返回宿主原 Resources，禁止再包装/新建实例。
     * 详见类注释：包装会导致微信自身资源查找失败并崩溃。
     */
    override fun getResources(): Resources {
        return base.resources
    }

    // ==========================================
    // 字符串降级（防止缺串 / plurals 缺失导致崩溃）
    // ==========================================

    override fun getString(id: Int): String {
        return runCatching { base.getString(id) }.getOrDefault("")
    }

    override fun getString(id: Int, vararg formatArgs: Any?): String {
        return runCatching { base.getString(id, *formatArgs) }.getOrDefault("")
    }

    override fun getText(id: Int): CharSequence {
        return runCatching { base.getText(id) }.getOrDefault("")
    }

    override fun getText(id: Int, def: CharSequence?): CharSequence {
        return runCatching { base.getText(id, def) }.getOrDefault(def ?: "")
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun getDrawable(id: Int) = base.getDrawable(id)

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun getDrawable(id: Int, theme: Resources.Theme?) = base.getDrawable(id, theme)
}
