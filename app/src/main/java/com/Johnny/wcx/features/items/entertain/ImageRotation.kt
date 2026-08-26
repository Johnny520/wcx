package com.Johnny.wcx.features.items.entertain

import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass

import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature

import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.content.m3.BaseItemContainer
import com.Johnny.wcx.ui.content.m3.IntNumberPickerWidget
import com.Johnny.wcx.ui.content.m3.SegmentedColumn
import com.Johnny.wcx.ui.content.m3.SwitchWidget
import com.Johnny.wcx.ui.utils.showComposeDialog
import java.util.WeakHashMap

@Feature(
    name = "图片旋转",
    categories = ["娱乐"],
    description = "让微信中的图片持续旋转"
)
object ImageRotation : ClickableFeature() {

    private data class RotationState(
        val originalRotation: Float,
        val animator: ObjectAnimator,
    )

    private val viewStateMap = WeakHashMap<View, RotationState>()

    private var onlyAvatars by prefOption("image_rotation_only_avatars", false)
    private var durationMs by prefOption("image_rotation_duration", 1000)

    private const val MIN_DURATION_MS = 100
    private const val MAX_DURATION_MS = 60000

    override fun onEnable() {
        if (onlyAvatars) {
            // Nuke 1.0.2 ChatAvatarRotator 逻辑: 仅挂钩聊天头像
            "com.tencent.mm.ui.chatting.view.ChattingAvatarImageView".toClass().reflekt()
                .firstConstructor().hookAfter {
                    applyRotation(thisObject as View)
                }
        } else {
            // 现有 WeKit 逻辑
            ImageView::class.reflekt()
                .firstConstructor { parameterCount = 4 }.hookAfter {
                    applyRotation(thisObject as View)
                }

            "com.tencent.mm.ui.widget.QImageView".toClass().reflekt()
                .firstConstructor().hookAfter {
                    applyRotation(thisObject as View)
                }
        }
    }

    override fun onDisable() {
        viewStateMap.forEach { (view, state) ->
            state.animator.cancel()
            view.rotation = state.originalRotation
        }
        viewStateMap.clear()
    }

    private fun applyRotation(view: View) {
        view.post {
            if (!isActive || viewStateMap.containsKey(view)) return@post

            val animator = ObjectAnimator.ofFloat(view, "rotation", 0f, 360f).apply {
                duration = durationMs.toLong()
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
            viewStateMap[view] = RotationState(view.rotation, animator)
            animator.start()
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var avatars by remember { mutableStateOf(onlyAvatars) }
            var duration by remember {
                mutableIntStateOf(durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS))
            }
            var dirty by remember { mutableStateOf(false) }

            // 动画时长只在视图创建时读取, 立即写偏好不会刷新已存在的旋转; 对话框关闭时统一重启
            DisposableEffect(Unit) {
                onDispose {
                    if (dirty && isActive) {
                        disable()
                        enable()
                    }
                }
            }

            AlertDialogContent(
                title = { Text("图片旋转") },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = "只对头像生效",
                                checked = avatars,
                                onCheckedChange = {
                                    avatars = it
                                    onlyAvatars = it
                                    dirty = true
                                },
                            )
                        }
                        item {
                            BaseItemContainer {
                                IntNumberPickerWidget(
                                    title = "旋转周期（毫秒）",
                                    value = duration,
                                    startInt = MIN_DURATION_MS,
                                    endInt = MAX_DURATION_MS,
                                    stepSize = 100,
                                    valueSuffix = "ms",
                                    onValueChange = {
                                        duration = it
                                        durationMs = it
                                        dirty = true
                                    },
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                },
            )
        }
    }
}
