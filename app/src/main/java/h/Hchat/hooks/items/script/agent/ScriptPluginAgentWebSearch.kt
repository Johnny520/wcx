package h.Hchat.hooks.items.script.agent

import android.os.Build
import android.text.Html
import android.text.Spanned
import android.text.style.URLSpan
import android.util.Base64
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Agent 的联网工具层。
 *
 * OpenAI/Codex 的 web_search 工具运行在服务端，不能直接嵌入 Android APK。
 * 这里保持相同的工具调用回路：模型返回 search，客户端取得公开资料，再把带来源的结果
 * 作为下一轮上下文交回模型。对 GitHub 和用户明确给出的 URL 走直读，普通关键词才走网页搜索。
 */
object ScriptPluginAgentWebSearch {
    const val ERROR_PREFIX = "[联网搜索错误]"

    private const val MAX_QUERY_CHARS = 1_000
    private const val MAX_PAGE_BYTES = 512 * 1024
    private const val MAX_SEARCH_HTML_BYTES = 384 * 1024
    private const val MAX_GITHUB_TREE_ITEMS = 800
    private const val MAX_URL_CHARS = 8_192
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36 Hchat-Plugin-Agent/1.1"
    private const val DOH_HOST = "cloudflare-dns.com"
    private const val DOH_URL = "https://cloudflare-dns.com/dns-query"
    private const val DNS_CACHE_TTL_MS = 5 * 60 * 1_000L

    private val resolvedAddressCache = ConcurrentHashMap<String, CachedAddresses>()
    private val dohBootstrapDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname.trimEnd('.').equals(DOH_HOST, ignoreCase = true)) {
                return listOf(
                    InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1)),
                    InetAddress.getByAddress(byteArrayOf(1, 0, 0, 1))
                )
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private val dohClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .dns(dohBootstrapDns)
            .build()
    }

    private val publicDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            var systemError: UnknownHostException? = null
            val addresses = try {
                Dns.SYSTEM.lookup(hostname)
            } catch (error: UnknownHostException) {
                systemError = error
                emptyList()
            }
            val publicAddresses = addresses.filterNot(::isPrivateAddress)
            if (publicAddresses.isNotEmpty()) return publicAddresses

            val literalHost = isIpLiteral(hostname)
            val syntheticAddresses = if (literalHost) {
                emptyList()
            } else {
                addresses.filter(::isSyntheticPublicProxyAddress)
            }
            if (!literalHost && (addresses.isEmpty() || syntheticAddresses.isNotEmpty())) {
                val resolved = resolvePublicAddresses(hostname)
                if (resolved.isNotEmpty()) return resolved
            }

            if (syntheticAddresses.isNotEmpty()) return syntheticAddresses
            val blocked = addresses.firstOrNull(::isPrivateAddress)
            if (blocked != null) throw UnknownHostException("拒绝访问内网地址: ${blocked.hostAddress}")
            throw systemError ?: UnknownHostException("域名解析失败: $hostname")
        }
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .dns(publicDns)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun isError(result: String): Boolean = result.trimStart().startsWith(ERROR_PREFIX)

    fun hasSearchFallback(result: String): Boolean =
        isError(result) && result.contains("[网页搜索结果]")

    fun readPage(url: String, cancellation: ScriptPluginAgentCancellation? = null): String {
        val clean = url.trim().take(MAX_URL_CHARS)
        if (clean.isBlank()) return errorResult("模型没有提供网页地址")
        cancellation?.throwIfCancelled()
        val directUrl = extractUrl(clean) ?: clean.takeIf { it.toHttpUrlOrNullSafe() != null }
            ?: return errorResult("URL 无效: $clean")
        return readUrl(directUrl, cancellation)
    }

    fun search(query: String, cancellation: ScriptPluginAgentCancellation? = null): String {
        val clean = query.trim().take(MAX_QUERY_CHARS)
        if (clean.isBlank()) return errorResult("模型没有提供搜索关键词")
        cancellation?.throwIfCancelled()

        val directUrl = extractUrl(clean)
        if (directUrl != null) return readUrl(directUrl, cancellation)

        val githubUrl = extractGithubUrl(clean)
        if (githubUrl != null) return readUrl(githubUrl, cancellation)

        githubRepositoryFromQuery(clean)?.let { repository ->
            return readGithubRepository(repository, "https://github.com/${repository.owner}/${repository.name}", cancellation)
        }

        if (clean.contains("github", ignoreCase = true)) {
            return searchGithubRepositories(clean, cancellation)
        }
        return searchWeb(clean, cancellation)
    }

    private fun readUrl(url: String, cancellation: ScriptPluginAgentCancellation?): String {
        val parsed = url.toHttpUrlOrNullSafe() ?: return errorResult("URL 无效: $url")
        val host = parsed.host.lowercase(Locale.US)
        if (host == "github.com" || host == "www.github.com") {
            val parts = parsed.pathSegments.filter { it.isNotBlank() }
            if (parts.size >= 2) {
                val repository = GitHubRepository(parts[0], parts[1].removeSuffix(".git"))
                when (parts.getOrNull(2)?.lowercase(Locale.US)) {
                    "blob" -> {
                        val branch = parts.getOrNull(3).orEmpty()
                        val path = parts.drop(4)
                        if (branch.isNotBlank() && path.isNotEmpty()) {
                            return readGithubFile(repository, branch, path, parsed.toString(), cancellation)
                        }
                    }
                    "tree" -> {
                        val branch = parts.getOrNull(3).orEmpty()
                        if (branch.isNotBlank()) {
                            return readGithubDirectory(repository, branch, parts.drop(4), parsed.toString(), cancellation)
                        }
                    }
                    null -> return readGithubRepository(repository, parsed.toString(), cancellation)
                }
            }
        }
        if (host == "raw.githubusercontent.com") {
            return readGenericPage(parsed.toString(), cancellation)
        }
        return readGenericPage(parsed.toString(), cancellation)
    }

    private fun readGithubRepository(
        repository: GitHubRepository,
        sourceUrl: String,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val metadataUrl = githubApi("repos", repository.owner, repository.name)
        val metadata = fetchText(
            metadataUrl.toString(),
            cancellation,
            headers = mapOf("Accept" to "application/vnd.github+json"),
            maxBytes = 96 * 1024
        )
        if (metadata.error != null) {
            return readGithubRepositoryFallback(repository, sourceUrl, metadata.error, cancellation)
        }

        val root = runCatching { JSONObject(metadata.body) }.getOrNull()
            ?: return errorResult("GitHub 仓库返回的 JSON 无法解析")
        val branch = root.optString("default_branch", "main").ifBlank { "main" }
        val readme = fetchText(
            githubApi("repos", repository.owner, repository.name, "readme").toString(),
            cancellation,
            headers = mapOf(
                "Accept" to "application/vnd.github.raw",
                "X-GitHub-Api-Version" to "2022-11-28"
            ),
            maxBytes = 160 * 1024
        ).let { result ->
            if (result.error == null) result else rawGithubReadme(repository, branch, cancellation) ?: result
        }
        val tree = fetchText(
            githubApi("repos", repository.owner, repository.name, "git", "trees", branch)
                .newBuilder()
                .addQueryParameter("recursive", "1")
                .build()
                .toString(),
            cancellation,
            headers = mapOf("Accept" to "application/vnd.github+json"),
            maxBytes = 384 * 1024
        )

        return buildString {
            appendLine("[GitHub 仓库]")
            appendLine("仓库: ${root.optString("full_name", "${repository.owner}/${repository.name}")}")
            appendLine("地址: ${root.optString("html_url", sourceUrl)}")
            root.optString("description", "").trim().takeIf { it.isNotBlank() }?.let {
                appendLine("简介: ${it.take(1_000)}")
            }
            appendLine("默认分支: $branch")
            root.optString("language", "").takeIf { it.isNotBlank() }?.let { appendLine("主要语言: $it") }
            appendLine("Stars: ${root.optInt("stargazers_count", 0)}，Forks: ${root.optInt("forks_count", 0)}")
            appendLine("来源: $sourceUrl")
            if (readme.error == null && readme.body.isNotBlank()) {
                appendLine()
                appendLine("[README]")
                appendLine(normalizePlainText(readme.body))
            }
            if (tree.error == null && tree.body.isNotBlank()) {
                appendLine()
                appendLine("[文件树]")
                appendLine(formatGithubTree(tree.body))
            }
            if (readme.error != null) appendLine("README: ${readme.error}")
            if (tree.error != null) appendLine("文件树: ${tree.error}")
        }
    }

    private fun readGithubRepositoryFallback(
        repository: GitHubRepository,
        sourceUrl: String,
        apiError: String,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val branches = listOf("main", "master")
        val readme = branches.firstNotNullOfOrNull { branch ->
            val rawUrl = githubRawUrl(repository, branch, listOf("README.md"))
            fetchText(rawUrl, cancellation, maxBytes = 160 * 1024)
                .takeIf { it.error == null && it.body.isNotBlank() }
                ?.let { branch to it.body }
        }
        if (readme != null) {
            return buildString {
                appendLine("[GitHub 仓库]")
                appendLine("仓库: ${repository.owner}/${repository.name}")
                appendLine("地址: $sourceUrl")
                appendLine("默认分支候选: ${readme.first}")
                appendLine("GitHub API: $apiError")
                appendLine()
                appendLine("[README]")
                append(normalizePlainText(readme.second))
            }
        }
        val page = readGenericPage(sourceUrl, cancellation)
        return if (isError(page)) {
            errorResult("读取 GitHub 仓库失败: $apiError；${page.removePrefix(ERROR_PREFIX).trim()}")
        } else {
            "$page\n\nGitHub API: $apiError"
        }
    }

    private fun rawGithubReadme(
        repository: GitHubRepository,
        branch: String,
        cancellation: ScriptPluginAgentCancellation?
    ): FetchResult? {
        val url = githubRawUrl(repository, branch, listOf("README.md"))
        return fetchText(url, cancellation, maxBytes = 160 * 1024)
            .takeIf { it.error == null && it.body.isNotBlank() }
    }

    private fun readGithubFile(
        repository: GitHubRepository,
        branch: String,
        path: List<String>,
        sourceUrl: String,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val api = githubApi("repos", repository.owner, repository.name, "contents")
            .newBuilder()
            .apply { path.forEach { addPathSegment(it) } }
            .addQueryParameter("ref", branch)
            .build()
            .toString()
        val raw = fetchText(
            githubRawUrl(repository, branch, path),
            cancellation,
            maxBytes = MAX_PAGE_BYTES
        )
        if (raw.error == null && raw.body.isNotBlank()) {
            return formatGithubFile(repository, branch, path, sourceUrl, raw.body, raw.truncated)
        }
        val result = fetchText(
            api,
            cancellation,
            headers = mapOf("Accept" to "application/vnd.github.raw"),
            maxBytes = MAX_PAGE_BYTES
        )
        if (result.error != null) {
            return errorResult("读取 GitHub 文件失败: ${raw.error ?: "raw 文件为空"}；${result.error}")
        }
        val content = decodeGithubContentIfNeeded(result.body)
        return formatGithubFile(repository, branch, path, sourceUrl, content, result.truncated)
    }

    private fun formatGithubFile(
        repository: GitHubRepository,
        branch: String,
        path: List<String>,
        sourceUrl: String,
        content: String,
        truncated: Boolean
    ): String {
        return buildString {
            appendLine("[GitHub 文件]")
            appendLine("路径: ${repository.owner}/${repository.name}/${path.joinToString("/")}")
            appendLine("分支: $branch")
            appendLine("来源: $sourceUrl")
            appendLine()
            append(content)
            if (truncated) appendLine("\n[文件内容已截断]")
        }
    }

    private fun readGithubDirectory(
        repository: GitHubRepository,
        branch: String,
        path: List<String>,
        sourceUrl: String,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val api = githubApi("repos", repository.owner, repository.name, "contents")
            .newBuilder()
            .apply { path.forEach { addPathSegment(it) } }
            .addQueryParameter("ref", branch)
            .build()
            .toString()
        val result = fetchText(
            api,
            cancellation,
            headers = mapOf("Accept" to "application/vnd.github+json"),
            maxBytes = 384 * 1024
        )
        if (result.error != null) {
            val page = readGenericPage(sourceUrl, cancellation)
            return if (isError(page)) {
                errorResult("读取 GitHub 目录失败: ${result.error}")
            } else {
                "$page\n\nGitHub 目录 API: ${result.error}"
            }
        }
        val array = runCatching { JSONArray(result.body) }.getOrNull()
            ?: return errorResult("GitHub 目录返回的 JSON 无法解析")
        return buildString {
            appendLine("[GitHub 目录]")
            appendLine("路径: ${repository.owner}/${repository.name}/${path.joinToString("/")}")
            appendLine("分支: $branch")
            appendLine("来源: $sourceUrl")
            for (index in 0 until minOf(array.length(), MAX_GITHUB_TREE_ITEMS)) {
                val item = array.optJSONObject(index) ?: continue
                append(item.optString("type", "file"))
                append(" ")
                appendLine(item.optString("path", ""))
            }
        }
    }

    private fun searchGithubRepositories(
        query: String,
        cancellation: ScriptPluginAgentCancellation?
    ): String {
        val cleanQuery = query.replace(Regex("(?i)github(?:\\.com)?"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { query }
        val url = githubApi("search", "repositories").newBuilder()
            .addQueryParameter("q", cleanQuery)
            .addQueryParameter("per_page", "8")
            .build()
            .toString()
        val result = fetchText(
            url,
            cancellation,
            headers = mapOf("Accept" to "application/vnd.github+json"),
            maxBytes = 256 * 1024
        )
        if (result.error != null) {
            val fallback = searchWeb("site:github.com $cleanQuery", cancellation)
            return if (!isError(fallback)) {
                buildString {
                    appendLine("[GitHub 仓库搜索]")
                    appendLine("GitHub API: ${result.error}")
                    append(fallback)
                }
            } else {
                errorResult("GitHub 仓库搜索失败: ${result.error}")
            }
        }
        val root = runCatching { JSONObject(result.body) }.getOrNull()
            ?: return errorResult("GitHub 搜索返回的 JSON 无法解析")
        val items = root.optJSONArray("items")
        if (items == null || items.length() == 0) return errorResult("GitHub 没有找到匹配的公开仓库")
        return buildString {
            appendLine("[GitHub 仓库搜索]")
            appendLine("查询: $cleanQuery")
            for (index in 0 until minOf(items.length(), 8)) {
                val item = items.optJSONObject(index) ?: continue
                appendLine()
                appendLine("${index + 1}. ${item.optString("full_name", "未命名仓库")}")
                appendLine("地址: ${item.optString("html_url", "")}")
                item.optString("description", "").trim().takeIf { it.isNotBlank() }?.let {
                    appendLine("简介: ${it.take(700)}")
                }
                appendLine("语言: ${item.optString("language", "未知")}，Stars: ${item.optInt("stargazers_count", 0)}")
            }
            appendLine()
            appendLine("来源: $url")
        }
    }

    private fun searchWeb(query: String, cancellation: ScriptPluginAgentCancellation?): String {
        val url = "https://html.duckduckgo.com/html/".toHttpUrlOrNullSafe()?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("kl", "wt-wt")
            ?.build()
            ?.toString()
            ?: return errorResult("无法生成网页搜索地址")
        val result = fetchText(
            url,
            cancellation,
            headers = mapOf("Accept" to "text/html,application/xhtml+xml"),
            maxBytes = MAX_SEARCH_HTML_BYTES
        )
        if (result.error != null) return errorResult("网页搜索失败: ${result.error}")
        val parsed = parseSearchResults(result.body)
        if (parsed.isEmpty()) return errorResult("网页搜索没有找到可读取的结果")
        return buildString {
            appendLine("[网页搜索结果]")
            appendLine("查询: $query")
            parsed.take(8).forEachIndexed { index, item ->
                appendLine()
                appendLine("${index + 1}. ${item.title}")
                appendLine("来源: ${item.url}")
                item.snippet.takeIf { it.isNotBlank() }?.let { appendLine("摘要: ${it.take(900)}") }
            }
            appendLine()
            appendLine("搜索来源: $url")
        }
    }

    private fun readGenericPage(url: String, cancellation: ScriptPluginAgentCancellation?): String {
        val result = fetchText(url, cancellation, maxBytes = MAX_PAGE_BYTES)
        if (result.error != null) {
            val directError = result.error
            val fallback = if (directError.contains("SSLHandshakeException") ||
                directError.contains("UnknownHostException") ||
                directError.contains("connection closed", ignoreCase = true)
            ) {
                searchWeb(url, cancellation)
            } else {
                ""
            }
            return if (fallback.isNotBlank() && !isError(fallback)) {
                buildString {
                    appendLine(errorResult("读取页面失败: $directError"))
                    appendLine("已附上搜索候选，不能将其当作网页正文。")
                    appendLine("来源: $url")
                    appendLine()
                    append(fallback)
                }
            } else {
                errorResult("读取页面失败: $directError")
            }
        }
        val contentType = result.contentType.lowercase(Locale.US)
        if (contentType.contains("json") || contentType.contains("xml") || contentType.startsWith("text/plain")) {
            return buildString {
                appendLine("[网页内容]")
                appendLine("来源: ${result.url}")
                appendLine()
                append(normalizePlainText(result.body))
            }
        }
        if (contentType.isNotBlank() && !contentType.contains("html") && !contentType.startsWith("text/")) {
            return errorResult("页面不是可读取的文本内容: $contentType")
        }
        return formatHtmlPage(result.url, result.body)
    }

    private fun formatHtmlPage(url: String, html: String): String {
        val cleanedHtml = html.replace(
            Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"),
            " "
        )
        val spanned = htmlToSpanned(cleanedHtml)
        val title = Regex("(?is)<title[^>]*>(.*?)</title>")
            .find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.let { normalizePlainText(htmlToSpanned(it).toString()) }
            .orEmpty()
        val text = normalizePlainText(spanned.toString())
        val links = extractPageLinks(spanned)
        return buildString {
            appendLine("[网页内容]")
            if (title.isNotBlank()) appendLine("标题: ${title.take(400)}")
            appendLine("来源: $url")
            if (links.isNotEmpty()) {
                appendLine("链接:")
                links.take(20).forEach { appendLine("- ${it.label}: ${it.url}") }
            }
            appendLine()
            append(text)
        }
    }

    private fun parseSearchResults(html: String): List<SearchResult> {
        val spanned = htmlToSpanned(html)
        val grouped = LinkedHashMap<String, MutableList<String>>()
        val spans = spanned.getSpans(0, spanned.length, URLSpan::class.java)
            .sortedBy { spanned.getSpanStart(it) }
        spans.forEach { span ->
            val url = unwrapSearchUrl(span.url) ?: return@forEach
            val label = normalizePlainText(
                spanned.subSequence(
                    spanned.getSpanStart(span).coerceAtLeast(0),
                    spanned.getSpanEnd(span).coerceAtMost(spanned.length)
                ).toString()
            )
            if (label.isBlank()) return@forEach
            val labels = grouped.getOrPut(url) { ArrayList() }
            if (labels.none { it.equals(label, ignoreCase = true) }) labels += label
        }
        return grouped.entries.map { (url, labels) ->
            SearchResult(
                title = labels.firstOrNull { !it.startsWith("http", ignoreCase = true) } ?: labels.first(),
                url = url,
                snippet = labels.drop(1).filterNot { it == url }.joinToString(" ")
            )
        }
    }

    private fun extractPageLinks(spanned: Spanned): List<SearchLink> {
        val result = LinkedHashMap<String, String>()
        spanned.getSpans(0, spanned.length, URLSpan::class.java).forEach { span ->
            val url = span.url.toHttpUrlOrNullSafe()?.toString() ?: return@forEach
            if (!url.startsWith("http://") && !url.startsWith("https://")) return@forEach
            val label = normalizePlainText(
                spanned.subSequence(
                    spanned.getSpanStart(span).coerceAtLeast(0),
                    spanned.getSpanEnd(span).coerceAtMost(spanned.length)
                ).toString()
            ).take(180)
            if (label.isNotBlank()) result.putIfAbsent(url, label)
        }
        return result.map { (url, label) -> SearchLink(label, url) }
    }

    private fun fetchText(
        url: String,
        cancellation: ScriptPluginAgentCancellation?,
        headers: Map<String, String> = emptyMap(),
        maxBytes: Int,
        requireText: Boolean = true
    ): FetchResult {
        val parsed = url.toHttpUrlOrNullSafe()
            ?: return FetchResult(error = "URL 无效")
        val hostError = validatePublicHost(parsed)
        if (hostError != null) return FetchResult(error = hostError)
        val request = Request.Builder()
            .url(parsed)
            .header("User-Agent", USER_AGENT)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/json,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5"
            )
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.6")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()
        val call = client.newCall(request)
        cancellation?.bind(call)
        return try {
            call.execute().use { response ->
                cancellation?.throwIfCancelled()
                if (!response.isSuccessful) {
                    val retry = response.header("Retry-After")?.let { ", Retry-After=$it" }.orEmpty()
                    val rateLimit = response.header("X-RateLimit-Remaining")?.let { remaining ->
                        val reset = response.header("X-RateLimit-Reset")?.let { ", reset=$it" }.orEmpty()
                        ", rateLimitRemaining=$remaining$reset"
                    }.orEmpty()
                    return@use FetchResult(
                        status = response.code,
                        url = response.request.url.toString(),
                        error = "HTTP ${response.code}${retry}${rateLimit}"
                    )
                }
                val contentType = response.header("Content-Type").orEmpty()
                if (requireText && contentType.isNotBlank() && !isTextContent(contentType)) {
                    return@use FetchResult(
                        status = response.code,
                        url = response.request.url.toString(),
                        contentType = contentType,
                        error = "响应类型不可读取: $contentType"
                    )
                }
                val body = response.body ?: return@use FetchResult(
                    status = response.code,
                    url = response.request.url.toString(),
                    contentType = contentType,
                    error = "响应为空"
                )
                val limited = readLimited(body, maxBytes)
                FetchResult(
                    status = response.code,
                    url = response.request.url.toString(),
                    contentType = contentType,
                    body = limited.text,
                    truncated = limited.truncated
                )
            }
        } catch (error: Throwable) {
            if (cancellation?.isCancellation(error) == true) {
                throw java.util.concurrent.CancellationException("Agent 已中断")
            }
            FetchResult(error = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(240)}")
        } finally {
            cancellation?.unbind(call)
        }
    }

    private fun readLimited(body: ResponseBody, maxBytes: Int): LimitedText {
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        var total = 0
        var truncated = false
        body.byteStream().use { input ->
            val buffer = ByteArray(8 * 1024)
            while (total < maxBytes) {
                val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                if (read <= 0) break
                output.write(buffer, 0, read)
                total += read
            }
            if (total >= maxBytes && input.read() != -1) truncated = true
        }
        val charset = body.contentType()?.charset(StandardCharsets.UTF_8) ?: StandardCharsets.UTF_8
        return LimitedText(output.toByteArray().toString(charset), truncated)
    }

    private fun resolvePublicAddresses(hostname: String): List<InetAddress> {
        val host = hostname.trimEnd('.').lowercase(Locale.US)
        val now = System.currentTimeMillis()
        resolvedAddressCache[host]?.takeIf { it.expiresAt > now }?.let { return it.addresses }

        val resolved = runCatching {
            val url = DOH_URL.toHttpUrlOrNullSafe()!!.newBuilder()
                .addQueryParameter("name", host)
                .addQueryParameter("type", "A")
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/dns-json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            dohClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()
                val root = JSONObject(body)
                if (root.optInt("Status", -1) != 0) return@use emptyList()
                val answers = root.optJSONArray("Answer") ?: return@use emptyList()
                buildList {
                    for (index in 0 until answers.length()) {
                        val answer = answers.optJSONObject(index) ?: continue
                        if (answer.optInt("type", 0) != 1) continue
                        val value = answer.optString("data", "").trim()
                        if (!IPV4_LITERAL.matches(value)) continue
                        val address = parseIpv4Address(value) ?: continue
                        if (!isPrivateAddress(address)) add(address)
                    }
                }.distinctBy { it.hostAddress }
            }
        }.getOrDefault(emptyList())

        if (resolved.isNotEmpty()) {
            resolvedAddressCache[host] = CachedAddresses(resolved, now + DNS_CACHE_TTL_MS)
        }
        return resolved
    }

    private fun formatGithubTree(text: String): String {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return "文件树 JSON 无法解析"
        val tree = root.optJSONArray("tree") ?: return "文件树为空"
        val result = StringBuilder()
        for (index in 0 until minOf(tree.length(), MAX_GITHUB_TREE_ITEMS)) {
            val item = tree.optJSONObject(index) ?: continue
            result.append(item.optString("type", "blob"))
                .append(" ")
                .appendLine(item.optString("path", ""))
        }
        if (tree.length() > MAX_GITHUB_TREE_ITEMS || root.optBoolean("truncated", false)) {
            result.appendLine("... 文件树过长，以上为前 $MAX_GITHUB_TREE_ITEMS 项")
        }
        return result.toString().trimEnd()
    }

    private fun decodeGithubContentIfNeeded(body: String): String {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return body
        val encoded = json.optString("content", "").replace(Regex("\\s+"), "")
        if (encoded.isBlank()) return body
        return runCatching { String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8) }
            .getOrDefault(body)
    }

    private fun githubApi(vararg segments: String): HttpUrl {
        return "https://api.github.com".toHttpUrlOrNullSafe()!!.newBuilder()
            .apply { segments.forEach { addPathSegment(it) } }
            .build()
    }

    private fun githubRawUrl(
        repository: GitHubRepository,
        branch: String,
        path: List<String>
    ): String {
        return "https://raw.githubusercontent.com".toHttpUrlOrNullSafe()!!.newBuilder()
            .addPathSegment(repository.owner)
            .addPathSegment(repository.name)
            .addPathSegment(branch)
            .apply { path.forEach { addPathSegment(it) } }
            .build()
            .toString()
    }

    private fun extractUrl(query: String): String? {
        return Regex("(?i)https?://[^\\s<>\"']+")
            .find(query)
            ?.value
            ?.trimEnd('.', ',', ';', ':', '，', '。', '；', '：', ')', '）', ']', '】')
            ?.takeIf { it.toHttpUrlOrNullSafe() != null }
    }

    private fun extractGithubUrl(query: String): String? {
        val match = Regex("(?i)(?:www\\.)?github\\.com/[^\\s<>\"']+").find(query) ?: return null
        val value = match.value.trimEnd('.', ',', ';', ':', '，', '。', '；', '：', ')', '）', ']', '】')
        return "https://$value".takeIf { it.toHttpUrlOrNullSafe() != null }
    }

    private fun githubRepositoryFromQuery(query: String): GitHubRepository? {
        val match = Regex("(?i)(?:github\\.com/)([^/\\s?#]+)/([^/\\s?#]+)").find(query)
            ?: Regex("^\\s*([A-Za-z0-9_.-]{1,64})/([A-Za-z0-9_.-]{1,100})\\s*$").matchEntire(query)
            ?: if (query.contains("github", ignoreCase = true)) {
                Regex("(?<![A-Za-z0-9_.-])([A-Za-z0-9_.-]{1,64})/([A-Za-z0-9_.-]{1,100})(?![A-Za-z0-9_.-])").find(query)
            } else {
                null
            }
        if (match == null) return null
        val owner = match.groupValues[1].trim()
        val name = match.groupValues[2].trim().trimEnd('.', ',', '，', '。').removeSuffix(".git")
        if (owner.isBlank() || name.isBlank() || owner.equals("v1", true)) return null
        return GitHubRepository(owner, name)
    }

    private fun unwrapSearchUrl(raw: String): String? {
        val absolute = if (raw.startsWith("//")) "https:$raw" else raw
        val parsed = absolute.toHttpUrlOrNullSafe() ?: return null
        if (parsed.host.endsWith("duckduckgo.com", ignoreCase = true)) {
            val target = parsed.queryParameter("uddg") ?: return null
            return target.takeIf { it.toHttpUrlOrNullSafe() != null }
        }
        return parsed.toString()
    }

    private fun htmlToSpanned(html: String): Spanned {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html)
        }
    }

    private fun normalizePlainText(value: String): String {
        return value
            .replace('\u00a0', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    private fun isTextContent(contentType: String): Boolean {
        val type = contentType.lowercase(Locale.US)
        return type.startsWith("text/") || type.contains("json") || type.contains("xml") ||
            type.contains("javascript") || type.contains("markdown") || type.contains("github.raw")
    }

    private fun validatePublicHost(url: HttpUrl): String? {
        val host = url.host.lowercase(Locale.US)
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            return "拒绝访问本地网络地址"
        }
        return null
    }

    private fun isIpLiteral(hostname: String): Boolean {
        val host = hostname.trim().removePrefix("[").removeSuffix("]")
        return host.contains(':') || IPV4_LITERAL.matches(host)
    }

    private fun parseIpv4Address(value: String): InetAddress? {
        val octets = value.split('.').map { it.toIntOrNull() ?: return null }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return null
        return InetAddress.getByAddress(ByteArray(4) { index -> octets[index].toByte() })
    }

    private fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) return true
        val host = address.hostAddress.orEmpty().substringBefore('%')
        val parts = host.split('.')
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            val numbers = parts.map { it.toInt() }
            val first = numbers[0]
            val second = numbers[1]
            return first == 0 || first == 10 || first == 127 ||
                (first == 100 && second in 64..127) ||
                (first == 169 && second == 254) ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168)
        }
        val normalized = host.lowercase(Locale.US)
        return normalized == "::1" || normalized.startsWith("fc") || normalized.startsWith("fd")
    }

    /**
     * 部分 Android 网络环境会把公开域名解析到这个 ULA 网段，再由系统网络层转发到公网。
     * 它不是目标主机的真实内网地址，误拦截会让所有未加入白名单的公开网站都报 UnknownHostException。
     */
    private fun isSyntheticPublicProxyAddress(address: InetAddress): Boolean {
        val normalized = address.hostAddress.orEmpty()
            .substringBefore('%')
            .lowercase(Locale.US)
            .removeSuffix(".")
        return normalized == "fdfe:dcba:9876::" || normalized.startsWith("fdfe:dcba:9876:")
    }

    private fun errorResult(message: String): String = "$ERROR_PREFIX ${message.take(600)}"

    private fun String.toHttpUrlOrNullSafe(): HttpUrl? = runCatching { toHttpUrlOrNull() }
        .getOrNull()
        ?.takeIf { it.scheme == "http" || it.scheme == "https" }

    private data class GitHubRepository(val owner: String, val name: String)

    private data class FetchResult(
        val status: Int = 0,
        val url: String = "",
        val contentType: String = "",
        val body: String = "",
        val truncated: Boolean = false,
        val error: String? = null
    )

    private data class LimitedText(val text: String, val truncated: Boolean)

    private data class CachedAddresses(val addresses: List<InetAddress>, val expiresAt: Long)

    private data class SearchResult(val title: String, val url: String, val snippet: String)

    private data class SearchLink(val label: String, val url: String)

    private val IPV4_LITERAL = Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")
}
