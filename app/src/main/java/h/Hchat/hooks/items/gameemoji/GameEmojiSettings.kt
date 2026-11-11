package h.Hchat.hooks.items.gameemoji

import android.content.Context
import h.Hchat.preferences.HchatStorage

object GameEmojiSettings {
    const val PREFS_NAME = "Hchat_game_emoji_config"

    const val KEY_FIXED_RESULT = "game_emoji_fixed_result"
    const val KEY_PICK_BEFORE_SEND = "game_emoji_pick_before_send"
    const val KEY_DICE_RESULT = "game_emoji_dice_result"
    const val KEY_RPS_RESULT = "game_emoji_rps_result"

    const val DEFAULT_FIXED_RESULT = false
    const val DEFAULT_PICK_BEFORE_SEND = false
    const val DEFAULT_DICE_RESULT = 1
    const val DEFAULT_RPS_RESULT = 1

    const val RPS_SCISSORS = 1
    const val RPS_ROCK = 2
    const val RPS_PAPER = 3

    fun fixedResultEnabled(context: Context): Boolean = preferences(context)
        .getBoolean(KEY_FIXED_RESULT, DEFAULT_FIXED_RESULT)

    fun pickBeforeSendEnabled(context: Context): Boolean = preferences(context)
        .getBoolean(KEY_PICK_BEFORE_SEND, DEFAULT_PICK_BEFORE_SEND)

    fun diceResult(context: Context): Int = preferences(context)
        .getInt(KEY_DICE_RESULT, DEFAULT_DICE_RESULT)
        .coerceIn(1, 6)

    fun rpsResult(context: Context): Int = preferences(context)
        .getInt(KEY_RPS_RESULT, DEFAULT_RPS_RESULT)
        .coerceIn(RPS_SCISSORS, RPS_PAPER)

    fun rpsLabel(result: Int): String = when (result) {
        RPS_ROCK -> "石头"
        RPS_PAPER -> "布"
        else -> "剪刀"
    }

    private fun preferences(context: Context) = HchatStorage.preferences(context, PREFS_NAME)
}
