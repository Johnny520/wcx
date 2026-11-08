package h.Hchat.crash

import h.Hchat.ui.FeatureSettingsProvider
import h.Hchat.ui.SimpleFeatureSettingsProvider

class CrashReportSettingsProvider : SimpleFeatureSettingsProvider(
    ID,
    "捕获异常日志",
    "记录微信异常并在下次启动时显示日志",
    FeatureSettingsProvider.CATEGORY_ENTERTAINMENT
) {
    companion object {
        const val ID = "crash_report"
    }
}
