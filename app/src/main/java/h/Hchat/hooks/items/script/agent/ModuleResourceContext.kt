package h.Hchat.hooks.items.script.agent

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Resources
import com.Johnny.wcx.BuildConfig

class ModuleResourceContext private constructor(
    base: Context,
    moduleRes: Resources?,
) : ContextWrapper(base) {

    private val wrapped: Resources =
        if (moduleRes == null) base.resources else ModuleFallbackResources(base.resources, moduleRes)

    override fun getResources(): Resources = wrapped

    companion object {
        @Volatile
        private var cached: ModuleResourceContext? = null

        fun of(context: Context): Context {
            if (context.packageName == BuildConfig.APPLICATION_ID) return context
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val base = context.applicationContext ?: context
                val ctx = ModuleResourceContext(base, ModuleResources.get(base))
                cached = ctx
                return ctx
            }
        }
    }
}
