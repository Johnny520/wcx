package h.Hchat.ui

interface FeatureSettingsProvider {
    fun featureId(): String

    fun title(): String

    fun subtitle(): String

    fun category(): String = CATEGORY_ENHANCE

    companion object {
        const val CATEGORY_PRACTICAL = "practical"
        const val CATEGORY_ENTERTAINMENT = "entertainment"
        const val CATEGORY_ENHANCE = "enhance"
    }
}
