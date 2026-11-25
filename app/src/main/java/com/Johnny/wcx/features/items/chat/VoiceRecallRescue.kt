package com.Johnny.wcx.features.items.chat

import com.Johnny.wcx.utils.fs.createDirsSafe
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.android.showToastSuspend
import com.Johnny.wcx.utils.fs.KnownPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.div
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 语音防撤回「可播放」补齐。
 *
 * 现状：[AntiMessageRecall] 直接把 `BigBallSysMsgConsumer.doRevokeMsg` 整个跳过，message 行留住了，
 * 但语音文件本身不归它管 —— 微信清理过本地语音（或这条语音压根没下载过）时，
 * 还原出来的语音气泡一点就「播放失败」。
 *
 * 这里补两件事，都挂在同一个已确认锚点上：
 * 1. 撤回发生前（hookBefore）：按 msgSvrId 查 message 表拿到 imgPath，用 [WeMessageApi.getVoiceFullPath]
 *    解析出真实文件路径，把文件复制到 `<moduleData>/voice_rescue/<msgSvrId>.amr`，
 *    并把「期望路径」写到同名 `.path` 旁注文件里。
 * 2. 撤回处理完（hookAfter，稍等片刻）：如果 message 行还在（说明防撤回生效、消息被还原），
 *    而原路径上的语音文件已经没了，就用救援副本回填到原路径，让气泡重新能播。
 *
 * 明确不做假成功：撤回时本地就没有文件（未下载/已被清理）、也没有历史副本的话，
 * 只写一条 warn 日志说明「这条救不回来」，不会谎报成功。
 */
@Feature(
    name = "语音防撤回救援",
    categories = ["聊天"],
    description = "撤回前先把语音文件备份到模块目录, 消息还原后若原文件已被清理则自动回填, 让被撤回的语音仍可播放"
)
object VoiceRecallRescue : ClickableFeature(), IResolveDex {

    private const val TAG = "VoiceRecallRescue"

    private const val KEY_AUTO_RESCUE = "voice_recall_rescue_auto"
    private const val KEY_ARCHIVE_LIMIT = "voice_recall_rescue_archive_limit"

    private const val RESCUE_DIR_NAME = "voice_rescue"

    /** 撤回处理完到回填之间的等待：给微信/防撤回把 message 行处理完的时间。 */
    private const val RESTORE_DELAY_MS = 1_200L

    private const val DEFAULT_ARCHIVE_LIMIT = 50
    private const val MAX_ARCHIVE_LIMIT = 500

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 8.0.80 已确认：`w41.u#c(String, long, com.tencent.mm.modelbase.p0, String, String, String)`
     * （classes11.dex），方法内含特征串 `doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s`。
     */
    private val methodRevokeHandler by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("doRevokeMsg xmlSrvMsgId=%d talker=%s isGet=%s")
        }
    }

    /** 与 AntiMessageRecall 保持一致：优先用 revokemsg XML 里的 newmsgid 取真实 msgSvrId。 */
    private val revokeNewMsgIdRegex = Regex("newmsgid[^0-9]{0,24}(\\d{6,})")

    // ---------------------------------------------------------------- 生命周期

    override fun onEnable() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        if (methodRevokeHandler.isPlaceholder) {
            WeLogger.e(TAG, "doRevokeMsg 锚点未解析（8.0.80 期望 w41.u#c），语音救援不生效")
            return
        }
        runCatching {
            methodRevokeHandler.hookBefore { handleRevokeBefore(args) }
            methodRevokeHandler.hookAfter { handleRevokeAfter(args) }
            WeLogger.i(TAG, "已挂接 doRevokeMsg：撤回前备份语音、撤回后按需回填")
        }.onFailure { WeLogger.e(TAG, "hook doRevokeMsg 失败", it) }
    }

    override fun onDisable() {
        runCatching { scope.cancel() }.onFailure { WeLogger.w(TAG, "取消协程作用域失败: ${it.message}") }
    }

    // ---------------------------------------------------------------- 撤回拦截点

    /**
     * 撤回前备份：**同步**执行，不进协程。
     *
     * 撤回处理里微信随后就会去删本地媒体，异步复制存在「副本还没写完文件已经被删」的竞态；
     * 这里的耗时只是一次 SQLite 单行查询 + 一次小文件复制（通常 10ms 量级），
     * 且 doRevokeMsg 本身跑在微信的 worker 线程上，不是主线程，不会卡 UI。
     */
    private fun handleRevokeBefore(args: Array<Any?>) {
        if (!autoRescueEnabled()) return
        val srvId = extractMsgSvrId(args)
        if (srvId <= 0L) {
            WeLogger.w(TAG, "撤回拦截命中但取不到 msgSvrId，跳过语音备份")
            return
        }
        val startedAt = System.currentTimeMillis()
        runCatching { rescueVoice(srvId) }
            .onFailure { WeLogger.e(TAG, "撤回前备份语音失败：msgSvrId=$srvId", it) }
        WeLogger.i(TAG, "撤回前备份耗时 ${System.currentTimeMillis() - startedAt}ms（msgSvrId=$srvId）")
    }

    private fun handleRevokeAfter(args: Array<Any?>) {
        if (!autoRescueEnabled()) return
        val srvId = extractMsgSvrId(args)
        if (srvId <= 0L) return
        scope.launch {
            // 等一会儿：让微信/防撤回把 message 行处理完再判断要不要回填
            delay(RESTORE_DELAY_MS)
            runCatching { restoreVoiceIfNeeded(srvId) }
                .onFailure { WeLogger.e(TAG, "撤回后回填语音失败：msgSvrId=$srvId", it) }
        }
    }

    private fun autoRescueEnabled(): Boolean = runCatching {
        WePrefs.getBoolOrDef(KEY_AUTO_RESCUE, true)
    }.getOrDefault(true)

    /**
     * 取被撤回消息的 msgSvrId。
     *
     * 8.0.80 的 doRevokeMsg 参数是 `(String xmlSrvMsgId, long, p0, String, String, String)`，
     * 直接取第一个 long 不一定对（既有 AntiMessageRecall 的注释里提到过），所以先扫字符串参数找
     * revokemsg XML 里的 newmsgid，拿不到再退回 long。
     */
    private fun extractMsgSvrId(args: Array<Any?>): Long {
        val xmlLike = args.filterIsInstance<String>()
            .firstOrNull { it.contains("revokemsg") || it.contains("newmsgid") }
        val fromXml = xmlLike?.let { revokeNewMsgIdRegex.find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }
        if (fromXml != null && fromXml > 0L) return fromXml
        return args.filterIsInstance<Long>().firstOrNull { it > 0L } ?: 0L
    }

    // ---------------------------------------------------------------- 备份

    /** 把一条语音复制到救援目录；返回值仅用于统计（true = 本次确实写入了副本）。 */
    private fun rescueVoice(srvId: Long): Boolean {
        val row = queryVoiceRow(srvId) ?: run {
            // 非语音 / 行已不在：安静跳过（撤回动作仍然命中过，这里不是错误）
            WeLogger.i(TAG, "msgSvrId=$srvId 不是语音消息或 message 行已不存在，跳过语音备份")
            return false
        }
        if (row.imgPath.isBlank()) {
            WeLogger.w(TAG, "语音消息没有 imgPath，无法定位文件：msgSvrId=$srvId talker=${row.talker}")
            return false
        }
        val fullPath = runCatching { WeMessageApi.getVoiceFullPath(row.imgPath) }
            .getOrElse {
                WeLogger.e(TAG, "语音路径解析失败：msgSvrId=$srvId imgPath=${row.imgPath}", it)
                return false
            }
        if (fullPath.isBlank()) {
            WeLogger.w(TAG, "语音路径解析为空：msgSvrId=$srvId imgPath=${row.imgPath}")
            return false
        }

        val source = File(fullPath)
        if (!source.isFile || source.length() <= 0L) {
            // 关键：本地没有文件就救不了，明确记日志，绝不假装成功
            WeLogger.w(
                TAG,
                "语音文件本地不存在（未下载或已被微信清理），本次无可救援内容：msgSvrId=$srvId path=$fullPath"
            )
            return false
        }

        val dir = rescueDir()
        val backup = dir / "$srvId.amr"
        val sidecar = dir / "$srvId.path"
        return runCatching {
            Files.copy(source.toPath(), backup, StandardCopyOption.REPLACE_EXISTING)
            Files.write(sidecar, fullPath.toByteArray(Charsets.UTF_8))
            WeLogger.i(TAG, "已备份语音：msgSvrId=$srvId -> $backup（期望路径 $fullPath）")
            true
        }.getOrElse {
            WeLogger.e(TAG, "写入救援副本失败：msgSvrId=$srvId", it)
            false
        }
    }

    // ---------------------------------------------------------------- 回填

    private suspend fun restoreVoiceIfNeeded(srvId: Long) {
        val dir = rescueDir()
        val backup = dir / "$srvId.amr"
        if (!runCatching { Files.isRegularFile(backup) && Files.size(backup) > 0L }.getOrDefault(false)) {
            WeLogger.i(TAG, "msgSvrId=$srvId 没有救援副本，跳过回填")
            return
        }

        // 只在「消息还在」时回填：行都被删了的话回填只是制造孤儿文件
        val row = queryVoiceRow(srvId)
        if (row == null) {
            WeLogger.i(TAG, "msgSvrId=$srvId 的 message 行已不在（撤回未被拦截），副本保留但不回填")
            return
        }

        val expected = readExpectedPath(dir / "$srvId.path")
            ?: queryExpectedPath(row.imgPath)
        if (expected.isNullOrBlank()) {
            WeLogger.w(TAG, "msgSvrId=$srvId 有副本但取不到期望路径，无法回填")
            return
        }

        val target = File(expected)
        if (target.isFile && target.length() > 0L) {
            // 原文件还在（防撤回整体跳过撤回时通常如此），无需回填
            return
        }

        val ok = runCatching {
            target.parentFile?.mkdirs()
            Files.copy(backup, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            target.isFile && target.length() > 0L
        }.onFailure {
            WeLogger.e(TAG, "回填语音失败：msgSvrId=$srvId target=$expected", it)
        }.getOrDefault(false)

        if (ok) {
            WeLogger.i(TAG, "已用救援副本回填语音：msgSvrId=$srvId -> $expected")
            showToastSuspend("已恢复被撤回的语音文件（可正常播放）")
        } else {
            WeLogger.w(TAG, "回填后校验失败：msgSvrId=$srvId target=$expected")
        }
    }

    private fun readExpectedPath(sidecar: Path): String? = runCatching {
        if (!Files.isRegularFile(sidecar)) null
        else String(Files.readAllBytes(sidecar), Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** 旁注文件丢失时，按当前 message 行的 imgPath 再算一次期望路径。 */
    private fun queryExpectedPath(imgPath: String): String? = runCatching {
        WeMessageApi.getVoiceFullPath(imgPath).takeIf { it.isNotBlank() }
    }.getOrNull()

    // ---------------------------------------------------------------- 数据 / 路径

    private class VoiceRow(val imgPath: String, val talker: String)

    private fun queryVoiceRow(srvId: Long): VoiceRow? = runCatching {
        WeDatabaseApi.rawQuery(
            "SELECT type, imgPath, talker FROM message WHERE msgSvrId=?",
            arrayOf<Any>(srvId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                null
            } else {
                val type = cursor.getInt(0)
                if (type != MessageType.VOICE.code) {
                    null
                } else {
                    VoiceRow(cursor.getString(1) ?: "", cursor.getString(2) ?: "")
                }
            }
        }
    }.onFailure {
        WeLogger.e(TAG, "查询 message 表失败：msgSvrId=$srvId", it)
    }.getOrNull()

    private fun rescueDir(): Path = (KnownPaths.moduleData / RESCUE_DIR_NAME).createDirsSafe()

    private fun rescueFiles(): List<File> = runCatching {
        rescueDir().toFile().listFiles()?.filter { it.isFile } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun statusText(): String {
        val files = rescueFiles()
        val count = files.count { it.name.endsWith(".amr") }
        val totalBytes = files.sumOf { it.length() }
        val sizeMb = totalBytes / 1024.0 / 1024.0
        return "已备份 $count 条语音，占用 %.2f MB".format(sizeMb)
    }

    // ---------------------------------------------------------------- 手动归档 / 清理

    /** 归档当前会话最近若干条语音（防撤回前就先把文件存下来，撤回时可以救）。 */
    private fun archiveCurrentConversation(talker: String): Pair<Int, Int> {
        val limit = WePrefs.getIntOrDef(KEY_ARCHIVE_LIMIT, DEFAULT_ARCHIVE_LIMIT)
            .coerceIn(1, MAX_ARCHIVE_LIMIT)
        val ids = runCatching {
            WeDatabaseApi.rawQuery(
                "SELECT msgSvrId FROM message WHERE talker=? AND type=34 AND msgSvrId>0" +
                    " ORDER BY createTime DESC LIMIT $limit",
                arrayOf<Any>(talker)
            ).use { cursor ->
                val list = ArrayList<Long>(limit)
                while (cursor.moveToNext()) list.add(cursor.getLong(0))
                list
            }
        }.onFailure {
            WeLogger.e(TAG, "扫描会话语音失败：talker=$talker", it)
        }.getOrDefault(emptyList())

        if (ids.isEmpty()) return 0 to 0

        var succeeded = 0
        ids.forEach { id ->
            if (runCatching { rescueVoice(id) }.getOrDefault(false)) succeeded += 1
        }
        return succeeded to ids.size
    }

    private fun clearRescueCopies(): Int {
        val files = rescueFiles()
        var removed = 0
        files.forEach { file ->
            if (runCatching { file.delete() }.getOrDefault(false)) removed += 1
        }
        return removed
    }

    // ---------------------------------------------------------------- 设置 / 状态 UI

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var autoRescue by remember { mutableStateOf(autoRescueEnabled()) }
            var status by remember { mutableStateOf("正在统计救援副本...") }
            var dirText by remember { mutableStateOf("") }
            var busy by remember { mutableStateOf(false) }
            val uiScope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                val loaded = withContext(Dispatchers.IO) {
                    val path = runCatching { rescueDir().toString() }.getOrDefault("")
                    val text = runCatching { statusText() }
                        .getOrElse { "统计失败：" + (it.message ?: it.javaClass.simpleName) }
                    path to text
                }
                dirText = loaded.first
                status = loaded.second
            }

            AlertDialogContent(
                title = { Text("语音防撤回救援") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "撤回发生时先把语音文件备份到模块目录；消息被还原后，如果微信把原文件清掉了，" +
                                "就用副本回填到原路径，让被撤回的语音还能播放。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                        if (dirText.isNotBlank()) {
                            Text("备份目录：$dirText", style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            "自动救援：" + if (autoRescue) "已开启" else "已关闭",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                autoRescue = !autoRescue
                                WePrefs.putBool(KEY_AUTO_RESCUE, autoRescue)
                                showToast(if (autoRescue) "已开启自动救援" else "已关闭自动救援")
                            }) {
                                Text(if (autoRescue) "关闭自动救援" else "开启自动救援")
                            }
                            Button(
                                onClick = {
                                    if (busy) {
                                        showToast("正在处理，请稍等")
                                        return@Button
                                    }
                                    busy = true
                                    uiScope.launch {
                                        val talker = WeCurrentConversationApi.value
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching { archiveCurrentConversation(talker) }
                                        }
                                        busy = false
                                        result.onSuccess { (succeeded, total) ->
                                            if (total == 0) {
                                                showToast("当前会话没有可归档的语音（或不在聊天界面）")
                                            } else {
                                                showToast("已归档 $succeeded/$total 条语音")
                                            }
                                        }.onFailure {
                                            WeLogger.e(TAG, "归档当前会话语音失败", it)
                                            showToast("归档失败，详见日志")
                                        }
                                        // 目录统计是 IO，不能在主线程做
                                        status = withContext(Dispatchers.IO) {
                                            runCatching { statusText() }.getOrDefault(status)
                                        }
                                    }
                                }
                            ) { Text("归档当前会话") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                uiScope.launch {
                                    val removed = withContext(Dispatchers.IO) {
                                        runCatching { clearRescueCopies() }
                                    }
                                    removed.onSuccess { n ->
                                        showToast("已清理 $n 个救援副本")
                                    }.onFailure {
                                        WeLogger.e(TAG, "清理救援副本失败", it)
                                        showToast("清理失败，详见日志")
                                    }
                                    status = withContext(Dispatchers.IO) {
                                        runCatching { statusText() }.getOrDefault(status)
                                    }
                                }
                            }) { Text("清理备份") }
                        }
                        Text(
                            "提示：全部聊天共用一个备份目录，撤回时按 msgSvrId 命名，不会串条。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = onDismiss) { Text("关闭") }
                }
            )
        }
    }
}
