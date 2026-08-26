@file:Suppress("DEPRECATION")

package com.Johnny.wcx.ui.utils

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetFileDescriptor
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.content.res.XmlResourceParser
import android.graphics.Color
import android.graphics.Movie
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.util.TypedValue
import androidx.core.graphics.drawable.toDrawable
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.ClassLoaders
import java.io.InputStream

class CommonContextWrapper(val base: Context) : ContextWrapper(base) {

    private val resourcesWrapper = ResourcesWrapper(base.resources)

    override fun getClassLoader(): ClassLoader {
        return ClassLoaders.MODULE
    }

    override fun getResources(): Resources {
        return resourcesWrapper
    }
}

@Suppress("DEPRECATION")
class ResourcesWrapper(val base: Resources) : Resources(base.assets, base.displayMetrics, base.configuration) {

    // Helper utility to keep the surface API overrides clean and standardized
    private inline fun <T> safeLog(methodName: String, fallback: T, block: () -> T): T {
        return runCatching { block() }.getOrElse { t ->
            WeLogger.d("ResourcesWrapper", "No-Context execution caught error in $methodName: ${t.localizedMessage}")
            fallback
        }
    }

    // ==========================================
    // 1. Strings & Text
    // ==========================================

    override fun getText(id: Int): CharSequence {
        return safeLog("getText(id)", "null") { base.getText(id) }
    }

    override fun getText(id: Int, def: CharSequence?): CharSequence {
        return safeLog("getText(id, def)", def ?: "null") { base.getText(id, def) }
    }

    override fun getString(id: Int): String {
        return safeLog("getString(id)", "null") { base.getString(id) }
    }

    override fun getString(id: Int, vararg formatArgs: Any?): String {
        return safeLog("getString(id, args)", "null") { base.getString(id, *formatArgs) }
    }

    override fun getQuantityText(id: Int, quantity: Int): CharSequence {
        return safeLog("getQuantityText", "null") { base.getQuantityText(id, quantity) }
    }

    override fun getQuantityString(id: Int, quantity: Int): String {
        return safeLog("getQuantityString", "null") { base.getQuantityString(id, quantity) }
    }

    override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String {
        return safeLog("getQuantityString(args)", "null") { base.getQuantityString(id, quantity, *formatArgs) }
    }

    override fun getTextArray(id: Int): Array<CharSequence> {
        return safeLog("getTextArray", emptyArray()) { base.getTextArray(id) }
    }

    override fun getStringArray(id: Int): Array<String> {
        return safeLog("getStringArray", emptyArray()) { base.getStringArray(id) }
    }

    // ==========================================
    // 2. Primitives, Dimensions & Fractions
    // ==========================================

    override fun getBoolean(id: Int): Boolean {
        return safeLog("getBoolean", false) { base.getBoolean(id) }
    }

    override fun getInteger(id: Int): Int {
        return safeLog("getInteger", 0) { base.getInteger(id) }
    }

    override fun getDimension(id: Int): Float {
        return safeLog("getDimension", 0f) { base.getDimension(id) }
    }

    override fun getDimensionPixelOffset(id: Int): Int {
        return safeLog("getDimensionPixelOffset", 0) { base.getDimensionPixelOffset(id) }
    }

    override fun getDimensionPixelSize(id: Int): Int {
        return safeLog("getDimensionPixelSize", 0) { base.getDimensionPixelSize(id) }
    }

    override fun getFraction(id: Int, baseVal: Int, pbase: Int): Float {
        return safeLog("getFraction", 0f) { base.getFraction(id, baseVal, pbase) }
    }

    override fun getIntArray(id: Int): IntArray {
        return safeLog("getIntArray", intArrayOf()) { base.getIntArray(id) }
    }

    // ==========================================
    // 3. Colors & Drawables
    // ==========================================

    @Deprecated("Deprecated in Java")
    override fun getColor(id: Int): Int {
        return safeLog("getColor", Color.TRANSPARENT) { base.getColor(id) }
    }

    override fun getColor(id: Int, theme: Theme?): Int {
        return safeLog("getColor(theme)", Color.TRANSPARENT) { base.getColor(id, theme) }
    }

    @SuppressLint("UseCompatLoadingForColorStateLists")
    @Deprecated("Deprecated in Java")
    override fun getColorStateList(id: Int): ColorStateList {
        return safeLog("getColorStateList", ColorStateList(arrayOf(), intArrayOf())) { base.getColorStateList(id) }
    }

    override fun getColorStateList(id: Int, theme: Theme?): ColorStateList {
        return safeLog("getColorStateList(theme)", ColorStateList(arrayOf(), intArrayOf())) { base.getColorStateList(id, theme) }
    }

    @Deprecated("Deprecated in Java")
    @SuppressLint("UseCompatLoadingForDrawables")
    override fun getDrawable(id: Int): Drawable {
        return safeLog("getDrawable", Color.TRANSPARENT.toDrawable()) { base.getDrawable(id) }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    override fun getDrawable(id: Int, theme: Theme?): Drawable {
        return safeLog("getDrawable(theme)", Color.TRANSPARENT.toDrawable()) { base.getDrawable(id, theme) }
    }

    @Deprecated("Deprecated in Java")
    override fun getDrawableForDensity(id: Int, density: Int): Drawable? {
        return safeLog("getDrawableForDensity", null) { base.getDrawableForDensity(id, density) }
    }

    override fun getDrawableForDensity(id: Int, density: Int, theme: Theme?): Drawable? {
        return safeLog("getDrawableForDensity(theme)", null) { base.getDrawableForDensity(id, density, theme) }
    }

    @Deprecated("Deprecated in Java")
    override fun getMovie(id: Int): Movie? {
        return safeLog("getMovie", null) { base.getMovie(id) }
    }

    // ==========================================
    // 4. Raw Resources & Streams
    // ==========================================

    override fun openRawResource(id: Int): InputStream {
        return runCatching { base.openRawResource(id) }.getOrElse { t ->
            WeLogger.d("ResourcesWrapper", "openRawResource crashed: ${t.localizedMessage}")
            java.io.ByteArrayInputStream(byteArrayOf())
        }
    }

    override fun openRawResource(id: Int, value: TypedValue?): InputStream {
        return runCatching { base.openRawResource(id, value) }.getOrElse { t ->
            WeLogger.d("ResourcesWrapper", "openRawResource(value) crashed: ${t.localizedMessage}")
            java.io.ByteArrayInputStream(byteArrayOf())
        }
    }

    override fun openRawResourceFd(id: Int): AssetFileDescriptor {
        return runCatching { base.openRawResourceFd(id) }.getOrElse { t ->
            WeLogger.d("ResourcesWrapper", "openRawResourceFd crashed: ${t.localizedMessage}")
            throw t // AssetFileDescriptor allocations cannot be safety-mocked cleanly via blank streams
        }
    }

    // ==========================================
    // 5. Identification & Resolution Metadata
    // ==========================================

    @SuppressLint("DiscouragedApi")
    override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int {
        return safeLog("getIdentifier", 0) { base.getIdentifier(name, defType, defPackage) }
    }

    override fun getResourceName(resid: Int): String {
        return safeLog("getResourceName", "null") { base.getResourceName(resid) }
    }

    override fun getResourcePackageName(resid: Int): String {
        return safeLog("getResourcePackageName", "null") { base.getResourcePackageName(resid) }
    }

    override fun getResourceTypeName(resid: Int): String {
        return safeLog("getResourceTypeName", "null") { base.getResourceTypeName(resid) }
    }

    override fun getResourceEntryName(resid: Int): String {
        return safeLog("getResourceEntryName", "null") { base.getResourceEntryName(resid) }
    }

    override fun getValue(id: Int, outValue: TypedValue?, resolveRefs: Boolean) {
        runCatching { base.getValue(id, outValue, resolveRefs) }.onFailure { t ->
            WeLogger.d("ResourcesWrapper", "getValue(id) failed cleanly: ${t.localizedMessage}")
        }
    }

    @SuppressLint("DiscouragedApi")
    override fun getValue(name: String?, outValue: TypedValue?, resolveRefs: Boolean) {
        runCatching { base.getValue(name, outValue, resolveRefs) }.onFailure { t ->
            WeLogger.d("ResourcesWrapper", "getValue(name) failed cleanly: ${t.localizedMessage}")
        }
    }

    override fun getValueForDensity(id: Int, density: Int, outValue: TypedValue?, resolveRefs: Boolean) {
        runCatching { base.getValueForDensity(id, density, outValue, resolveRefs) }.onFailure { t ->
            WeLogger.d("ResourcesWrapper", "getValueForDensity failed cleanly: ${t.localizedMessage}")
        }
    }

    // ==========================================
    // 6. Structural XML & Internal Attributes (Fail-Safe Forwarding)
    // ==========================================

    override fun obtainTypedArray(id: Int): TypedArray {
        return runCatching { base.obtainTypedArray(id) }.getOrElse { t ->
            WeLogger.d("ResourcesWrapper", "obtainTypedArray caught structural error: ${t.localizedMessage}")
            throw t
        }
    }

    override fun obtainAttributes(set: AttributeSet?, attrs: IntArray?): TypedArray {
        return base.obtainAttributes(set, attrs)
    }

    override fun getLayout(id: Int): XmlResourceParser {
        return runCatching { base.getLayout(id) }.getOrElse { t ->
            WeLogger.d("ResourcesWrapper", "getLayout framework fallback failure: ${t.localizedMessage}")
            throw t
        }
    }

    override fun getAnimation(id: Int): XmlResourceParser {
        return runCatching { base.getAnimation(id) }.getOrElse { t ->
            WeLogger.d("ResourcesWrapper", "getAnimation framework fallback failure: ${t.localizedMessage}")
            throw t
        }
    }

    override fun getXml(id: Int): XmlResourceParser {
        return runCatching { base.getXml(id) }.getOrElse { t ->
            WeLogger.d("ResourcesWrapper", "getXml framework fallback failure: ${t.localizedMessage}")
            throw t
        }
    }
}
