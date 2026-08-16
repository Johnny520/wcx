package com.Johnny.wcx.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.constants.PackageNames
import com.Johnny.wcx.utils.android.getSystemService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val releaseTag: String = "",
    val releaseUrl: String = "",
    val changelog: String = "",
    val apkUrl: String = "",
)

/**
 * Release 列表项，用于历史版本展示
 */
data class ReleaseItem(
    val tag: String,
    val name: String,
    val body: String,
    val url: String,
    val publishedAt: String,
    val isPrerelease: Boolean,
)

sealed interface UpdateResult {
    /** Remote versionCode ≤ installed versionCode. */
    data object UpToDate : UpdateResult

    /** A newer version is available. */
    data class UpdateAvailable(val info: UpdateInfo) : UpdateResult

    /** Something went wrong while checking or downloading. */
    data class Error(val cause: Throwable) : UpdateResult
}

// ─── GitHub Release API ────────────────────────────────────────────────────

private const val GITHUB_API_LATEST =
    "https://api.github.com/repos/Johnny520/wcx/releases/latest"
private const val GITHUB_API_RELEASES =
    "https://api.github.com/repos/Johnny520/wcx/releases?per_page=20"
private const val RELEASES_PAGE = "https://github.com/Johnny520/wcx/releases"

// APKs are published per entry-point flavor: app-<flavor>-<abi>-release.apk.
// Stay on the same flavor the installed build was compiled for.
private val FLAVOR = BuildConfig.FLAVOR_SLUG
private val ABI_LIST = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
private const val UNIVERSAL_APK_SUFFIX = "universal-release.apk"

/**
 * 从 GitHub Release 的 asset 列表中选择最适合当前设备的 APK 下载地址
 */
private fun selectApkUrl(assets: List<GitHubAsset>): String {
    val supportedAbis = Build.SUPPORTED_ABIS
    for (abi in supportedAbis) {
        val expected = "app-$FLAVOR-$abi-release.apk"
        assets.firstOrNull { it.name == expected }?.let { return it.browser_download_url }
    }
    assets.firstOrNull { it.name.endsWith(UNIVERSAL_APK_SUFFIX) }?.let { return it.browser_download_url }
    return RELEASES_PAGE
}

@Serializable
private data class GitHubAsset(
    val name: String,
    val browser_download_url: String,
)

@Serializable
private data class GitHubRelease(
    val tag_name: String,
    val name: String,
    val body: String,
    val prerelease: Boolean,
    val html_url: String,
    val assets: List<GitHubAsset>,
    val published_at: String,
)

// ─── AppUpdater ───────────────────────────────────────────────────────────────

/**
 * Self-contained in-app updater for WeKit.
 *
 * Usage:
 * ```
 * when (val result = AppUpdater.checkForUpdate(context)) {
 *     is UpdateResult.UpdateAvailable -> AppUpdater.downloadAndInstall(context, result.info)
 *     is UpdateResult.UpToDate        -> { /* nothing to do */ }
 *     is UpdateResult.Error           -> { /* show error */ }
 * }
 * ```
 */
object AppUpdater {

    private val json = Json { ignoreUnknownKeys = true }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * 从 GitHub API 获取最新 Release 信息，检测是否有新版本
     *
     * 兼容 CI 构建版和正式发行版，自动适配不同的 tag 命名
     */
    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        runCatching {
            val release = fetchLatestRelease()
            val updateInfo = parseUpdateInfo(release)
            val installedCode = BuildConfig.VERSION_CODE
            if (updateInfo.versionCode > installedCode) {
                UpdateResult.UpdateAvailable(updateInfo)
            } else {
                UpdateResult.UpToDate
            }
        }.getOrElse {
            UpdateResult.Error(it)
        }
    }

    /**
     * 获取最近的 Release 列表（含更新说明）
     *
     * @return 按发布时间倒序排列的 Release 列表，最多 20 个
     */
    suspend fun getReleaseHistory(): Result<List<ReleaseItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val releases = fetchAllReleases()
            releases.map { release ->
                ReleaseItem(
                    tag = release.tag_name,
                    name = release.name,
                    body = release.body,
                    url = release.html_url,
                    publishedAt = release.published_at,
                    isPrerelease = release.prerelease,
                )
            }
        }
    }

    /**
     * Enqueues an APK download via [DownloadManager] and, on completion,
     * triggers the system installer.
     *
     * Requires the `REQUEST_INSTALL_PACKAGES` permission and a FileProvider
     * authority of `<packageName>.provider` in your manifest.
     *
     * Must be called from a coroutine; completion is awaited via a
     * [BroadcastReceiver] on [Dispatchers.Main].
     */
    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        val apkUrl = info.apkUrl.ifBlank { selectApkUrl(emptyList()) }
        val fileName = "wcx-${info.versionName}.apk"

        val downloadId = enqueueDownload(context, apkUrl, fileName)
        val apkFile = waitForDownload(context, downloadId)

        install(context, apkFile)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun fetchLatestRelease(): GitHubRelease {
        val request = Request.Builder()
            .url(GITHUB_API_LATEST)
            .header("Accept", "application/vnd.github.v3+json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} fetching latest release")
            }
            val body = response.body.string()
            return json.decodeFromString(body)
        }
    }

    private fun fetchAllReleases(): List<GitHubRelease> {
        val request = Request.Builder()
            .url(GITHUB_API_RELEASES)
            .header("Accept", "application/vnd.github.v3+json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("HTTP ${response.code} fetching releases")
            }
            val body = response.body.string()
            return json.decodeFromString(body)
        }
    }

    private fun parseUpdateInfo(release: GitHubRelease): UpdateInfo {
        val tagName = release.tag_name
        val versionCode = extractVersionCode(tagName)
        val apkUrl = selectApkUrl(release.assets)
        return UpdateInfo(
            versionCode = versionCode,
            versionName = release.name,
            releaseTag = tagName,
            releaseUrl = release.html_url,
            changelog = release.body,
            apkUrl = apkUrl,
        )
    }

    private fun extractVersionCode(tagName: String): Int {
        val vPrefix = tagName.removePrefix("v")
        vPrefix.toIntOrNull()?.let { return it }
        val ciMatch = Regex("""CI-?(\d+)?""", RegexOption.IGNORE_CASE).find(tagName)
        ciMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val hashMatch = Regex("""[0-9a-f]{7}""", RegexOption.IGNORE_CASE).find(tagName)
        hashMatch?.let { return Int.MAX_VALUE - 1000 }
        return Int.MAX_VALUE - 9999
    }

    private fun enqueueDownload(context: Context, url: String, fileName: String): Long {
        val request = DownloadManager.Request(url.toUri()).apply {
            setTitle("WCX 更新")
            setDescription("正在下载更新...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setMimeType("application/vnd.android.package-archive")
        }
        val dm = context.getSystemService<DownloadManager>()
        return dm.enqueue(request)
    }

    /** Suspends until [DownloadManager] broadcasts completion for [downloadId]. */
    private suspend fun waitForDownload(context: Context, downloadId: Long): File =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                        if (id != downloadId) return

                        context.unregisterReceiver(this)

                        val dm = context.getSystemService<DownloadManager>()
                        val query = DownloadManager.Query().setFilterById(downloadId)

                        dm.query(query)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                                if (cursor.getInt(statusCol) == DownloadManager.STATUS_SUCCESSFUL) {

                                    // 核心：动态获取 DownloadManager 实际保存的本地真实路径
                                    val localUriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                                    val localUriStr = cursor.getString(localUriCol)

                                    runCatching {
                                        val realFile = File(android.net.Uri.parse(localUriStr).path!!)
                                        cont.resume(realFile)
                                    }.getOrElse {
                                        cont.resumeWithException(RuntimeException("Failed to resolve download path", it))
                                    }
                                } else {
                                    val reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                                    cont.resumeWithException(RuntimeException("Download failed: reason=${cursor.getInt(reasonCol)}"))
                                }
                            } else {
                                cont.resumeWithException(RuntimeException("Download query returned no results"))
                            }
                        } ?: cont.resumeWithException(RuntimeException("Download query returned null cursor"))
                    }
                }

                val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(receiver, filter)
                }

                cont.invokeOnCancellation {
                    runCatching { context.unregisterReceiver(receiver) }
                    val dm = context.getSystemService<DownloadManager>()
                    dm.remove(downloadId)
                }
            }
        }

    private fun install(context: Context, apk: File) {
        /*
        <provider
            android:name="androidx.core.content.FileProvider"
            android:exported="false"
            android:process=":recovery"
            android:authorities="com.tencent.mm.external.recovery.logprovider"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/di"/>
        </provider>
         */

        val uri =
            FileProvider.getUriForFile(
                context,
                "${PackageNames.WECHAT}.external.recovery.logprovider",
                apk,
            )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        context.startActivity(intent)
    }
}
