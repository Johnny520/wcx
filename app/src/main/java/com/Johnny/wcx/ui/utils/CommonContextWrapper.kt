package com.Johnny.wcx.ui.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import com.Johnny.wcx.utils.reflection.ClassLoaders

/**
 * 模块注入 UI 时使用的 Context 包装。
 *
 * 历史实现曾把 Resources 也包装成 `ResourcesWrapper : Resources(base.assets, ...)`，
 * 那并不是「委托」，而是用 base 的 AssetManager **新建了一个 Resources 实例**。
 * 在 Android 15/16（targetSdk 36）上，未重写的 `Resources.getDrawableForDensity`
 * 会走到这个新建实例的 impl，其资源 cookie 与微信原包不一致，于是
 * `WeImageView.setImageResource()` 抛
 * `Resources$NotFoundException: Drawable com.tencent.mm:raw/icons_filled_edited`，微信进程崩溃。
 *
 * 因此这里改为**完全不碰 Resources**：getResources() 直接返回宿主原 Resources，
 * 保证微信所有 drawable / color / dimen 的查找行为与未注入时完全一致。
 *
 * 说明：`Context.getString` / `getText` / `getDrawable` 在 framework 中均为 final，
 * 无法在这一层做「缺串降级」，因此不再尝试覆盖；模块内 Compose 文案均为中文字面量，
 * 不依赖模块 R.string，实际不受影响。
 */
class CommonContextWrapper(val base: Context) : ContextWrapper(base) {

    override fun getClassLoader(): ClassLoader {
        return ClassLoaders.MODULE
    }

    /**
     * 必须返回宿主原 Resources，禁止再包装或新建实例。
     * 详见类注释：包装会让微信自身的资源查找失败并崩溃。
     */
    override fun getResources(): Resources {
        return base.resources
    }
}
