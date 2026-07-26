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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
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
import kotlinx.coroutines.delay
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists

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

            showComposeDialog(ctx) {
                val player = remember { MediaPlayer() }
                var isPlaying by remember { mutableStateOf(false) }
                var currentPositionMs by remember { mutableLongStateOf(0L) }
                val totalDurationMs = remember { AudioUtils.getDurationMs(voiceFilePath).coerceAtLeast(0L) }
                var prepared by remember { mutableStateOf(false) }
                var prepareError by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    val voiceFile = voiceFilePath.toPath()
                    if (!voiceFile.exists()) {
                        prepareError = "语音未缓存，请先在收藏中播放一次"
                        return@LaunchedEffect
                    }
                    runCatching {
                        player.setDataSource(voiceFilePath)
                        player.prepare()
                        player.setOnCompletionListener {
                            isPlaying = false
                            currentPositionMs = totalDurationMs
                        }
                        prepared = true
                    }.onFailure {
                        prepareError = it.message ?: "音频加载失败"
                    }
                }

                DisposableEffect(Unit) {
                    onDispose {
                        runCatching { player.release() }
                    }
                }

                LaunchedEffect(isPlaying) {
                    while (isPlaying && prepared) {
                        currentPositionMs = runCatching { player.currentPosition.toLong() }
                            .getOrDefault(currentPositionMs)
                        delay(200.milliseconds)
                    }
                }

                fun togglePlay() {
                    if (!prepared || prepareError != null) return
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

                AlertDialogContent(
                    title = { Text("转发收藏语音") },
                    text = {
                        Column {
                            VoicePreviewBar(
                                isPlaying = isPlaying,
                                currentPositionMs = currentPositionMs,
                                totalDurationMs = totalDurationMs,
                                prepareError = prepareError,
                                onTogglePlay = ::togglePlay,
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onDismiss) { Text("取消") }
                    },
                    confirmButton = {
                        Button({
                            val voiceFile = voiceFilePath.toPath()
                            if (!voiceFile.exists()) {
                                showToast(ctx, "语音未缓存，请先在收藏中播放一次")
                                return@Button
                            }
                            val durationMs = if (FakeVoiceDuration.isActive) {
                                FakeVoiceDuration.getFakeDurationMs().toInt()
                            } else {
                                totalDurationMs.coerceToInt()
                            }
                            WeMessageApi.sendVoice(WeCurrentConversationApi.value, voiceFilePath, durationMs)
                            showToast(ctx, "已发送")
                            onDismiss()
                            getTopMostActivity()?.finish()
                        }) { Text("发送") }
                    })
            }

            result = null
        }
    }
}

@Composable
private fun VoicePreviewBar(
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    prepareError: String?,
    onTogglePlay: () -> Unit,
) {
    val progress = if (totalDurationMs > 0) min(1f, currentPositionMs.toFloat() / totalDurationMs.toFloat()) else 0f
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(surfaceColor)
            .clickable(enabled = prepareError == null, onClick = onTogglePlay)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isPlaying) MaterialSymbols.Outlined.Pause else MaterialSymbols.Outlined.Play_arrow,
            contentDescription = if (isPlaying) "暂停" else "播放",
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatDuration(currentPositionMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = prepareError ?: formatDuration(totalDurationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (prepareError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
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
