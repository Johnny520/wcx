package h.Hchat.hooks.items.fakevoiceduration

object FakeVoiceDurationSettings {
    const val PREFS_NAME = "Hchat_fake_voice_duration_config"
    const val KEY_ENABLE = "fake_voice_duration_enable"
    const val KEY_DURATION_SECONDS = "fake_voice_duration_seconds"
    const val DEFAULT_ENABLE = false
    const val DEFAULT_DURATION_SECONDS = 5
    const val MIN_DURATION_SECONDS = 1
    const val MAX_DURATION_SECONDS = 60
}
