package com.Johnny.wcx.ui.style

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.Johnny.wcx.preferences.WePrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 模块设置界面动效（用户选定的两套：滚动渐显 + 数据生长）。
 *
 * - [fxReveal]：卡片/条目随滚动依次浮现（文字淡入上移，错开 80ms）。LazyColumn 的 item
 *   滚入视口时才组合，因此「首次组合启动动画」天然等于「滚到哪浮现到哪」，零额外监听。
 * - [rememberGrowNumber]：数字从 0 滚动到目标值（配合同文件进度条生长使用）。
 *
 * 总开关存 WePrefs[KEY_UI_FX]，默认开启；设置页「界面」分组有开关行。
 * 所有动画都是纯 Compose 层的（只作用于模块设置自己的页面），不涉及任何微信 Hook。
 */
object AnimFx {
    const val KEY_UI_FX = "ui_anim_fx_enabled"

    /** 动效总开关；开关键不存在时视为开启（默认体验）。 */
    /**
     * 动效恒为开启（用户要求：默认开启、不提供开关）。
     * 不再读取 WePrefs —— 旧版本留下的 ui_anim_fx_enabled=false 会导致动效永久关闭。
     */
    fun enabled(): Boolean = true
}

/**
 * 滚动渐显：首次进入组合时淡入 + 上移归位，[order] 决定错开序（每 80ms 一档）。
 * 用在 LazyColumn 的 item 根修饰符上。
 */
@Composable
fun Modifier.fxReveal(order: Int = 0): Modifier {
    var played by remember { mutableStateOf(false) }
    val alpha = remember { Animatable(if (played) 1f else 0f) }
    val ty = remember { Animatable(if (played) 0f else 26f) }
    LaunchedEffect(Unit) {
        if (played) return@LaunchedEffect
        played = true
        if (!AnimFx.enabled()) {
            alpha.snapTo(1f); ty.snapTo(0f); return@LaunchedEffect
        }
        delay((order % 8) * 80L)
        launch { alpha.animateTo(1f, tween(300, easing = FastOutSlowInEasing)) }
        ty.animateTo(0f, tween(380, easing = FastOutSlowInEasing))
    }
    return this.graphicsLayer {
        this.alpha = alpha.value
        this.translationY = ty.value
    }
}

/**
 * 数据生长：数字/比例从 0 滚到 [target]（0f..1f 或任意数值）。
 * 返回当前动画值，调用方自己格式化（如百分比、计数）。
 */
@Composable
fun rememberGrowNumber(target: Float, durationMs: Int = 700): Float {
    val value = remember { Animatable(0f) }
    LaunchedEffect(target) {
        if (!AnimFx.enabled()) { value.snapTo(target); return@LaunchedEffect }
        value.snapTo(0f)
        value.animateTo(target, tween(durationMs, easing = FastOutSlowInEasing))
    }
    return value.value
}
