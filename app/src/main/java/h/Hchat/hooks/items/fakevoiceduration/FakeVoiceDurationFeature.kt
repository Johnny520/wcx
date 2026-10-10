package h.Hchat.hooks.items.fakevoiceduration

import android.content.Context

// TODO(migration stub): 原实现是完整 Feature；此处仅保留被 WeChatVoiceApi 调用的静态成员。
object FakeVoiceDurationFeature {
    const val ID = "fake_voice_duration"

    @JvmStatic
    fun isEnabled(context: Context?): Boolean = false

    @JvmStatic
    fun durationMillis(context: Context?): Int = 0
}
