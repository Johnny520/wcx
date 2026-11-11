package h.Hchat.hooks.items.messageforward

import android.app.Activity
import h.Hchat.hooks.api.sns.PreparedSnsForward
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.items.selectedmessages.SelectedMessageSnapshot
import h.Hchat.utils.KavaReflector
import java.io.File

internal class MessageForwardSystemShare(
    private val context: FeatureContext
) {
    fun share(activity: Activity, snapshot: SelectedMessageSnapshot): String? {
        val type = snapshot.type and 0xffff
        if (type == 1) {
            val text = snapshot.retransmit?.content.orEmpty().ifBlank { snapshot.content }
            return shareText(activity, text, "消息内容为空")
        }

        val file = shareFile(snapshot) ?: return "该消息暂不支持系统分享"
        if (!file.isFile) return "分享文件不存在"
        return shareFile(activity, file, shareMimeType(type, file))
    }

    fun shareFavorite(activity: Activity, type: Int, text: String, path: String?): String? {
        if (type == 1) return shareText(activity, text, "收藏内容为空")
        if (type != 2 && type != 4) return "该收藏类型暂不支持系统分享"
        val file = path?.takeIf { it.isNotBlank() }?.let(::File)
            ?: return "收藏文件不存在"
        if (!file.isFile) return "收藏文件不存在"
        val mimeType = if (type == 2) "image/*" else "video/*"
        return shareFile(activity, file, mimeType)
    }

    fun shareSns(activity: Activity, prepared: PreparedSnsForward): String? {
        if (prepared.video.isNotBlank()) {
            val file = File(prepared.video)
            if (!file.isFile) return "朋友圈视频文件不存在"
            return shareFiles(activity, listOf(file), "video/*", prepared.text)
        }
        if (prepared.images.isNotEmpty()) {
            val files = prepared.images.map(::File)
            if (files.any { !it.isFile }) return "部分朋友圈图片文件不存在"
            return shareFiles(activity, files, "image/*", prepared.text)
        }
        return shareText(activity, prepared.text, "朋友圈内容为空")
    }

    private fun shareText(activity: Activity, text: String, emptyMessage: String): String? {
        if (text.isBlank()) return emptyMessage
        return launch(
            activity,
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
        )
    }

    private fun shareFile(activity: Activity, file: File, mimeType: String): String? {
        return shareFiles(activity, listOf(file), mimeType, "")
    }

    private fun shareFiles(
        activity: Activity,
        files: List<File>,
        mimeType: String,
        text: String
    ): String? {
        if (files.isEmpty()) return "分享文件不存在"
        val uris = files.map { contentUri(activity, it) ?: return "无法生成分享文件地址" }
        val multiple = uris.size > 1
        val intent = Intent(if (multiple) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
            type = mimeType
            if (multiple) {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            } else {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
            if (text.isNotBlank()) putExtra(Intent.EXTRA_TEXT, text)
            clipData = ClipData.newUri(activity.contentResolver, files.first().name, uris.first()).apply {
                for (index in 1 until uris.size) addItem(ClipData.Item(uris[index]))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return launch(activity, intent)
    }

    private fun launch(activity: Activity, intent: Intent): String? {
        return runCatching {
            val chooser = Intent.createChooser(intent, "分享消息").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(chooser)
            null
        }.getOrElse { "没有可用的分享应用" }
    }

    private fun shareFile(snapshot: SelectedMessageSnapshot): File? {
        val type = snapshot.type and 0xffff
        val path = when (type) {
            3, 43, 62 -> snapshot.retransmit?.fileName.orEmpty()
            34 -> snapshot.voicePath
            47 -> snapshot.retransmit?.fileName.orEmpty()
            else -> ""
        }
        return path.takeIf { it.isNotBlank() }?.let(::File)
    }

    private fun shareMimeType(type: Int, file: File): String {
        return when (type) {
            3, 47 -> "image/*"
            34 -> "audio/*"
            43, 62 -> "video/*"
            else -> {
                val extension = file.extension.lowercase()
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                    ?: "application/octet-stream"
            }
        }
    }

    private fun contentUri(activity: Activity, file: File): Uri? {
        val providerClass = KavaReflector.loadClass(
            "android.support.v4.content.FileProvider",
            context.hostClassLoader()
        ) ?: return null
        val method = KavaReflector.findMethod(
            providerClass,
            "getUriForFile",
            Context::class.java,
            String::class.java,
            File::class.java
        ) ?: return null
        return KavaReflector.invoke(
            method,
            null,
            activity,
            activity.packageName + ".external.fileprovider",
            file
        ) as? Uri
    }
}
