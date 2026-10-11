package h.Hchat.hooks.items.script.agent

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.Resources.NotFoundException
import android.content.res.Resources.Theme
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics

internal class ModuleFallbackResources(
    private val base: Resources,
    private val module: Resources,
) : Resources(base.assets, base.displayMetrics, base.configuration) {

    override fun getConfiguration(): Configuration = base.configuration

    override fun getDisplayMetrics(): DisplayMetrics = base.displayMetrics

    override fun getDrawable(id: Int, theme: Theme?): Drawable? =
        try {
            super.getDrawable(id, theme)
        } catch (e: NotFoundException) {
            runCatching { module.getDrawable(id, null) }.getOrNull()
                ?: ColorDrawable(Color.TRANSPARENT)
        }

    override fun getDrawableForDensity(id: Int, density: Int, theme: Theme?): Drawable? =
        try {
            super.getDrawableForDensity(id, density, theme)
        } catch (e: NotFoundException) {
            runCatching { module.getDrawableForDensity(id, density, null) }.getOrNull()
                ?: ColorDrawable(Color.TRANSPARENT)
        }

    override fun getString(id: Int): String =
        try {
            super.getString(id)
        } catch (e: NotFoundException) {
            runCatching { module.getString(id) }.getOrDefault("")
        }

    override fun getString(id: Int, vararg formatArgs: Any): String =
        try {
            super.getString(id, *formatArgs)
        } catch (e: NotFoundException) {
            runCatching { module.getString(id, *formatArgs) }.getOrDefault("")
        }

    override fun getText(id: Int): CharSequence =
        try {
            super.getText(id)
        } catch (e: NotFoundException) {
            runCatching { module.getText(id) }.getOrDefault("")
        }

    override fun getColor(id: Int): Int =
        try {
            super.getColor(id)
        } catch (e: NotFoundException) {
            runCatching { module.getColor(id) }.getOrDefault(0)
        }

    override fun getColor(id: Int, theme: Theme?): Int =
        try {
            super.getColor(id, theme)
        } catch (e: NotFoundException) {
            runCatching { module.getColor(id, theme) }.getOrDefault(0)
        }

    override fun getColorStateList(id: Int): ColorStateList =
        try {
            super.getColorStateList(id)
        } catch (e: NotFoundException) {
            runCatching { module.getColorStateList(id) }.getOrNull()
                ?: ColorStateList.valueOf(Color.TRANSPARENT)
        }

    override fun getColorStateList(id: Int, theme: Theme?): ColorStateList =
        try {
            super.getColorStateList(id, theme)
        } catch (e: NotFoundException) {
            runCatching { module.getColorStateList(id, theme) }.getOrNull()
                ?: ColorStateList.valueOf(Color.TRANSPARENT)
        }

    override fun getDimension(id: Int): Float =
        try {
            super.getDimension(id)
        } catch (e: NotFoundException) {
            runCatching { module.getDimension(id) }.getOrDefault(0f)
        }

    override fun getDimensionPixelSize(id: Int): Int =
        try {
            super.getDimensionPixelSize(id)
        } catch (e: NotFoundException) {
            runCatching { module.getDimensionPixelSize(id) }.getOrDefault(0)
        }

    override fun getDimensionPixelOffset(id: Int): Int =
        try {
            super.getDimensionPixelOffset(id)
        } catch (e: NotFoundException) {
            runCatching { module.getDimensionPixelOffset(id) }.getOrDefault(0)
        }

    override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int {
        val host = super.getIdentifier(name, defType, defPackage)
        if (host != 0) return host
        return runCatching { module.getIdentifier(name, defType, defPackage) }.getOrDefault(0)
    }

    override fun getStringArray(id: Int): Array<String> =
        try {
            super.getStringArray(id)
        } catch (e: NotFoundException) {
            runCatching { module.getStringArray(id) }.getOrDefault(emptyArray())
        }

    override fun getTextArray(id: Int): Array<CharSequence> =
        try {
            super.getTextArray(id)
        } catch (e: NotFoundException) {
            runCatching { module.getTextArray(id) }.getOrDefault(emptyArray())
        }
}
