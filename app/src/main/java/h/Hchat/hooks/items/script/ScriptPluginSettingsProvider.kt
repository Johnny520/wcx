package h.Hchat.hooks.items.script

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class ScriptPluginSettingsProvider : SimpleFeatureSettingsProvider(
    ScriptPluginFeature.ID,
    "插件总开关",
    "启动时自动加载已启用插件",
    FeatureSettingsProvider.CATEGORY_ENHANCE
)
