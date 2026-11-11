package h.Hchat.hooks.items.protobuf

import android.content.Context
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ProtobufPacketFileLogger(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val bucketFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)

    @Synchronized
    fun append(direction: String, uri: String, cgiId: Int, length: Int, json: String) {
        try {
            val dir = logDir() ?: return
            if (!dir.isDirectory && !dir.mkdirs()) return
            val file = File(dir, bucketFileName())
            file.appendText(buildString {
                append("----- ")
                append(timeFormat.format(Date()))
                append(" -----\n")
                append(direction).append('\n')
                append("Uri: ").append(uri).append('\n')
                append("Type: ").append(cgiId).append('\n')
                append("Len: ").append(length).append('\n')
                append("Json: ").append(json).append("\n\n")
            })
        } catch (e: Throwable) {
            h.Hchat.utils.HLog.e("[Hchat:Protobuf] 写入抓包文件失败: ${e.message}", e)
        }
    }

    private fun logDir(): File? {
        val mediaRoot = try {
            appContext.externalMediaDirs?.firstOrNull { it != null }
        } catch (_: Throwable) {
            null
        }
        val root = mediaRoot ?: File("/storage/emulated/0/Android/media/${appContext.packageName}")
        return File(root, "Hchat/抓包日志")
    }

    private fun bucketFileName(): String {
        val calendar = Calendar.getInstance(Locale.US)
        calendar.time = Date()
        val bucketStart = (calendar.timeInMillis / (5 * 60 * 1000L)) * (5 * 60 * 1000L)
        calendar.timeInMillis = bucketStart
        val bucket = synchronized(bucketFormat) {
            bucketFormat.format(calendar.time)
        }
        return "$bucket.log"
    }

}
