package h.Hchat.ui

import java.util.Collections

object UIRegistry {
    private val providers = ArrayList<FeatureSettingsProvider>()

    @JvmStatic
    fun get(): UIRegistry = this

    fun registerProvider(provider: FeatureSettingsProvider?) {
        if (provider == null) return
        unregisterProvider(provider.featureId())
        providers.add(provider)
    }

    fun unregisterProvider(featureId: String?) {
        if (featureId == null) return
        providers.removeIf { provider -> featureId == provider.featureId() }
    }

    fun getAllProviders(): List<FeatureSettingsProvider> {
        return Collections.unmodifiableList(ArrayList(providers))
    }

    fun clear() {
        providers.clear()
    }
}
