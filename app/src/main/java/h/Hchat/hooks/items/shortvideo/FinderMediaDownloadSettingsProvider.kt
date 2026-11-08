package h.Hchat.hooks.items.shortvideo

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class FinderMediaDownloadSettingsProvider : SimpleFeatureSettingsProvider(
    FinderMediaDownloadFeature.ID,
    "视频号媒体下载",
    "在视频号分享菜单增加复制链接和下载入口",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
