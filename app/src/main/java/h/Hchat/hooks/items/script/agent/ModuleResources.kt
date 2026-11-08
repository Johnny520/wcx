package h.Hchat.hooks.items.script.agent

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.drawable.Drawable
import h.Hchat.BuildConfig
import h.Hchat.R
import h.Hchat.utils.HLog

// 模块资源表兜底：用 R.string.app_name 探测哪张表能直接用编译期 id（反射取表有坑）
object ModuleResources {

    private const val PROBE_NAME = "text_select_handle_left_material"

    private val PROBE_ID = R.string.app_name

    @Volatile
    private var primary: Resources? = null

    @Volatile
    private var tables: List<Resources> = emptyList()

    @Volatile
    private var loaded = false

    fun get(context: Context): Resources? {
        ensure(context)
        return primary
    }

    fun text(context: Context, resId: Int): CharSequence? =
        lookup(context, resId) { res, id -> res.getText(id) }

    fun drawable(context: Context, resId: Int): Drawable? =
        lookup(context, resId) { res, id -> res.getDrawable(id, null) }

    fun name(context: Context, resId: Int): String? =
        lookup(context, resId) { res, id -> res.getResourceName(id) }

    fun entryName(context: Context, resId: Int): String? =
        name(context, resId)?.substringAfterLast('/')

    private inline fun <T> lookup(context: Context, resId: Int, block: (Resources, Int) -> T): T? {
        if (resId == 0) return null
        ensure(context)
        for (table in tables) {
            runCatching { block(table, resId) }.getOrNull()?.let { return it }
            val shifted = shiftedId(table, resId)
            if (shifted != resId) {
                runCatching { block(table, shifted) }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun shiftedId(table: Resources, resId: Int): Int {
        val runtime = runCatching {
            table.getIdentifier("app_name", "string", BuildConfig.APPLICATION_ID)
        }.getOrDefault(0)
        if (runtime == 0) return resId
        val delta = runtime - PROBE_ID
        return if (delta == 0) resId else resId + delta
    }

    private fun ensure(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val list = load(context.applicationContext ?: context)
            tables = list
            primary = list.firstOrNull { idCapable(it) } ?: list.firstOrNull()
            loaded = true
        }
    }

    private fun idCapable(table: Resources): Boolean =
        runCatching { table.getResourceName(PROBE_ID) }.isSuccess

    private fun load(context: Context): List<Resources> {
        val list = ArrayList<Resources>(2)

        runCatching {
            val apk = ProotEnvironment.moduleApkPath()
            if (apk != null) {
                val am = AssetManager::class.java.getDeclaredConstructor().newInstance()
                AssetManager::class.java.getMethod("addAssetPath", String::class.java).invoke(am, apk)
                val host = context.resources
                list += Resources(am, host.displayMetrics, host.configuration)
            }
        }.onFailure { HLog.e("挂载模块资源表(apk)失败", it) }

        runCatching {
            val res = context.createPackageContext(
                BuildConfig.APPLICATION_ID,
                Context.CONTEXT_IGNORE_SECURITY,
            ).resources
            if (res.getIdentifier(PROBE_NAME, "drawable", BuildConfig.APPLICATION_ID) != 0) {
                list += res
            }
        }.onFailure { HLog.e("挂载模块资源表(pkg)失败", it) }

        return list
    }
}
