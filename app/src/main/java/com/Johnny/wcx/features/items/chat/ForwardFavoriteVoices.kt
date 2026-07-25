package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.media.MediaPlayer
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Cloud_download
import com.composables.icons.materialsymbols.outlined.Pause
import com.composables.icons.materialsymbols.outlined.Play_arrow
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.net.models.protobuf.FavInfoProto
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.AudioUtils
import com.Johnny.wcx.utils.RuntimeConfig
import com.Johnny.wcx.utils.android.getTopMostActivity
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.coerceToInt
import com.Johnny.wcx.utils.fs.KnownPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists

private enum class VoiceLoadState {
    CHECKING,
    NOT_CACHED,
    CONVERTING,
    READY,
    ERROR,
}

@Feature(name = "转发收藏语音", categories = ["聊天"], description = "在聊天菜单的「收藏」中允许转发语音")
object ForwardFavoriteVoices : SwitchFeature() {

    @OptIn(ExperimentalSerializationApi::class)
    override fun onEnable() {
        "com.tencent.mm.plugin.fav.ui.FavSelectUI".toClass().reflekt().firstMethod { name = "onItemClick" }.hookBefore {
            val view = args[1] as View

            val tag = view.tag

            val a = tag.reflekt().firstField { name = "a"; superclass() }.get()!!

            val type = a.reflekt().firstField { name = "field_type"; superclass() }.get()!! as Int

            if (type != 3) return@hookBefore

            val favPhoto = a.reflekt().firstField { name = "field_favProto"; superclass() }.get()!!
            val bytes = favPhoto.reflekt().firstMethod { name = "getData"; superclass() }.invoke()!! as ByteArray

            val favInfo = ProtoBuf.decodeFromByteArray<FavInfoProto>(bytes)
            val voiceInfo = favInfo.voiceInfo

            var voiceFilePath = voiceInfo.filePath

            if (voiceFilePath == null) {
                val baseStorageDir = RuntimeConfig.userDataDir
                val cacheName = voiceInfo.fileCacheName
                val bucketId = cacheName.hashCode() and 0xFF

                voiceFilePath = (baseStorageDir / "favorite" / bucketId.toString() / "$cacheName.${voiceInfo.fileCacheType}").absolutePathString()
            }

            val ctx = thisObject as Activity
            val sourcePath = voiceFilePath

            showComposeDialog(ctx) {
                val scope = rememberCoroutineScope()
                val player = remember { MediaPlayer() }
                var loadState by remember { mutableStateOf(VoiceLoadState.CHECKING) }
                var errorMessage by remember { mutableStateOf<String?>(null) }
                var playPath by remember { mutableStateOf<String?>(null) }
                var isPlaying by remember { mutableStateOf(false) }
                var currentPositionMs by remember { mutableLongStateOf(0L) }
                var totalDurationMs by remember { mutableLongStateOf(0L) }

                fun stopAndReset() {
                    runCatching {
                        if (player.isPlaying) player.stop()
                        player.reset()
                    }
                    isPlaying = false
                    currentPositionMs = 0L
                }

                fun prepareAndPlay(path: String) {
                    runCatching {
                        stopAndReset()
                        player.setDataSource(path)
                        player.prepare()
                        totalDurationMs = player.duration.coerceAtLeast(0).toLong()
                        player.setOnCompletionListener {
                            isPlaying = false
                            currentPositionMs = totalDurationMs
                        }
                        player.start()
                        isPlaying = true
                    }.onFailure {
                        loadState = VoiceLoadState.ERROR
                        errorMessage = it.message ?: "播放失败"
                    }
                }

                fun togglePlay() {
                    if (loadState != VoiceLoadState.READY) return
                    val path = playPath ?: return
                    runCatching {
                        if (player.isPlaying) {
                            player.pause()
                            isPlaying = false
                        } else {
                            if (currentPositionMs >= totalDurationMs && totalDurationMs > 0) {
                                player.seekTo(0)
                                currentPositionMs = 0L
                            }
                            player.start()
                            isPlaying = true
                        }
                    }.onFailure {
                        showToast(ctx, it.message ?: "播放失败")
                    }
                }

                LaunchedEffect(Unit) {
                    val source = sourcePath.toPath()
                    if (!source.exists()) {
                        loadState = VoiceLoadState.NOT_CACHED
                        return@LaunchedEffect
                    }

                    loadState = VoiceLoadState.CONVERTING
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val silkName = source.name
                            val baseName = silkName.substringBeforeLast('.')
                            val pcmPath = KnownPaths.moduleCache / "$baseName.pcm"
                            val mp3Path = KnownPaths.moduleCache / "$baseName.mp3"

                            if (!mp3Path.exists()) {
                                pcmPath.deleteIfExists()
                                val ok = AudioUtils.silkToPcm(source.absolutePathString(), pcmPath.absolutePathString())
                                if (!ok) throw RuntimeException("silk 解码失败")
                                val ok2 = AudioUtils.pcmToMp3(pcmPath.absolutePathString(), mp3Path.absolutePathString())
                                if (!ok2) throw RuntimeException("mp3 编码失败")
                                pcmPath.deleteIfExists()
                            }
                            mp3Path.absolutePathString()
                        }
                    }
                    result.onSuccess { mp3 ->
                        playPath = mp3
                        loadState = VoiceLoadState.READY
                        totalDurationMs = AudioUtils.getDurationMs(mp3).coerceAtLeast(0L)
                    }.onFailure {
                        loadState = VoiceLoadState.ERROR
                        errorMessage = it.message ?: "语音解码失败"
                    }
                }

                DisposableEffect(Unit) {
                    onDispose {
                        runCatching { player.release() }
                    }
                }

                LaunchedEffect(isPlaying) {
                    while (isPlaying && loadState == VoiceLoadState.READY) {
                        currentPositionMs = runCatching { player.currentPosition.toLong() }
                            .getOrDefault(currentPositionMs)
                        delay(200.milliseconds)
                    }
                }

                AlertDialogContent(
                    title = { Text("转发收藏语音") },
                    text = {
                        Column {
                            VoicePreviewBar(
                                loadState = loadState,
                                errorMessage = errorMessage,
                                isPlaying = isPlaying,
                                currentPositionMs = currentPositionMs,
                                totalDurationMs = totalDurationMs,
                                onTogglePlay = ::togglePlay,
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onDismiss) { Text("取消") }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (!sourcePath.toPath().exists()) {
                                    showToast(ctx, "语音未缓存，请先在收藏中播放一次")
                                    return@Button
                                }
                                scope.launch {
                                    val durationMs = if (FakeVoiceDuration.isActive) {
                                        FakeVoiceDuration.getFakeDurationMs().toInt()
                                    } else {
                                        totalDurationMs.coerceAtLeast(0).coerceToInt()
                                    }
                                    val success = withContext(Dispatchers.IO) {
                                        runCatching {
                                            WeMessageApi.sendVoice(
                                                WeCurrentConversationApi.value,
                                                sourcePath,
                                                durationMs,
                                            )
                                        }.isSuccess
                                    }
                                    if (success) {
                                        showToast(ctx, "已发送")
                                        onDismiss()
                                        getTopMostActivity()?.finish()
                                    } else {
                                        showToast(ctx, "发送失败")
                                    }
                                }
                            },
                            enabled = loadState == VoiceLoadState.READY,
                        ) { Text("发送") }
                    })
            }

            result = null
        }
    }
}

@Composable
private fun VoicePreviewBar(
    loadState: VoiceLoadState,
    errorMessage: String?,
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    onTogglePlay: () -> Unit,
) {
    val progress = if (totalDurationMs > 0) min(1f, currentPositionMs.toFloat() / totalDurationMs.toFloat()) else 0f
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(surfaceColor)
            .then(
                if (loadState == VoiceLoadState.READY) {
                    Modifier.clickable(onClick = onTogglePlay)
                } else Modifier
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (loadState) {
            VoiceLoadState.CHECKING,
            VoiceLoadState.CONVERTING -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                )
            }
            VoiceLoadState.NOT_CACHED -> {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Cloud_download,
                    contentDescription = "未缓存",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            VoiceLoadState.READY -> {
                Icon(
                    imageVector = if (isPlaying) MaterialSymbols.Outlined.Pause else MaterialSymbols.Outlined.Play_arrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            VoiceLoadState.ERROR -> {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Play_arrow,
                    contentDescription = "错误",
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                if (loadState == VoiceLoadState.READY || loadState == VoiceLoadState.ERROR) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = when (loadState) {
                        VoiceLoadState.CHECKING -> "检测中..."
                        VoiceLoadState.CONVERTING -> "解码中..."
                        VoiceLoadState.NOT_CACHED -> "未缓存"
                        VoiceLoadState.READY -> formatDuration(currentPositionMs)
                        VoiceLoadState.ERROR -> formatDuration(currentPositionMs)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = when (loadState) {
                        VoiceLoadState.NOT_CACHED -> "请先在收藏中播放一次"
                        VoiceLoadState.ERROR -> errorMessage ?: "加载失败"
                        else -> formatDuration(totalDurationMs)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (loadState) {
                        VoiceLoadState.NOT_CACHED -> MaterialTheme.colorScheme.onSurfaceVariant
                        VoiceLoadState.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun String.toPath() = java.io.File(this).toPath()
