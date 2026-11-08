package h.Hchat.hooks.items.moments

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class OriginalMomentsUploadSettingsProvider : SimpleFeatureSettingsProvider(
    OriginalMomentsUploadFeature.ID,
    "朋友圈原图上传",
    "发布朋友圈图片和视频时尽量保留原始文件",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
)
