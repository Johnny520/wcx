package h.Hchat.hooks.items.quoteclear

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class QuoteDeleteClearSettingsProvider : SimpleFeatureSettingsProvider(
    QuoteDeleteClearFeature.ID,
    "删除键清引用",
    "输入框为空时按删除键取消引用消息",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
