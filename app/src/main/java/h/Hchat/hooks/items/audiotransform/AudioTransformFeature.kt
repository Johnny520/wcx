package h.Hchat.hooks.items.audiotransform

import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext

class AudioTransformFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "音频转换"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(AudioTransformSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
    }

    companion object {
        const val ID = "audio_transform"
    }
}
