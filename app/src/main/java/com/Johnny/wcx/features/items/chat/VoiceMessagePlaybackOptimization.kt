package com.Johnny.wcx.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexClass
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature

import com.Johnny.wcx.features.items.chat.VoiceMessagePlaybackOptimization.MODE_DISABLE
import com.Johnny.wcx.features.items.chat.VoiceMessagePlaybackOptimization.MODE_INHERIT_PROGRESS
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.content.m3.RadioButtonWidget
import com.Johnny.wcx.ui.content.m3.SegmentedColumn
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.reflection.bool

/**
 * 语音消息播放逻辑优化。
 *
 * 微信在聊天语音播放时通过距离传感器自动切换听筒/扬声器, 其完整链路 (8.0.65-8.0.76):
 * - AutoPlay 控制器 (`com.tencent.mm.ui.chatting` 包内, 类名各版本不同:
 *   8.0.65 x0 / 8.0.67-69 y0 / 8.0.74-76 v0) 持有 SensorController;
 * - 传感器回调方法 (8.0.65 x1 / 8.0.67 s1 / 8.0.69 x1 / 8.0.74 f1 / 8.0.76 c1,
 *   参数 boolean, false=贴近耳朵 true=离开耳朵) 收到事件后延迟 50ms 调度切换任务;
 * - 切换任务类 (8.0.65 e1 / 8.0.67-69 f1 / 8.0.74-76 c1) 的 `onTimerExpired()` 执行真正切换:
 *   - 离开耳朵: setSpeakerOn(true) + switchSpeaker(), 若 RepairerConfigVoiceSpeakerSeek
 *     开启再回退 2 秒;
 *   - 贴近耳朵: setSpeakerOn(false), 若 VoiceSpeakerSeek 开启则 switchSpeaker() + 回退 2 秒,
 *     否则重新 `start play` 从头播放 — 这就是「切换后从头重播」的来源。
 *
 * 本功能两个模式:
 * - [MODE_DISABLE]: 直接拦截传感器回调, 完全禁用自动切换 (贴近/离开都不再切);
 * - [MODE_INHERIT_PROGRESS]: 仍自动切换, 但只执行 setSpeakerOn + switchSpeaker
 *   (switchSpeaker 内部对 MediaPlayer 保存 currentPosition 后 seekTo, Speex/Silk 仅重建
 *   AudioTrack 流类型), 跳过从头重播与 2 秒回退, 播放进度原样继承。
 *
 * 所有锚点均为跨版本稳定字符串, 按项目约定不加 allowFailure。
 */
@Feature(
    name = "语音消息播放逻辑优化",
    categories = ["聊天"],
    description = "优化语音消息听筒/扬声器自动切换: 完全禁用自动切换, 或切换后继承播放进度不再从头重播"
)
object VoiceMessagePlaybackOptimization : ClickableFeature(), IResolveDex {

    private const val TAG = "VoicePlaybackOptimization"

    /** 完全禁用自动切换 */
    private const val MODE_DISABLE = 0

    /** 切换后继承播放进度 */
    private const val MODE_INHERIT_PROGRESS = 1

    private var playbackMode by prefOption("voice_playback_mode", MODE_INHERIT_PROGRESS)

    /**
     * AutoPlay 语音播放控制器: 日志锚点 `MicroMsg.AutoPlay` + 传感器回调内的
     * `onSensorEvent, isON:` 组合定位, 各版本唯一。
     */
    private val classAutoPlay by dexClass {
        matcher {
            usingEqStrings("MicroMsg.AutoPlay", "onSensorEvent, isON:")
        }
    }

    /**
     * AutoPlay 的距离传感器回调 (boolean: false=贴近耳朵 true=离开耳朵)。
     * 方法名各版本不同, 用方法体内日志常量 `onSensorEvent, isON:` 锚定。
     */
    private val methodSensorCallback by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.AutoPlay", "onSensorEvent, isON:")
            }
            usingEqStrings("onSensorEvent, isON:")
            paramTypes(bool)
            returnType("void")
        }
    }

    /** 切换任务入口 `onTimerExpired()`: 无参 boolean 返回, 方法名跨版本稳定。 */
    private val methodSwitchTask by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("speaker off")
            }
            paramCount = 0
            returnType("boolean")
        }
    }

    /**
     * AutoPlay.setSpeakerOn(boolean)：8.0.65-74 = G / 8.0.76 = H / 8.0.80 = x0.G。
     *
     * 8.0.80 实测：AutoPlay 的两条老日志串
     * `speakerOn has been set %s` 与 `switchSpeaker, isSpeakerOn: %b, isPlaying: %b`
     * 都只剩常量池条目、无任何方法引用（孤儿串），原锚点必然 0 命中。
     * 同时 `MicroMsg.AutoPlay` 这个 TAG 在 8.0.80 被 20+ 个聊天页类共用，
     * 单独拿它做 declaredClass 会多命中 —— 改用同类独有串
     * `isHeadsetPlugged: %b, isBluetoothOn: %b` 收窄到真正的 AutoPlay（x0）。
     * 类内 boolean 形参的方法有 G/d/F/J 四个，用「调 platformtools.Log.c」区分出 G
     * （d 走 xlog.Log.w 且调 onStop，J 带 keep_app_silent，F 完全不打日志）。
     */
    private val methodSetSpeakerOn by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.AutoPlay", "isHeadsetPlugged: %b, isBluetoothOn: %b")
            }
            usingEqStrings("MicroMsg.AutoPlay")
            paramTypes(bool)
            returnType("void")
            addInvoke {
                declaredClass = "com.tencent.mm.sdk.platformtools.Log"
                name = "c"
            }
        }
    }

    /**
     * AutoPlay.switchSpeaker()：8.0.65-74 = K / 8.0.76 = L / 8.0.80 = x0.K。
     * 无参 void 方法，方法体先取 d1.isPlaying() 再调 d1.g(Z) 完成切换 —— 用后者定位。
     */
    private val methodSwitchSpeaker by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.AutoPlay", "isHeadsetPlugged: %b, isBluetoothOn: %b")
            }
            paramCount = 0
            returnType("void")
            addInvoke {
                declaredClass = "com.tencent.mm.modelbase.d1"
                name = "g"
            }
        }
    }

    override fun onEnable() {
        // 完全禁用: 传感器事件直接丢弃, AutoPlay 不会进入任何切换逻辑。
        methodSensorCallback.hookBefore {
            if (playbackMode == MODE_DISABLE) result = null
        }

        // 切换任务: 禁用模式下兜底丢弃; 继承进度模式下替换为「仅切换 + 保留进度」。
        methodSwitchTask.hookBefore {
            when (playbackMode) {
                MODE_DISABLE -> result = false
                MODE_INHERIT_PROGRESS -> {
                    // 锚点缺失（微信改版）时保持原生切换行为 —— 直接调 placeholder 会抛异常，
                    // 而这里在聊天页播放语音的热路径上，抛出去就是播放中断。
                    if (methodSetSpeakerOn.isPlaceholder || methodSwitchSpeaker.isPlaceholder) {
                        WeLogger.w(TAG, "skip inherit-progress: speaker anchors not resolved in this WeChat version")
                        return@hookBefore
                    }
                    val task = thisObject!!
                    val autoPlay = task.reflekt()
                        .firstField { type = classAutoPlay.clazz }
                        .get()
                    val switchingToEarpiece = task.reflekt()
                        .firstField { type = bool }
                        .get() as Boolean
                    methodSetSpeakerOn.method.invoke(autoPlay, switchingToEarpiece)
                    methodSwitchSpeaker.method.invoke(autoPlay)
                    result = false
                }
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var mode by remember { mutableIntStateOf(playbackMode) }

            AlertDialogContent(
                title = { Text("语音消息播放逻辑优化") },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = "切换后继承播放进度",
                                description = "自动切换继续生效，切换后从原进度继续播放",
                                selected = mode == MODE_INHERIT_PROGRESS,
                                onClick = {
                                    mode = MODE_INHERIT_PROGRESS
                                    playbackMode = MODE_INHERIT_PROGRESS
                                },
                            )
                        }
                        item {
                            RadioButtonWidget(
                                iconPlaceholder = false,
                                title = "完全禁用自动切换",
                                description = "贴近/离开耳朵均不自动切换听筒与扬声器",
                                selected = mode == MODE_DISABLE,
                                onClick = {
                                    mode = MODE_DISABLE
                                    playbackMode = MODE_DISABLE
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("关闭") }
                },
            )
        }
    }
}
