package h.Hchat.hooks.items.audiotransform

object AudioTransformSettings {
    const val PREFS_NAME = "Hchat_audio_transform_config"
    const val KEY_MODE = "audio_transform_mode"
    const val KEY_LAST_TALKER = "audio_transform_last_talker"
    const val KEY_SPLIT_ENABLED = "audio_transform_split_enabled"
    const val KEY_SPLIT_DURATION_SECONDS = "audio_transform_split_duration_seconds"

    const val MODE_AUDIO_TO_SILK_SAVE = 0
    const val MODE_AUDIO_TO_SILK_SEND = 1
    const val MODE_SILK_TO_MP3_SAVE = 2
    const val MODE_SILK_TO_M4A_SAVE = 3

    const val DEFAULT_MODE = MODE_AUDIO_TO_SILK_SAVE
    const val DEFAULT_SPLIT_ENABLED = false
    const val DEFAULT_SPLIT_DURATION_SECONDS = 60L
    const val MIN_SPLIT_DURATION_SECONDS = 1L
}
