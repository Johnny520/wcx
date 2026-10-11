package h.Hchat.hooks.core

/**
 * 所有功能模块的统一接口。
 */
interface Feature {
    fun featureId(): String

    fun name(): String = featureId()

    fun isEnabled(context: FeatureContext): Boolean {
        return context.configStore().getGlobalBoolean(featureId() + "_enabled", true)
    }

    @Throws(Throwable::class)
    fun onInit(context: FeatureContext) {
    }

    @Throws(Throwable::class)
    fun install(context: FeatureContext)

    fun onConfigChanged(context: FeatureContext, key: String?) {
    }

    fun onDestroy(context: FeatureContext) {
    }
}
