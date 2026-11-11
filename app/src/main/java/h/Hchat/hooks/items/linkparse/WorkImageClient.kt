package h.Hchat.hooks.items.linkparse

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal enum class WorkImageFormat(val extension: String) {
    JPEG("jpg"),
    WEBP("webp")
}

internal data class DownloadedWorkImage(
    val file: File,
    val bytes: Long,
    val format: WorkImageFormat
)

internal object WorkImageFormatSniffer {
    fun detect(head: ByteArray): WorkImageFormat? {
        if (head.size >= 3 && head[0] == 0xff.toByte() && head[1] == 0xd8.toByte() && head[2] == 0xff.toByte()) {
            return WorkImageFormat.JPEG
        }
        if (head.size >= 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(head, 8, 4, Charsets.US_ASCII) == "WEBP") {
            return WorkImageFormat.WEBP
        }
        return null
    }
}

/** Anonymous, bounded image downloads. Original JPEG/WebP bytes are kept unchanged. */
internal class WorkImageClient {
    private val client = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    fun download(
        source: WorkImageSource,
        platform: WorkLinkMatcher.Platform,
        targetBase: File
    ): DownloadedWorkImage? {
        for (candidate in source.candidates) {
            downloadCandidate(candidate, platform, targetBase)?.let { return it }
        }
        return null
    }

    private fun downloadCandidate(
        candidate: String,
        platform: WorkLinkMatcher.Platform,
        targetBase: File
    ): DownloadedWorkImage? {
        val part = File(targetBase.absolutePath + ".part")
        if (part.exists() || finalFiles(targetBase).any(File::exists)) return null
        var url = candidate.toHttpUrlOrNull() ?: return null
        var result: DownloadedWorkImage? = null
        try {
            repeat(MAX_REDIRECTS + 1) { hop ->
                if (!allowed(url, platform)) return null
                request(url).use { response ->
                    if (response.isRedirect) {
                        if (hop == MAX_REDIRECTS) return null
                        url = response.header("Location")?.let { url.resolve(it) } ?: return null
                        return@use
                    }
                    if (!response.isSuccessful) return null
                    val body = response.body ?: return null
                    if (body.contentLength() > MAX_IMAGE_BYTES) return null
                    val buffer = ByteArray(16 * 1024)
                    var size = 0L
                    val signature = ByteArrayOutputStream(12)
                    body.byteStream().use { input ->
                        FileOutputStream(part).use { output ->
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw IOException("interrupted")
                                val read = input.read(buffer)
                                if (read < 0) break
                                size += read
                                if (size > MAX_IMAGE_BYTES) throw IOException("image too large")
                                if (signature.size() < 12) signature.write(buffer, 0, minOf(read, 12 - signature.size()))
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    if (body.contentLength() >= 0 && size != body.contentLength()) return null
                    val format = WorkImageFormatSniffer.detect(signature.toByteArray()) ?: return null
                    val target = File(targetBase.absolutePath + "." + format.extension)
                    if (!part.renameTo(target)) return null
                    result = DownloadedWorkImage(target, size, format)
                    return@use
                }
                if (result != null) return result
            }
        } catch (_: Exception) {
            return null
        } finally {
            if (part.exists()) part.delete()
        }
        return result
    }

    private fun allowed(url: HttpUrl, platform: WorkLinkMatcher.Platform): Boolean =
        when (platform) {
            WorkLinkMatcher.Platform.DOUYIN -> WorkImageExtractor.allowedDouyinImage(url.toString())
            WorkLinkMatcher.Platform.XIAOHONGSHU -> WorkImageExtractor.allowedXhsImage(url.toString())
        }

    private fun request(url: HttpUrl): Response = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/avif,image/webp,image/apng,image/jpeg,*/*")
            .header("Accept-Encoding", "identity")
            .get().build()
    ).execute()

    private fun finalFiles(base: File): List<File> = listOf(
        File(base.absolutePath + ".jpg"),
        File(base.absolutePath + ".webp")
    )

    companion object {
        const val MAX_IMAGE_BYTES = 15L * 1024 * 1024
        private const val MAX_REDIRECTS = 4
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
    }
}
