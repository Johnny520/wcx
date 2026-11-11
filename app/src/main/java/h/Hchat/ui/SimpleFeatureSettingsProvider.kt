package h.Hchat.ui

open class SimpleFeatureSettingsProvider protected constructor(
    private val featureId: String,
    private val title: String,
    private val subtitle: String,
    private val category: String = FeatureSettingsProvider.CATEGORY_ENHANCE
) : FeatureSettingsProvider {
    final override fun featureId(): String = featureId

    final override fun title(): String = title

    final override fun subtitle(): String = subtitle

    final override fun category(): String = category
}
