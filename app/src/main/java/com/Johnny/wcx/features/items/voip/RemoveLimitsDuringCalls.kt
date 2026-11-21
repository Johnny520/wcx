package com.Johnny.wcx.features.items.voip

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.DexMethodDelegate
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import java.lang.reflect.Method

/**
 * 移除通话时聊天限制：把所有「设备占用检查」方法钉成 false。
 *
 * 旧实现用 `invoke 了 MultiTalkActionEvent 的静态无参 boolean 方法` 当作锚点，
 * 再拿它的 declaringClass 去约束其余 11 个方法。微信 8.0.77 起多人通话模块被移除，
 * 8.0.79 的 dex 里已经没有任何方法 invoke MultiTalkActionEvent（实测 classes15.dex），
 * 锚点匹配失败 → 其余方法全部拿不到类 → 功能整体静默失效。
 *
 * 现在改成每个方法各自按「日志字符串 + 参数个数」独立定位（8.0.79 实测全部落在
 * com.tencent.mm.voipmp.helper.DeviceOccupyHelper，混淆名 Lpq/b;），不再依赖任何锚点。
 * 微信改类名/挪包都不影响，只有日志串变化才会失效——失效时日志会明确写出来。
 */
@Feature(name = "移除通话时聊天限制", categories = ["聊天", "音视频通话"], description = "绕过正在通话时聊天限制")
object RemoveLimitsDuringCalls : SwitchFeature(), IResolveDex {

    private const val TAG = "RemoveLimitsDuringCalls"
    private const val OC = "MicroMsg.DeviceOccupy"

    /** 微信 8.0.80 起从 DeviceOccupyHelper 彻底删除的三个占用检查（串已不存在）。 */
    private val REMOVED_IN_8_0_80 = listOf("checkDeviceUsing", "checkAudioDeviceUsing", "checkSpeakerUsing")

    override fun onEnable() {
        var hooked = 0
        val skipped = mutableListOf<String>()

        targets().forEach { (label, delegate) ->
            // 多候选兼容：命中多个时**每个 boolean 候选都 hook** —— 它们都是「是否占用」的
            // 谓词，只 hook 取到的第一个会在 8.0.76 这类版本上漏掉另一个重载。
            // candidates 为空（走了缓存、或 DexKit 未跑）时退回单个 method。
            val methods = runCatching { delegate.candidateMethods() }.getOrDefault(emptyList())
                .ifEmpty { runCatching { listOf(delegate.method) }.getOrDefault(emptyList()) }
            if (methods.isEmpty() || delegate.isPlaceholder) {
                skipped += label
                return@forEach
            }
            var hookedForLabel = 0
            val seen = mutableSetOf<String>()
            for (method in methods) {
                // 只拦返回 boolean 的：钉成 false 就等于「没有占用」，其它类型一律不碰
                if (method.returnType != Boolean::class.javaPrimitiveType &&
                    method.returnType != java.lang.Boolean::class.java
                ) {
                    skipped += "$label(${method.returnType?.simpleName})"
                    continue
                }
                val id = "${method.declaringClass?.name}#${method.name}"
                if (!seen.add(id)) continue
                runCatching {
                    method.hookBefore { result = false }
                }.onSuccess { hookedForLabel++ }
                    .onFailure { WeLogger.e(TAG, "$label hook 失败（$id）", it) }
            }
            if (hookedForLabel > 0) {
                hooked += hookedForLabel
                WeLogger.i(
                    TAG,
                    "$label 已 hook ${hookedForLabel}/${methods.size} 个候选：${seen.joinToString("、")}"
                )
            } else {
                skipped += label
            }
        }

        WeLogger.i(TAG, "通话限制移除：已 hook $hooked 个；跳过 ${skipped.size} 个${if (skipped.isEmpty()) "" else " -> $skipped"}")
        if (hooked == 0) {
            WeLogger.w(TAG, "一个都没 hook 上，功能不会生效（微信可能改了这个类的日志串）")
        }
        // 微信 8.0.80 起这三项的特征串被整条删除（string_ids 里都不存在，不是孤儿串），
        // 无法重描锚点；这里显式告警，避免用户以为"开了没效果"是模块 bug。
        val removedIn8080 = skipped.filter { label ->
            REMOVED_IN_8_0_80.any { label.startsWith(it) }
        }
        if (removedIn8080.isNotEmpty()) {
            WeLogger.w(
                TAG,
                "微信 8.0.80 已删除 ${removedIn8080.joinToString("、")}（DeviceOccupyHelper 里这三条日志串在 dex " +
                        "string_ids 中完全不存在，其余 ${targets().size - removedIn8080.size} 项不受影响，正常生效）"
            )
        }
    }

    /**
     * 目标方法清单。字符串取自微信 8.0.79 实测（classes15.dex → DeviceOccupyHelper，
     * 8.0.80 = `com.tencent.mm.voipmp.helper.DeviceOccupyHelper` / `Lsq/b;`）。
     * 参数个数一并作为约束，避免同一个串命中同族的另一个重载。
     *
     * 8.0.80 复核：`isCameraUsing`、`checkAppBrand*Using*` 这些串仍活着（`Lsq/b.h` / `Lsq/b.i` /
     * `Lsq/b.a/c/d/e/f`），但最后三项（[methodCheckDeviceUsing] / [methodCheckAudioDeviceUsing] /
     * [methodCheckSpeakerUsing]）的串已被微信整条删除，属于**真失效**，只能 allowFailure 降级
     * —— 详见各委托的注释与 onEnable 里的告警日志。
     */
    private fun targets(): List<Pair<String, DexMethodDelegate>> = listOf(
        "isMultiTalking(1)" to methodIsMultiTalking1,
        "isMultiTalking(2)" to methodIsMultiTalking2,
        "isCameraUsing" to methodIsCameraUsing,
        "isVoiceUsing(1)" to methodIsVoiceUsing1,
        "isVoiceUsing(2)" to methodIsVoiceUsing2,
        "checkAppBrandVoiceUsing" to methodCheckAppBrandVoiceUsing,
        "checkAppBrandVoiceUsingAndShowToast" to methodCheckAppBrandVoiceUsing2,
        "checkAppBrandCameraUsing" to methodCheckAppBrandCameraUsing,
        "checkAppBrandCameraUsingAndShowToast" to methodCheckAppBrandCameraUsing2,
        // 老版本微信（8.0.7x 早期）才有的几个占用检查，新版本已删除，允许失败。
        "checkDeviceUsing" to methodCheckDeviceUsing,
        "checkAudioDeviceUsing" to methodCheckAudioDeviceUsing,
        "checkSpeakerUsing" to methodCheckSpeakerUsing,
    )

    private val methodIsMultiTalking1 by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(OC, "isMultiTalking")
            paramCount = 1
            returnType = "boolean"
        }
    }

    private val methodIsMultiTalking2 by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(OC, "isMultiTalking")
            paramCount = 2
            returnType = "boolean"
        }
    }

    // 8.0.76 实测该特征命中 2 个重载（do：isCameraUsing 多签名）。
    // 这里允许「多命中不报错」，实际由 onEnable 对**每个** boolean 候选都 hook（见下）。
    private val methodIsCameraUsing by dexMethod(allowMultiple = true, allowFailure = true) {
        matcher {
            usingEqStrings(OC, "isCameraUsing")
            returnType = "boolean"
        }
    }

    private val methodIsVoiceUsing1 by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(OC, "isVoiceUsing")
            paramCount = 1
            returnType = "boolean"
        }
    }

    private val methodIsVoiceUsing2 by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(OC, "isVoiceUsing")
            paramCount = 2
            returnType = "boolean"
        }
    }

    private val methodCheckAppBrandVoiceUsing by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(OC, "checkAppBrandVoiceUsing isVoiceUsing:%b, isCameraUsing:%b")
            returnType = "boolean"
        }
    }

    private val methodCheckAppBrandVoiceUsing2 by dexMethod(allowMultiple = true, allowFailure = true) {
        matcher {
            usingEqStrings(OC, "checkAppBrandVoiceUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b")
            returnType = "boolean"
        }
    }

    private val methodCheckAppBrandCameraUsing by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(OC, "checkAppBrandCameraUsing isVoiceUsing:%b, isCameraUsing:%b")
            returnType = "boolean"
        }
    }

    private val methodCheckAppBrandCameraUsing2 by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(OC, "checkAppBrandCameraUsingAndShowToast isVoiceUsing:%b, isCameraUsing:%b")
            returnType = "boolean"
        }
    }

    // ── 8.0.80 已无法定位的三项（真失效，保留 matcher 只为 ≤8.0.79 兼容）───────────────────
    //
    // 判定依据（8.0.80 = 0x28005032，18 个 dex 全量扫描）：
    //   * `checkDeviceUsing` / `checkAudioDeviceUsing` / `checkSpeakerUsing` 三串在 string_ids 里
    //     **完全不存在**（连"孤儿串"都不是），子串搜索同样 0 命中；
    //   * 承载类还在：`com.tencent.mm.voipmp.helper.DeviceOccupyHelper`（`Lsq/b;`，34 个方法），
    //     说明不是类改名而是这三条日志被 R8 删掉了；
    //   * 同类的其它占用检查（isCameraUsing / checkAppBrandVoiceUsing / checkAppBrandCameraUsing /
    //     checkTingChatRoomStateExist / checkFinderLive* / checkMutePlay）串都还在，抓得到 ⇒ 失效只限这三项。
    //
    // 为什么不用「TAG + 签名」兜底：`Lsq/b` 里只剩 `MicroMsg.DeviceOccupy` 一个 TAG 的 boolean
    // 无参方法有 `p()` / `r()` / `A()` 三个（还有 `y()` = checkMutePlay），彼此没有任何可判别特征，
    // 猜错就会把某个正常谓词钉成 false（比如把「正在多通话」判成 false），风险远大于收益。
    //
    // 兜底：allowFailure=true → 占位符 → targets() 里跳过，并在 onEnable 末尾打一条专门的 W 日志。
    private val methodCheckDeviceUsing by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(OC, "checkDeviceUsing")
            returnType = "boolean"
        }
    }

    private val methodCheckAudioDeviceUsing by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(OC, "checkAudioDeviceUsing")
            returnType = "boolean"
        }
    }

    private val methodCheckSpeakerUsing by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(OC, "checkSpeakerUsing")
            returnType = "boolean"
        }
    }
}
