package h.Hchat.hooks.items.protobuf

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class ProtobufPacketSettingsProvider : SimpleFeatureSettingsProvider(
    ProtobufPacketFeature.ID,
    "Protobuf",
    "抓包和自定义发包",
    FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
)
