package h.Hchat.hooks.items.linkparse

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import h.Hchat.utils.HLog
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal sealed interface WorkResolution {
    data class Video(val candidates: List<String>) : WorkResolution
    data class Gallery(val images: List<WorkImageSource>) : WorkResolution
}

/** Isolated anonymous requests: no shared cookie jar, automatic redirects, or third-party parser. */
internal class WorkVideoClient {
    private val client = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    fun resolve(match: WorkLinkMatcher.Match): WorkResolution? = runCatching {
        val input = match.url.toHttpUrlOrNull() ?: return fail("url")
        val page = getWorkPage(input, match) ?: return fail("work_page")
        when (match.platform) {
            WorkLinkMatcher.Platform.DOUYIN -> {
                val id = page.workId
                if (!id.matches(Regex("[1-9][0-9]{15,19}"))) return fail("douyin_id_invalid")
                val detail = "https://api.amemv.com/aweme/v1/aweme/detail/".toHttpUrlOrNull()!!.newBuilder()
                    .addQueryParameter("aweme_id", id).addQueryParameter("aid", "6383")
                    .addQueryParameter("device_platform", "web").addQueryParameter("version_code", "190500")
                    .addQueryParameter("version_name", "19.5.0").build()
                val body = request(detail).use { response ->
                    if (response.isSuccessful) readText(response) else {
                        HLog.e("[Hchat:LinkParse] resolver douyin_detail_http=${response.code}")
                        null
                    }
                } ?: return fail("douyin_detail_body")
                val videos = WorkVideoExtractor.douyinCandidates(body, id)
                if (videos.isNotEmpty()) WorkResolution.Video(videos)
                else WorkImageExtractor.douyin(body, id)?.let { WorkResolution.Gallery(it) }
                    ?: fail("douyin_detail_shape")
            }
            WorkLinkMatcher.Platform.XIAOHONGSHU -> {
                val id = page.workId
                val videos = WorkVideoExtractor.xiaohongshuCandidates(page.body, id)
                if (videos.isNotEmpty()) WorkResolution.Video(videos)
                else WorkImageExtractor.xiaohongshu(page.body, id)?.let { WorkResolution.Gallery(it) }
            }
        }
    }.onFailure { HLog.e("[Hchat:LinkParse] resolver_exception=${it.javaClass.simpleName}", it) }.getOrNull()

    private fun fail(stage: String): Nothing? {
        HLog.e("[Hchat:LinkParse] resolver_stage=$stage")
        return null
    }

    /** Caller owns [target]; a failed or incomplete download never leaves it behind. */
    fun download(mediaUrl: String, platform: WorkLinkMatcher.Platform, target: File): Boolean {
        val part = File(target.absolutePath + ".part")
        if (target.exists() || part.exists()) return false
        var completed = false
        try {
            var url = mediaUrl.toHttpUrlOrNull() ?: return false
            repeat(MAX_REDIRECTS + 1) { hop ->
                if (!WorkVideoExtractor.allowedMedia(url.toString(), platform)) return false
                request(url).use { response ->
                    if (response.isRedirect) {
                        if (hop == MAX_REDIRECTS) return false
                        url = response.header("Location")?.let { url.resolve(it) } ?: return false
                        return@use
                    }
                    if (!response.isSuccessful) return false
                    val body = response.body ?: return false
                    if (body.contentLength() > MAX_VIDEO_BYTES) return false
                    val buffer = ByteArray(16 * 1024)
                    var size = 0L
                    val signature = ByteArrayOutputStream(12)
                    body.byteStream().use { source ->
                        FileOutputStream(part).use { out ->
                            while (true) {
                                if (Thread.currentThread().isInterrupted) throw IOException("interrupted")
                                val read = source.read(buffer)
                                if (read < 0) break
                                size += read
                                if (size > MAX_VIDEO_BYTES) throw IOException("video too large")
                                if (signature.size() < 12) signature.write(buffer, 0, minOf(read, 12 - signature.size()))
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    val head = signature.toByteArray()
                    if (size < 12 || head.size < 12 || String(head, 4, 4, Charsets.US_ASCII) != "ftyp") return false
                    if (body.contentLength() >= 0 && size != body.contentLength()) return false
                    if (!part.renameTo(target)) return false
                    completed = true
                    return true
                }
            }
        } catch (_: Exception) {
            return false
        } finally {
            if (part.exists()) part.delete()
            if (!completed && target.exists()) target.delete()
        }
        return false
    }

    private fun getWorkPage(start: HttpUrl, initialMatch: WorkLinkMatcher.Match): Page? {
        val platform = initialMatch.platform
        val pageUserAgent = if (platform == WorkLinkMatcher.Platform.DOUYIN && initialMatch.requiresRedirect) {
            DOUYIN_REDIRECT_USER_AGENT
        } else USER_AGENT
        var url = start
        var workId = WorkLinkRedirectPolicy.workId(start, platform)
        val visited = HashSet<String>()
        repeat(MAX_REDIRECTS + 1) { hop ->
            if (!visited.add(url.toString())) return pageFailure("redirect_loop")
            if (!WorkLinkRedirectPolicy.allows(url, platform)) return pageFailure("redirect_host_rejected")
            val hopMatch = WorkLinkMatcher.parse(url.toString())
            if (hopMatch != null && hopMatch.platform != platform) return pageFailure("platform_mismatch")
            val hopId = WorkLinkRedirectPolicy.workId(url, platform)
            if (hopId != null && workId != null && hopId != workId) return pageFailure("work_id_changed")
            if (hopId != null) workId = hopId
            request(url, pageUserAgent).use { response ->
                if (response.isRedirect) {
                    if (hop == MAX_REDIRECTS) return pageFailure("redirect_limit")
                    val location = response.header("Location") ?: return pageFailure("redirect_location_missing")
                    val next = url.resolve(location) ?: return pageFailure("redirect_location_invalid")
                    if (!WorkLinkRedirectPolicy.allows(next, platform)) return pageFailure("redirect_host_rejected")
                    val nextId = WorkLinkRedirectPolicy.workId(next, platform)
                    if (nextId != null && workId != null && nextId != workId) return pageFailure("work_id_changed")
                    if (nextId != null) workId = nextId
                    url = next
                    return@use
                }
                if (!response.isSuccessful) {
                    HLog.e("[Hchat:LinkParse] resolver page_http=${response.code}")
                    return pageFailure("http_status")
                }
                if (workId == null) return pageFailure("work_id_missing")
                val body = readText(response) ?: return pageFailure("body_invalid")
                return Page(url, body, workId)
            }
        }
        return pageFailure("loop_end")
    }

    private fun pageFailure(stage: String): Page? {
        HLog.e("[Hchat:LinkParse] resolver_page_stage=$stage")
        return null
    }

    private fun request(url: HttpUrl, userAgent: String = USER_AGENT): Response = client.newCall(
        Request.Builder().url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/json,*/*")
            .header("Accept-Encoding", "identity")
            .get().build()
    ).execute()

    private fun readText(response: Response): String? {
        if (!response.isSuccessful) return null
        val body = response.body ?: return null
        if (body.contentLength() > MAX_PAGE_BYTES) return null
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        body.byteStream().use { source ->
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (output.size() + read > MAX_PAGE_BYTES) return null
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private data class Page(val url: HttpUrl, val body: String, val workId: String)

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Mobile Safari/537.36"
        private const val DOUYIN_REDIRECT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
        private const val MAX_REDIRECTS = 4
        private const val MAX_PAGE_BYTES = 3 * 1024 * 1024
        private const val MAX_VIDEO_BYTES = 50L * 1024 * 1024
    }
}
