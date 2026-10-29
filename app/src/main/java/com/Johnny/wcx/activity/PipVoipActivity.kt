package com.Johnny.wcx.activity

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.ResultReceiver
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.Keep
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Call_end
import com.composables.icons.materialsymbols.outlined.Mic
import com.composables.icons.materialsymbols.outlined.Mic_off
import com.composables.icons.materialsymbols.outlined.Videocam
import com.composables.icons.materialsymbols.outlined.Videocam_off
import com.Johnny.wcx.ui.utils.theme.ModuleTheme

@Keep
class PipVoipActivity : ComponentActivity() {

    private lateinit var receiver: ResultReceiver
    private var groupCall = false
    private var micMuted by mutableStateOf(false)
    private var videoEnabled by mutableStateOf(true)
    private var closing = false

    /**
     * 8.0.80+ 多人通话的「精简模式」。
     *
     * 微信 8.0.80 把多人通话重写成 Flutter 实现（MultiTalkMainUI / MultiTalkUIViewModel 被物理删除），
     * 目前只有 OboeAudioRecorder 的 native 静音方法，语义和句柄都不确定。
     * 与其给用户一排点了没反应（甚至误操作）的按钮，这里只显示「通话中」——
     * 用户点一下 PiP 窗口即可展开并返回通话（系统行为 → onPictureInPictureModeChanged → RESULT_RESTORE）。
     */
    private var limited = false

    /**
     * 是否支持主动控制（静音 / 挂断）。
     *
     * 只有拿到 Flutter VoIP 插件实例（VoIPPluginApi 实现）时才为 true；
     * 此时精简模式也会显示静音/挂断按钮，否则只显示状态，绝不出现点了没反应的按钮。
     */
    private var canControl = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.action == ACTION_CLOSE) {
            finish()
            return
        }

        receiver = IntentCompat.getParcelableExtra(
            intent,
            EXTRA_RESULT_RECEIVER,
            ResultReceiver::class.java,
        )!!
        current = this
        groupCall = intent.getBooleanExtra(EXTRA_GROUP_CALL, false)
        limited = intent.getBooleanExtra(EXTRA_LIMITED, false)
        canControl = intent.getBooleanExtra(EXTRA_CAN_CONTROL, false)
        micMuted = intent.getBooleanExtra(EXTRA_MIC_MUTED, false)
        videoEnabled = intent.getBooleanExtra(EXTRA_VIDEO_ENABLED, true)
        setContent {
            ModuleTheme(darkTheme = true) {
                PipControls()
            }
        }

        enterPictureInPictureMode(updatePipParams())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_CLOSE) {
            closing = true
            finishAndRemoveTask()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode && !closing) {
            closing = true
            receiver.send(RESULT_RESTORE, null)
            finishAndRemoveTask()
        }
    }

    override fun onDestroy() {
        if (::receiver.isInitialized) receiver.send(RESULT_CLOSED, null)
        if (current === this) current = null
        super.onDestroy()
    }

    fun handleAction(action: String) {
        when (action) {
            ACTION_HANG_UP -> {
                closing = true
                receiver.send(RESULT_HANG_UP, null)
                finishAndRemoveTask()
            }

            ACTION_TOGGLE_MIC -> toggleMic()
            ACTION_TOGGLE_VIDEO -> toggleVideo()
        }
    }

    private fun toggleMic() {
        micMuted = !micMuted
        receiver.send(RESULT_TOGGLE_MIC, null)
        updatePipParams()
    }

    private fun toggleVideo() {
        videoEnabled = !videoEnabled
        receiver.send(RESULT_TOGGLE_VIDEO, null)
        updatePipParams()
    }

    private fun updatePipParams(): PictureInPictureParams {
        val actions = mutableListOf<RemoteAction>()
        if (limited && !canControl) {
            // 精简模式：不放任何自定义 action（放了也控制不了，只会误导）
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .setActions(actions)
                .build()
            setPictureInPictureParams(params)
            return params
        }
        actions += listOf(
            remoteAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "挂断",
                ACTION_HANG_UP,
                REQUEST_HANG_UP,
            ),
            remoteAction(
                android.R.drawable.ic_btn_speak_now,
                if (micMuted) "打开麦克风" else "关闭麦克风",
                ACTION_TOGGLE_MIC,
                REQUEST_TOGGLE_MIC,
            ),
        )
        // 视频开关：8.0.80 的 switchCameraOpenStatus 需要一个 Flutter 回调对象做参数，
        // 暂时不接，避免构造错误参数导致通话异常。老版本（非 limited）保持原行为。
        if (groupCall && !limited) {
            actions += remoteAction(
                android.R.drawable.ic_menu_camera,
                if (videoEnabled) "关闭视频" else "打开视频",
                ACTION_TOGGLE_VIDEO,
                REQUEST_TOGGLE_VIDEO,
            )
        }

        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .setActions(actions)
            .build()
        setPictureInPictureParams(params)
        return params
    }

    private fun remoteAction(icon: Int, title: String, action: String, requestCode: Int): RemoteAction {
        val intent = Intent(this, PipVoipActionReceiver::class.java).setAction(action)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(this, icon), title, title, pendingIntent)
    }

    @Composable
    private fun PipControls() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF151515)),
            contentAlignment = Alignment.Center,
        ) {
            if (limited && !canControl) {
                Text(
                    // 如实反映通话状态；麦克风状态来自 OboeAudioRecorder.g(Z)V 的只读同步
                    text = if (micMuted) "通话中 · 已静音" else "通话中",
                    color = Color.White,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = {
                        closing = true
                        receiver.send(RESULT_HANG_UP, null)
                        finishAndRemoveTask()
                    },
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(MaterialSymbols.Outlined.Call_end, contentDescription = "挂断")
                }
                FilledIconButton(onClick = ::toggleMic, modifier = Modifier.size(48.dp)) {
                    Icon(
                        if (micMuted) MaterialSymbols.Outlined.Mic else MaterialSymbols.Outlined.Mic_off,
                        contentDescription = if (micMuted) "打开麦克风" else "关闭麦克风",
                    )
                }
                if (groupCall) {
                    FilledIconButton(onClick = ::toggleVideo, modifier = Modifier.size(48.dp)) {
                        Icon(
                            if (videoEnabled) MaterialSymbols.Outlined.Videocam_off else MaterialSymbols.Outlined.Videocam,
                            contentDescription = if (videoEnabled) "关闭视频" else "打开视频",
                        )
                    }
                }
            }
            }
        }
    }

    companion object {
        const val EXTRA_RESULT_RECEIVER = "pip_voip_result_receiver"
        const val EXTRA_GROUP_CALL = "pip_voip_group_call"
        const val EXTRA_MIC_MUTED = "pip_voip_mic_muted"
        const val EXTRA_VIDEO_ENABLED = "pip_voip_video_enabled"

        /** 精简模式：只显示「通话中」，不提供静音/摄像头/挂断（8.0.80+ 多人通话暂无可控入口）。 */
        const val EXTRA_LIMITED = "pip_voip_limited"

        /** 是否支持主动控制（拿到 Flutter VoIP 插件实例时为 true）。 */
        const val EXTRA_CAN_CONTROL = "pip_voip_can_control"

        const val ACTION_CLOSE = "com.Johnny.wcx.action.CLOSE_VOIP_PIP"
        private const val ACTION_HANG_UP = "com.Johnny.wcx.action.HANG_UP_VOIP"
        private const val ACTION_TOGGLE_MIC = "com.Johnny.wcx.action.TOGGLE_VOIP_MIC"
        private const val ACTION_TOGGLE_VIDEO = "com.Johnny.wcx.action.TOGGLE_VOIP_VIDEO"

        const val RESULT_HANG_UP = 1
        const val RESULT_TOGGLE_MIC = 2
        const val RESULT_TOGGLE_VIDEO = 3
        const val RESULT_RESTORE = 4
        const val RESULT_CLOSED = 5

        private const val REQUEST_HANG_UP = 1
        private const val REQUEST_TOGGLE_MIC = 2
        private const val REQUEST_TOGGLE_VIDEO = 3

        internal var current: PipVoipActivity? = null
    }
}

class PipVoipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        PipVoipActivity.current?.handleAction(action)
    }
}
