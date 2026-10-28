package com.Johnny.wcx.features.items.voip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import de.robv.android.xposed.XC_MethodHook
import org.luckypray.dexkit.query.enums.StringMatchType

@Feature(name = "屏蔽铃声", categories = ["聊天", "音视频通话"], description = "屏蔽音视频通话铃声")
object BlockVoipRingtone : ClickableFeature(), IResolveDex {

    // 默认关闭：这两条 hook 会跳过微信 VoIP 自己的铃声播放流程，少数机型上会让通话
    // 状态机等不到铃声回调而卡死，所以必须由用户主动开启。
    private var disableOutCall by prefOption("voip_disable_ringtone_out_call", false)
    private var disableInCall by prefOption("voip_disable_ringtone_in_call", false)

    // =====================================================================
    // WeChat 8.0.76+（VoIPMP / Flutter 化）三条播放路径
    // =====================================================================

    // 路径 1：startRing 发送端（nq5/e.a = MicroMsg.VoIPMPRingtoneController）
    // 唯一日志对：MicroMsg.VoIPMPRingtoneController + "startRing() called with: username = "
    private val methodStartRing by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.VoIPMPRingtoneController", "startRing() called with: username = ")
        }
    }

    // 路径 2：scene 分发接收端（py3/u.kj = 8.0.76 的新 BaseSceneSetting，返回 boolean）
    // 与旧版 playSound 相同的日志对，兼容旧版 void playSound
    private val methodSceneSetting by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.BaseSceneSetting", "playSound Failed Throwable t = ")
        }
    }

    // 路径 3：呼入铃声直接播放（voipmp platform 的 ZIDL_HBV(J)V）
    // 只取第一个命中结果，避免 resultIndex 越界导致 Dex 扫描崩溃
    private val methodIncomingRing by dexMethod(allowMultiple = true, allowFailure = true) {
        matcher {
            name = "ZIDL_HBV"
            declaredClass("com.tencent.mm.plugin.voipmp", StringMatchType.Contains, false)
            paramTypes("long")
            returnType = "void"
        }
    }

    // =====================================================================
    // 旧版兜底链（8.0.75 及更早的 playSound）
    // =====================================================================
    // 8.0.76 实测该日志串命中 3 个方法（同名/同串的多个播放入口）。
    // 允许多命中不报错；使用时把**每个**候选都 hook —— 它们都是「按日志串定位的播放入口」。
    private val methodPlaySoundByLog by dexMethod(allowMultiple = true, allowFailure = true) {
        matcher {
            usingEqStrings("playSound Failed Throwable t = ")
        }
    }

    private val methodPlaySoundByName by dexMethod(allowFailure = true) {
        matcher {
            name = "playSound"
        }
    }

    private const val TAG = "BlockVoipRingtone"

    override fun onEnable() {
        var hooked = false

        if (!methodStartRing.isPlaceholder) {
            methodStartRing.hookBefore { tryBlockStartRing(this) }
            hooked = true
            WeLogger.i(TAG, "hook enabled: startRing (VoIPMPRingtoneController)")
        }

        if (!methodSceneSetting.isPlaceholder) {
            methodSceneSetting.hookBefore { tryBlockRingtone(this) }
            hooked = true
            WeLogger.i(TAG, "hook enabled: sceneSetting (BaseSceneSetting)")
        } else if (!methodPlaySoundByLog.isPlaceholder) {
            WeLogger.w(TAG, "sceneSetting strict match failed, using log-string fallback")
            // 多候选：把命中的每个入口都挂上（只挂第一个会漏掉另外两个播放路径）
            val candidates = runCatching { methodPlaySoundByLog.candidateMethods() }
                .getOrDefault(emptyList())
                .ifEmpty { runCatching { listOf(methodPlaySoundByLog.method) }.getOrDefault(emptyList()) }
            var fallbackHooked = 0
            val hookedIds = mutableListOf<String>()
            for (m in candidates) {
                runCatching { m.hookBefore { tryBlockRingtone(this) } }
                    .onSuccess {
                        fallbackHooked++
                        hookedIds += "${m.declaringClass?.name}#${m.name}"
                    }
                    .onFailure { WeLogger.e(TAG, "log-string fallback hook 失败（${m.declaringClass?.name}#${m.name}）", it) }
            }
            if (fallbackHooked > 0) {
                hooked = true
                WeLogger.i(TAG, "log-string fallback 已 hook $fallbackHooked/${candidates.size} 个候选：${hookedIds.joinToString("、")}")
            } else {
                WeLogger.w(TAG, "log-string fallback 无可用候选（候选数=${candidates.size}），未 hook")
            }
        }

        if (!methodIncomingRing.isPlaceholder) {
            methodIncomingRing.hookBefore { tryBlockIncomingRing(this) }
            hooked = true
            WeLogger.i(TAG, "hook enabled: incoming ring player (ZIDL_HBV)")
        }

        if (!hooked) {
            WeLogger.w(TAG, "no ringtone hook available on this WeChat version")
        }
    }

    /** 只有原方法返回 void 时才能用 setResult 跳过，否则会破坏调用方拿到的返回值。 */
    private fun canSkip(param: XC_MethodHook.MethodHookParam): Boolean {
        val m = param.method as? java.lang.reflect.Method ?: return false
        return m.returnType == Void.TYPE
    }

    private fun tryBlockRingtone(param: XC_MethodHook.MethodHookParam) {
        try {
            val bundle = (param.args.getOrNull(1) as? Bundle) ?: (param.args.getOrNull(0) as? Bundle) ?: return
            // 之前这里写成 val scene = "scene"（常量），判断永远不成立，等于这条路径从来没生效过。
            val scene = bundle.getString("scene") ?: return
            if (!scene.equals("start", ignoreCase = true)) return
            if (!canSkip(param)) {
                WeLogger.w(TAG, "skip blocked: ${param.method.name} is not void, 不拦截以免破坏返回值")
                return
            }
            val isOutCall = bundle.getBoolean("isOutCall")
            val disOutCall = isOutCall && disableOutCall
            val disInCall = !isOutCall && disableInCall
            if (disOutCall || disInCall) {
                // 8.0.76 的 kj 返回 boolean；旧版 playSound 为 void。
                // 无论哪种返回类型，置 result 都会跳过原方法体，从而不播放铃声。
                param.result = null
                WeLogger.d(TAG, "blocked scene=start, isOutCall=$isOutCall, method=${param.method.name}")
            }
        } catch (e: Throwable) {
            WeLogger.w(TAG, "tryBlockRingtone error: ${e.message}")
        }
    }

    private fun tryBlockStartRing(param: XC_MethodHook.MethodHookParam) {
        try {
            val isOutCall = param.args.getOrNull(2) as? Boolean ?: return
            val disOutCall = isOutCall && disableOutCall
            val disInCall = !isOutCall && disableInCall
            if ((disOutCall || disInCall) && canSkip(param)) {
                // void 方法只能置 null；之前写 true 会被 Xposed 判定「返回值类型不匹配」，
                // 直接抛 ClassCastException 把微信搞崩。
                param.result = null
                WeLogger.d(TAG, "blocked startRing, isOutCall=$isOutCall")
            }
        } catch (e: Throwable) {
            WeLogger.w(TAG, "tryBlockStartRing error: ${e.message}")
        }
    }

    private fun tryBlockIncomingRing(param: XC_MethodHook.MethodHookParam) {
        if (disableInCall && canSkip(param)) {
            param.result = null // void 方法必须置 null，否则触发返回值类型校验异常
            WeLogger.d(TAG, "blocked incoming ring, method=${param.method.name}")
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var outCall by remember { mutableStateOf(disableOutCall) }
            var inCall by remember { mutableStateOf(disableInCall) }

            AlertDialogContent(
                title = { Text("屏蔽铃声") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable { outCall = !outCall },
                            trailingContent = { Switch(checked = outCall, onCheckedChange = { outCall = it }) },
                            supportingContent = { Text("屏蔽拨出音视频通话时的铃声") },
                            headlineContent = { Text("屏蔽呼出铃声") },
                        )
                        ListItem(
                            modifier = Modifier.clickable { inCall = !inCall },
                            trailingContent = { Switch(checked = inCall, onCheckedChange = { inCall = it }) },
                            supportingContent = { Text("屏蔽收到音视频通话请求时的铃声") },
                            headlineContent = { Text("屏蔽呼入铃声") },
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        disableOutCall = outCall
                        disableInCall = inCall
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }
}
