package h.Hchat.hooks.items.shortvideo

import android.content.Context
import h.Hchat.hooks.core.FeatureContext
import org.json.JSONObject
import java.io.File

// TODO(migration stub): WCX 切桥桩。
// 原实现依赖 DexMethodCache / FinderFeedDetailResolver / HchatMediaDownloader / KavaReflector /
// XmlPullParser / okhttp 等重量级依赖，无法整包导入。
// 这里仅保留调用方用到的成员签名，保证调用方可编译；方法体返回空/可空值。
// 调用点：h/Hchat/hooks/items/script/{ScriptPluginRuntime,ScriptPluginFeature,ScriptMessageHook,ScriptWaBridge}.kt
internal object FinderMediaDownloadSupport {
    const val MEDIA_TYPE_IMAGE = 2
    const val MEDIA_TYPE_VIDEO = 4

    data class FinderMedia(
        val type: Int,
        val items: List<JSONObject>
    )

    // 调用点：ScriptPluginFeature.kt -> FinderMediaDownloadSupport.install(context)  (context: FeatureContext)
    fun install(context: FeatureContext): Boolean {
        // TODO(migration stub): 安装原生 Finder 详情解析器
        return false
    }

    // 调用点：ScriptPluginRuntime.kt / ScriptMessageHook.kt -> extractMedia(msgInfoBean.getContent())  (String)
    fun extractMedia(content: String): FinderMedia? {
        // TODO(migration stub): 从消息正文/XML 解析 Finder 媒体
        return null
    }

    // 调用点：ScriptPluginRuntime.kt -> extractMedia(msgInfoBean)  (Any?，实参为 ScriptMessageBean)
    //        ScriptWaBridge.kt      -> extractMedia(finderFeedOrMessage)  (Any?)
    fun extractMedia(bean: Any?): FinderMedia? {
        // TODO(migration stub): 从消息对象解析 Finder 媒体
        return null
    }

    // 调用点：ScriptPluginRuntime.kt -> downloadItem(hostContext, media, index, file.absolutePath)
    //        ScriptWaBridge.kt      -> downloadItem(hostContext, media, mediaIndex, savePath)
    // 注意：第 4 个参数 savePath 在 ScriptWaBridge 处为可空 String?，故此处类型取 String?。
    fun downloadItem(
        context: Context,
        media: FinderMedia,
        mediaIndex: Int,
        savePath: String?
    ): File? {
        // TODO(migration stub): 下载单个 Finder 媒体项
        return null
    }
}
