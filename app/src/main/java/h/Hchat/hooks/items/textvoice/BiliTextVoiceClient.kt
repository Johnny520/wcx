package h.Hchat.hooks.items.textvoice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class BiliTextVoiceClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    fun synthesize(text: String, voiceId: String, speechRate: Int, target: File): File {
        val normalizedText = text.trim()
        require(normalizedText.isNotEmpty()) { "文字不能为空" }
        require(normalizedText.length <= MAX_TEXT_LENGTH) { "文字不能超过 $MAX_TEXT_LENGTH 个字符" }

        val nowMillis = System.currentTimeMillis()
        val requestBody = JSONObject().apply {
            put("model_id", "tts_bcut")
            put("platform", "Android")
            put("raw_data", JSONArray().put(normalizedText))
            put("raw_params", JSONObject().apply {
                put("format", "mp3")
                put("logid", "${UUID.randomUUID()}_$nowMillis")
                put("method", 0)
                put("pitch_rate", 0)
                put("sample_rate", 16000)
                put("speech_rate", speechRate.coerceIn(-9, 20))
                put("voice", voiceId)
                put("voice_engine", "bili")
                put("volume", 50)
            })
        }
        val request = Request.Builder()
            .url("$ENDPOINT?aurora_version=2.33.0&montage_version=1.36.1.3&sdk_type=mon&ts=${nowMillis / 1000L}")
            .header("env", "prod")
            .header("APP-KEY", "bilistudio")
            .header("bili-http-engine", "cronet")
            .header("User-Agent", USER_AGENT)
            .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val audioUrl = client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("在线语音请求失败: HTTP ${response.code}")
            val root = runCatching { JSONObject(body) }
                .getOrElse { error("在线语音返回内容无法解析") }
            val code = root.optInt("code", -1)
            if (code != 0) {
                error(root.optString("message").ifBlank { "在线语音服务返回错误: $code" })
            }
            root.optJSONObject("data")
                ?.optJSONObject("result")
                ?.optJSONArray("results")
                ?.optJSONObject(0)
                ?.optString("url")
                .orEmpty()
                .takeIf { it.startsWith("https://", ignoreCase = true) }
                ?: error("在线语音未返回安全的音频地址")
        }
        return download(audioUrl, target)
    }

    fun cancelAll() {
        client.dispatcher.cancelAll()
    }

    private fun download(url: String, target: File): File {
        target.parentFile?.let { dir ->
            if (!dir.isDirectory && !dir.mkdirs()) error("无法创建语音缓存目录")
        }
        val part = File(target.parentFile, target.name + ".part")
        part.delete()
        target.delete()
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("下载语音失败: HTTP ${response.code}")
                val body = response.body ?: error("下载语音失败: 返回内容为空")
                val contentLength = body.contentLength()
                if (contentLength > MAX_AUDIO_BYTES) error("语音文件超过 16 MiB")
                body.byteStream().use { input ->
                    FileOutputStream(part, false).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_AUDIO_BYTES) error("语音文件超过 16 MiB")
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            if (!part.isFile || part.length() <= 0L) error("下载到的语音文件为空")
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            return target
        } catch (throwable: Throwable) {
            part.delete()
            target.delete()
            throw throwable
        }
    }

    private companion object {
        const val ENDPOINT = "https://member.bilibili.com/x/material/rubick-interface/sync-task"
        const val USER_AGENT = "com.bilibili.studio/2740030 (Linux; U; Android 13; zh_CN; 21121210C; Build/TKQ1.220807.001; Cronet/88.0.4324.188)"
        const val MAX_TEXT_LENGTH = 2000
        const val MAX_AUDIO_BYTES = 16L * 1024L * 1024L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
