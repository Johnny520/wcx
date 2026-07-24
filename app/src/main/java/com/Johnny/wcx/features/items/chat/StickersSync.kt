package com.Johnny.wcx.features.items.chat

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.tencent.mm.storage.emotion.EmojiGroupInfo
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexConstructor
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeServiceApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToastSuspend
import com.Johnny.wcx.utils.enumValueOfClass
import com.Johnny.wcx.utils.fs.KnownPaths
import com.Johnny.wcx.utils.fs.createDirsSafe
import com.Johnny.wcx.utils.polyfills.intoList
import com.Johnny.wcx.utils.reflection.DexKit
import com.Johnny.wcx.utils.reflection.asClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.walk
import kotlin.io.path.writeText

@Feature(
    name = "贴纸包同步",
    categories = ["聊天"],
    description = "从指定路径将所有图片注册为贴纸包\n搭配 Telegram Xposed 模块 StickersSync 使用, 或使用自带此功能的 (例如 Nagram) 的第三方客户端\n注意: 每张贴纸第一次加载由于需要计算 MD5 速度较慢, 后续加载得益于缓存与并发速度将大大加快 (~2000 个贴纸仅需 4 秒)"
)
object StickersSync : ClickableFeature(), IResolveDex {

    private const val TAG = "StickersSync"
    private const val STICKER_PACK_ID_PREFIX = "wekit.stickers.sync"
    private val ALLOWED_STICKER_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")

    private data class StickerPack(
        val appPackId: String,
        val packId: String,
        val packName: String,
        val stickers: List<Any>
    )

    @Serializable
    private data class HashCache(
        val hashes: Map<String, String> = emptyMap()
    )

    private fun loadHashCache(packPath: Path): HashCache {
        val cacheFile = packPath.resolve(".hashes.json")
        return try {
            if (cacheFile.isRegularFile()) {
                Json.decodeFromString<HashCache>(cacheFile.readText())
            } else {
                HashCache()
            }
        } catch (ex: Exception) {
            WeLogger.e(TAG, "failed to load hash cache from ${cacheFile.absolutePathString()}", ex)
            HashCache()
        }
    }

    private fun saveHashCache(packPath: Path, cache: HashCache) {
        val cacheFile = packPath.resolve(".hashes.json")
        try {
            cacheFile.writeText(Json.encodeToString(cache))
        } catch (ex: Exception) {
            WeLogger.e(TAG, "failed to save hash cache to ${cacheFile.absolutePathString()}", ex)
        }
    }

    private val stickerPacks: List<StickerPack> by lazy {
        runBlocking {
            showToastSuspend("正在加载贴纸包...")

            withContext(Dispatchers.IO) {
                val packDirs = Files.list(stickersDir).filter { Files.isDirectory(it) }.intoList()
                if (packDirs.isEmpty()) {
                    showToastSuspend("未找到任何贴纸包")
                    return@withContext emptyList<StickerPack>()
                }

                // use a semaphore to limit the max amount of sticker packs being processed at the same time
                val semaphore = Semaphore(5)

                val packs = packDirs.map { packDir ->
                    async {
                        semaphore.withPermit {
                            val packDirName = packDir.name
                            val stickers = mutableListOf<Any>()

                            val hashCache = loadHashCache(packDir)
                            val newHashes = mutableMapOf<String, String>()

                            val images = packDir.walk()
                                .filter {
                                    it.isRegularFile() &&
                                            it.extension.lowercase() in ALLOWED_STICKER_EXTENSIONS &&
                                            !it.name.startsWith(".pack_icon.") &&
                                            !(it.extension.lowercase() == "webp" && it.resolveSibling("${it.nameWithoutExtension}.png").isRegularFile())
                                }
                                .toList()

                            images.forEach { path ->
                                try {
                                    val actualPath = if (path.extension.lowercase() == "webp") {
                                        convertWebpToPng(path) ?: return@forEach
                                    } else {
                                        path
                                    }

                                    val absPath = actualPath.absolutePathString()
                                    val fileName = actualPath.fileName.toString()

                                    val md5 = hashCache.hashes[fileName]
                                        ?: WeServiceApi.getEmojiMd5FromPath(HostInfo.application, absPath)
                                    newHashes[fileName] = md5

                                    val emojiThumb = WeServiceApi.getEmojiInfoByMd5(md5)
                                    WeServiceApi.methodSaveEmojiThumb.method.invoke(emojiThumb, null, true)
                                    val groupItemInfo = ctorGroupItemInfo.newInstance(emojiThumb, 2, "", 0)
                                    stickers.add(groupItemInfo)
                                } catch (e: Exception) {
                                    WeLogger.e(TAG, "failed to load sticker: $path", e)
                                }
                            }

                            if (newHashes.isNotEmpty()) {
                                saveHashCache(packDir, HashCache(newHashes))
                            }

                            if (stickers.isNotEmpty()) {
                                WeLogger.i(
                                    TAG,
                                    "loaded pack '$packDirName' with ${stickers.size} stickers"
                                )
                                StickerPack(
                                    appPackId = "$STICKER_PACK_ID_PREFIX.$packDirName",
                                    packId = packDirName,
                                    packName = packDirName,
                                    stickers = stickers
                                )
                            } else null
                        }
                    }
                }.awaitAll().filterNotNull()

                val totalStickers = packs.sumOf { it.stickers.size }
                showToastSuspend("成功加载 ${packs.size} 个贴纸包, 共 $totalStickers 个贴纸")

                packs
            }
        }
    }

    private fun convertWebpToPng(webpPath: Path): Path? {
        return try {
            val pngPath = webpPath.resolveSibling("${webpPath.nameWithoutExtension}.png")

            if (pngPath.isRegularFile()) {
                return pngPath
            }

            val webpBitmap = BitmapFactory.decodeFile(webpPath.absolutePathString())
            if (webpBitmap == null) {
                WeLogger.e(TAG, "failed to decode WebP: ${webpPath.absolutePathString()}")
                return null
            }
            pngPath.toFile().outputStream().use { output ->
                webpBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            webpBitmap.recycle()
            pngPath
        } catch (ex: Exception) {
            WeLogger.e(TAG, "failed to convert WebP to PNG: ${webpPath.absolutePathString()}", ex)
            null
        }
    }

    private val methodGetEmojiGroupInfo by dexMethod {
        matcher {
            paramTypes(Int::class.java)
            usingEqStrings("MicroMsg.emoji.EmojiGroupInfoStorage", "get Panel EmojiGroupInfo.")
        }
    }
    private val methodAddAllGroupItems by dexMethod {
        matcher {
            usingEqStrings("data")
            addInvoke {
                usingEqStrings("checkScrollToPosition: ")
            }
        }
    }
    private val ctorGroupItemInfo by dexConstructor {
        matcher {
            usingEqStrings("emojiInfo", "sosDocId")
        }
    }
    private val ctorResourceLoadOptions by dexConstructor {
        matcher {
            declaredClass {
                modifiers = Modifier.FINAL
                addFieldForType(Any::class.java)
                addField {
                    type {
                        superClass("java.lang.Enum")
                    }
                }
                usingEqStrings("")
            }

            paramTypes(String::class.java)
        }
    }
    private val methodDownloadImage by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.Loader.DefaultImageDownloader.HttpClientFactory", "dz[httpURLConnectionGet 300]")
        }
    }

    private val stickersDir: Path by lazy {
        (KnownPaths.moduleData / "stickers")
            .createDirsSafe()
    }


    private const val PLACEHOLDER_PACK_URL = "NOTURL://STICKER_PACK"
    private const val SEPERATOR = ";"

    private var actualRetTypeInitArg2Type: Class<*>? = null

    override fun onEnable() {
        @Suppress("UNCHECKED_CAST")
        methodGetEmojiGroupInfo.hookAfter {
            // Inject each sticker pack
            stickerPacks.forEachIndexed { index, pack ->
                val stickersPackData = ContentValues().apply {
                    put(
                        "packGrayIconUrl",
                        "$PLACEHOLDER_PACK_URL$SEPERATOR${pack.packName}"
                    )
                    put(
                        "packIconUrl",
                        "$PLACEHOLDER_PACK_URL$SEPERATOR${pack.packName}"
                    )
                    put("packName", pack.packName)
                    put("packStatus", 1)
                    put("productID", pack.appPackId)
                    put("status", 7)
                    put("sync", 2)
                }

                val emojiGroupInfo = EmojiGroupInfo()
                emojiGroupInfo.convertFrom(stickersPackData, true)

                (result as MutableList<Any?>).add(index, emojiGroupInfo)
            }
            WeLogger.i(TAG, "injected ${stickerPacks.size} sticker packs")
        }

        @Suppress("UNCHECKED_CAST")
        methodAddAllGroupItems.hookBefore {
            val manager = args[0] ?: return@hookBefore

            val packConfig = manager.reflekt()
                .firstMethod {
                    superclass()
                    modifiers(Modifiers.FINAL)
                    returnType {
                        it != Boolean::class.java
                    }
                }
                .invoke()
            val emojiGroupInfo = packConfig!!.reflekt()
                .firstField {
                    type = "com.tencent.mm.storage.emotion.EmojiGroupInfo"
                }.get()!!
            val packId = emojiGroupInfo.reflekt()
                .firstField {
                    superclass()
                    name = "field_packName"
                }
                .get()!! as String

            // Find matching sticker pack
            val matchingPack = stickerPacks.find { it.packId == packId }
            if (matchingPack != null) {
                val stickerList = manager.reflekt().firstMethod {
                    superclass()
                    returnType = List::class
                }.invoke() as MutableList<Any?>
                stickerList.addAll(matchingPack.stickers)
            }
        }

        ctorResourceLoadOptions.hookAfter {
            val url = args[0] as String
            if (url.startsWith(PLACEHOLDER_PACK_URL)) {
                val fResSource = thisObject.reflekt()
                    .firstField {
                        type { it isSubclassOf Enum::class }
                    }
                val newResSource = enumValueOfClass(fResSource.get()!!.javaClass, "LOCAL_PATH")
                fResSource.set(newResSource)
                val packDir = stickersDir / url.substringAfter(SEPERATOR)
                val iconFile = (ALLOWED_STICKER_EXTENSIONS - "webp").firstNotNullOfOrNull { ext ->
                    (packDir / ".pack_icon.$ext").takeIf { it.isRegularFile() }
                }
                val path = if (iconFile != null) {
                    iconFile.absolutePathString()
                } else {
                    val fallback = packDir.walk().firstOrNull { f ->
                        f.isRegularFile() &&
                                f.extension.lowercase() in ALLOWED_STICKER_EXTENSIONS &&
                                !f.name.startsWith(".pack_icon.")
                    }
                    (fallback ?: packDir / ".pack_icon.png").absolutePathString()
                }
                thisObject.reflekt()
                    .firstField { type = Any::class }
                    .set(path)
            }
        }

        methodDownloadImage.hookBefore {
            val url = args[0] as String
            if (!url.startsWith("/")) return@hookBefore
            val retType = methodDownloadImage.method.returnType
            val path = runCatching { Path(url) }.getOrElse { e ->
                WeLogger.d(TAG, "could not convert $url to path", e)
                return@hookBefore
            }
            val bytes = path.readBytes()
            val retTypeCtor = retType.constructors[0]
            val retTypeInitArg2Type = retTypeCtor.parameters[2].type
            if (actualRetTypeInitArg2Type == null) {
                actualRetTypeInitArg2Type =
                    DexKit.findClass {
                        matcher {
                            addInterface(retTypeInitArg2Type.name)
                            addMethod {
                                paramTypes(ByteArray::class.java)
                            }
                        }
                    }[0].asClass
            }
            result = retType.createInstance(
                bytes, "image/png",
                actualRetTypeInitArg2Type!!.createInstance(bytes)
            )
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("贴纸包同步") },
                text = {
                    Column {
                        Row(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .clickable {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        stickerPacks.forEach { pack ->
                                            WeDatabaseApi.delete(
                                                "EmojiGroupInfo",
                                                "productID = ?",
                                                arrayOf(pack.appPackId)
                                            )
                                        }
                                        showToastSuspend("已清除 ${stickerPacks.size} 个贴纸包缓存!")
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "清除微信数据库贴纸包缓存",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }

                        Row(
                            modifier = androidx.compose.ui.Modifier
                                .fillMaxWidth()
                                .clickable {
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val deleted = withContext(Dispatchers.IO) {
                                            val hashFiles = if (stickersDir.isRegularFile()) emptyList() else {
                                                stickersDir.walk().filter {
                                                    it.isRegularFile() && it.name == ".hashes.json"
                                                }.toList()
                                            }
                                            hashFiles.forEach { it.deleteIfExists() }
                                            hashFiles.size
                                        }
                                        showToastSuspend("已清除 $deleted 个哈希缓存文件!")
                                    }
                                }
                                .padding(vertical = 12.dp, horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "清除贴纸哈希缓存",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                })
        }
    }
}
