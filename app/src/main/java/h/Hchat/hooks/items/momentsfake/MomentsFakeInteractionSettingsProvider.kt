package h.Hchat.hooks.items.momentsfake

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class MomentsFakeLikeSettingsProvider : SimpleFeatureSettingsProvider(
    ID,
    "朋友圈伪集赞",
    "长按朋友圈选择或凭空生成本地点赞",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
) {
    companion object {
        const val ID = "moments_fake_like"
    }
}

class MomentsFakeCommentSettingsProvider : SimpleFeatureSettingsProvider(
    ID,
    "朋友圈伪评论",
    "长按朋友圈添加带时间和顺序的本地评论",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
) {
    companion object {
        const val ID = "moments_fake_comment"
    }
}

class MomentsFakeForwardSettingsProvider : SimpleFeatureSettingsProvider(
    ID,
    "朋友圈伪转发",
    "复制朋友圈到本地并自定义文字和发布时间",
    FeatureSettingsProvider.CATEGORY_PRACTICAL
) {
    companion object { const val ID = "moments_fake_forward" }
}
