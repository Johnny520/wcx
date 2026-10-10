package h.Hchat.utils

import android.content.Context
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.math.BigInteger
import java.util.Locale
import java.util.concurrent.TimeUnit

object HchatMediaDownloader {
    private const val FINDER_VIDEO_DECRYPT_BYTES = 128 * 1024

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @JvmStatic
    fun hchatDir(context: Context, child: String): File {
        val appContext = context.applicationContext ?: context
        val mediaRoot = runCatching {
            appContext.externalMediaDirs?.firstOrNull { it != null }
        }.getOrNull() ?: File("/storage/emulated/0/Android/media/${appContext.packageName}")
        return File(File(mediaRoot, "Hchat"), child).apply { mkdirs() }
    }

    @JvmStatic
    fun downloadImage(context: Context, url: String?, fileName: String? = null): File? {
        if (url.isNullOrBlank()) return null
        val target = File(hchatDir(context, "Image"), cleanFileName(fileName, "image", guessExtension(url, "png")))
        return downloadToFile(url, target)
    }

    @JvmStatic
    fun downloadImages(context: Context, urls: Iterable<*>?, prefix: String? = null): List<File> {
        if (urls == null) return emptyList()
        val out = ArrayList<File>()
        var index = 1
        for (item in urls) {
            val url = item?.toString()?.takeIf { it.isNotBlank() } ?: continue
            val name = if (prefix.isNullOrBlank()) {
                null
            } else {
                "${prefix}_${index}.${guessExtension(url, "png")}"
            }
            downloadImage(context, url, name)?.let { out.add(it) }
            index++
        }
        return out
    }

    @JvmStatic
    fun downloadFinderImage(context: Context, url: String?, fileName: String? = null): File? {
        if (url.isNullOrBlank()) return null
        val target = File(hchatDir(context, "Finder"), cleanFileName(fileName, "finder_image", guessExtension(url, "png")))
        return downloadFinderImage(context, url, target)
    }

    @JvmStatic
    fun downloadFinderImage(context: Context, url: String?, target: File?): File? {
        if (url.isNullOrBlank() || target == null) return null
        return downloadToFileAtomically(url, target)
    }

    @JvmStatic
    fun downloadFinderVideo(context: Context, url: String?, fileName: String? = null): File? {
        if (url.isNullOrBlank()) return null
        val target = File(hchatDir(context, "Finder"), cleanFileName(fileName, "finder_video", "mp4"))
        return downloadFinderVideo(context, url, target)
    }

    @JvmStatic
    fun downloadFinderVideo(context: Context, url: String?, target: File?): File? {
        if (url.isNullOrBlank() || target == null) return null
        return downloadToFileAtomically(url, target)
    }

    @JvmStatic
    fun downloadAndDecryptFinderVideo(
        context: Context,
        url: String?,
        decodeKey: String?,
        fileName: String? = null
    ): File? {
        if (url.isNullOrBlank() || decodeKey.isNullOrBlank()) return null
        val finalFile = File(hchatDir(context, "Finder"), cleanFileName(fileName, "finder_video", "mp4"))
        return downloadAndDecryptFinderVideo(context, url, decodeKey, finalFile)
    }

    @JvmStatic
    fun downloadAndDecryptFinderVideo(
        context: Context,
        url: String?,
        decodeKey: String?,
        finalFile: File?
    ): File? {
        if (url.isNullOrBlank() || decodeKey.isNullOrBlank() || finalFile == null) return null
        val tmpFile = File(finalFile.parentFile ?: File("."), "${finalFile.name}.tmp")
        return runCatching {
            downloadToFile(url, tmpFile) ?: return@runCatching null
            decryptFile(tmpFile, finalFile, BigInteger(decodeKey))
            tmpFile.delete()
            finalFile
        }.onFailure {
            tmpFile.delete()
            finalFile.delete()
            HLog.e("[Hchat:MediaDownloader] 视频号视频下载解密失败: ${it.message}", it)
        }.getOrNull()
    }

    @JvmStatic
    fun downloadToFile(url: String, target: File): File? {
        return downloadToFile(url, target, exposeOnlyWhenComplete = false)
    }

    @JvmStatic
    fun downloadToFileAtomically(url: String, target: File): File? {
        return downloadToFile(url, target, exposeOnlyWhenComplete = true)
    }

    private fun downloadToFile(url: String, target: File, exposeOnlyWhenComplete: Boolean): File? {
        return runCatching {
            val parent = target.parentFile
            parent?.let { if (!it.isDirectory) it.mkdirs() }
            val outputFile = if (exposeOnlyWhenComplete) {
                File(parent ?: File("."), ".${target.name}.download.${System.nanoTime()}.tmp")
            } else {
                target
            }
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    outputFile.delete()
                    return@runCatching null
                }
                val body = response.body ?: run {
                    outputFile.delete()
                    return@runCatching null
                }
                BufferedInputStream(body.byteStream()).use { input ->
                    FileOutputStream(outputFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
            }
            if (exposeOnlyWhenComplete) {
                Files.move(outputFile.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            target
        }.onFailure {
            if (exposeOnlyWhenComplete) {
                target.parentFile
                    ?.listFiles { file -> file.name.startsWith(".${target.name}.download.") && file.name.endsWith(".tmp") }
                    ?.forEach { file -> runCatching { file.delete() } }
            }
            HLog.e("[Hchat:MediaDownloader] 下载失败: ${it.message}", it)
        }.getOrNull()
    }

    private fun decryptFile(original: File, output: File, key: BigInteger) {
        original.inputStream().use { input ->
            output.outputStream().use { out ->
                val cryptoState = CryptoState(key)
                val buffer = ByteArray(32 * 1024 * 1024)
                var remaining = FINDER_VIDEO_DECRYPT_BYTES
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (remaining > 0) {
                        val decryptLength = minOf(read, remaining)
                        decryptBuffer(buffer, decryptLength, cryptoState)
                        remaining -= decryptLength
                    }
                    out.write(buffer, 0, read)
                }
            }
        }
    }

    private fun decryptBuffer(buffer: ByteArray, length: Int, cryptoState: CryptoState) {
        if (length <= 0) return
        for (i in 0 until length step 8) {
            val f = cryptoState.f
            val keyBlock = cryptoState.c[f]
            if (f == 0) {
                cryptoState.updateState()
                cryptoState.f = 255
            } else {
                cryptoState.f = f - 1
            }
            val keyBytes = ByteArray(8)
            for (j in 0 until 8) {
                val shifted = keyBlock.shiftRight(j * 8)
                val masked = shifted.and(BigInteger.valueOf(255))
                keyBytes[7 - j] = masked.toByteArray().lastOrNull() ?: 0
            }
            val blockSize = minOf(8, length - i)
            for (j in 0 until blockSize) {
                val bufferIndex = i + j
                buffer[bufferIndex] = (buffer[bufferIndex].toInt() xor keyBytes[j].toInt()).toByte()
            }
        }
    }

    private fun cleanFileName(fileName: String?, prefix: String, extension: String): String {
        val raw = fileName?.takeIf { it.isNotBlank() }
            ?: "${prefix}_${System.currentTimeMillis()}.$extension"
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"), "_")
        return if (cleaned.substringAfterLast('.', "").isBlank()) "$cleaned.$extension" else cleaned
    }

    private fun guessExtension(url: String, fallback: String): String {
        val segment = runCatching { Uri.parse(url).lastPathSegment.orEmpty() }.getOrDefault("")
        val ext = segment.substringAfterLast('.', "").lowercase(Locale.US)
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "mp4" -> ext
            else -> fallback
        }
    }

    private class CryptoState(bigInteger: BigInteger) {
        private val mask = BigInteger("ffffffffffffffff", 16)
        private val b: Array<BigInteger> = Array(8) { BigInteger("9e3779b97f4a7c13", 16) }
        val c: Array<BigInteger> = Array(256) { BigInteger.ZERO }
        private val d: Array<BigInteger> = Array(256) { BigInteger.ZERO }
        private val e: Array<BigInteger> = Array(256) { BigInteger.ZERO }
        var f: Int = 255

        init {
            c[0] = bigInteger
            repeat(4) { mix(b) }
            var i = 0
            while (i < 256) {
                for (j in 0 until 8) b[j] = b[j].add(c[i + j]).and(mask)
                mix(b)
                for (j in 0 until 8) d[i + j] = b[j]
                i += 8
            }
            i = 0
            while (i < 256) {
                for (j in 0 until 8) b[j] = b[j].add(d[i + j]).and(mask)
                mix(b)
                for (j in 0 until 8) d[i + j] = b[j]
                i += 8
            }
            updateState()
        }

        private fun mix(state: Array<BigInteger>) {
            state[0] = state[0].subtract(state[4]).and(mask)
            state[5] = state[5].xor(state[7].shiftRight(9)).and(mask)
            state[7] = state[7].add(state[0]).and(mask)
            state[1] = state[1].subtract(state[5]).and(mask)
            state[6] = state[6].xor(state[0].shiftLeft(9)).and(mask)
            state[0] = state[0].add(state[1]).and(mask)
            state[2] = state[2].subtract(state[6]).and(mask)
            state[7] = state[7].xor(state[1].shiftRight(23)).and(mask)
            state[1] = state[1].add(state[2]).and(mask)
            state[3] = state[3].subtract(state[7]).and(mask)
            state[0] = state[0].xor(state[2].shiftLeft(15)).and(mask)
            state[2] = state[2].add(state[3]).and(mask)
            state[4] = state[4].subtract(state[0]).and(mask)
            state[1] = state[1].xor(state[3].shiftRight(14)).and(mask)
            state[3] = state[3].add(state[4]).and(mask)
            state[5] = state[5].subtract(state[1]).and(mask)
            state[2] = state[2].xor(state[4].shiftLeft(20)).and(mask)
            state[4] = state[4].add(state[5]).and(mask)
            state[6] = state[6].subtract(state[2]).and(mask)
            state[3] = state[3].xor(state[5].shiftRight(17)).and(mask)
            state[5] = state[5].add(state[6]).and(mask)
            state[7] = state[7].subtract(state[3]).and(mask)
            state[4] = state[4].xor(state[6].shiftLeft(14)).and(mask)
            state[6] = state[6].add(state[7]).and(mask)
        }

        fun updateState() {
            e[2] = e[2].add(BigInteger.ONE).and(mask)
            e[1] = e[1].add(e[2]).and(mask)
            for (i in 0 until 256) {
                when (i % 4) {
                    0 -> e[0] = e[0].xor(e[0].shiftLeft(21)).not().and(mask)
                    1 -> e[0] = e[0].xor(e[0].shiftRight(5))
                    2 -> e[0] = e[0].xor(e[0].shiftLeft(12))
                    3 -> e[0] = e[0].xor(e[0].shiftRight(33))
                }
                e[0] = e[0].add(d[(i + 128) % 256]).and(mask)
                val di = d[i]
                val index1 = di.shiftRight(3).mod(BigInteger.valueOf(256)).toInt()
                val s = d[index1].add(e[0]).add(e[1]).and(mask)
                d[i] = s
                val index2 = s.shiftRight(11).mod(BigInteger.valueOf(256)).toInt()
                e[1] = d[index2].add(di).and(mask)
                c[i] = e[1]
            }
        }
    }
}
