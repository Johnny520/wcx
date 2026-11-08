package h.Hchat.hooks.items.textvoice

import android.content.Context
import h.Hchat.preferences.HchatStorage
import kotlin.math.roundToInt

object TextVoiceSettings {
    const val PREFS_NAME = "Hchat_text_voice_config"
    const val KEY_SEND_ENABLE = "text_voice_send_enable"
    const val KEY_PLAY_ENABLE = "text_voice_play_enable"
    const val KEY_ENGINE = "text_voice_engine"
    const val KEY_VOICE = "text_voice_voice"
    const val KEY_TTS_VOICE = "text_voice_tts_voice"
    const val KEY_SPEECH_RATE = "text_voice_speech_rate"

    const val DEFAULT_SEND_ENABLE = false
    const val DEFAULT_PLAY_ENABLE = false
    const val ENGINE_ONLINE = "online"
    const val ENGINE_TTS_PREFIX = "tts:"
    const val DEFAULT_ENGINE = ENGINE_ONLINE
    const val DEFAULT_VOICE = "1:shigeju"
    const val DEFAULT_TTS_VOICE = ""
    const val ENGLISH_VOICE_ID = "v50"
    const val DEFAULT_SPEECH_RATE = 1.0f
    const val MIN_SPEECH_RATE = 0.1f
    const val MAX_SPEECH_RATE = 3.0f

    data class VoiceOption(
        val key: String,
        val label: String,
        val voiceId: String
    )

    val voiceOptions: List<VoiceOption> = listOf(
        voice(1, "shigeju", "慢波"),
        voice(2, "dianzijianchen", "电子奸臣"),
        voice(3, "zhangfei-guichu", "激昂张飞"),
        voice(4, "maikease", "麦克阿瑟"),
        voice(5, "jixiedianjing", "电竞解说"),
        voice(6, "shaweima", "沙老板"),
        voice(7, "dingzhen", "理唐小子"),
        voice(8, "fanzhiyi-guichu", "大将锐评"),
        voice(9, "sunxiaochuan", "游戏解说"),
        voice(10, "wangdachui", "锤大力"),
        voice(11, "xianyumengxiangjia-guichu", "梦想家"),
        voice(12, "jixiezhanjing", "机甲战警"),
        voice(13, "tixunan", "体虚生"),
        voice(14, "heyboy", "说唱小哥"),
        voice(15, "xiaomeng", "萌琦"),
        voice(16, "xionger", "熊熊"),
        voice(17, "ziwei", "紫薇"),
        voice(18, "houge", "猴哥"),
        voice(19, "haixing", "海星"),
        voice(20, "guanyu-guichu", "豪迈二爷"),
        voice(21, "caocaogaifan-guichu", "愤怒阿瞒"),
        voice(22, "zhugeliang-guichu", "智谋丞相"),
        voice(23, "chunribu", "春日部"),
        voice(24, "laodie", "魔法老爹"),
        voice(25, "guangxige", "洗头男"),
        voice(26, "haibao", "海星"),
        voice(27, "kenanvc", "名侦探"),
        voice(28, "luxun", "树人"),
        voice(29, "zhubo", "青年主播"),
        voice(30, "diyinpao", "低音炮"),
        voice(31, "jieshuonannew", "解说男生"),
        voice(32, "jieshuonv", "解说女声"),
        voice(33, "huayuanbaobao", "治愈男生"),
        voice(34, "bage", "娱乐扒哥"),
        voice(35, "bamei", "娱乐扒妹"),
        voice(36, "meishi", "舌尖美食"),
        voice(37, "yizhi", "抑制腔"),
        voice(38, "xiaoxin", "萌小孩"),
        voice(39, "zhengtai", "元气正太"),
        voice(40, "daimeng", "小鬼头"),
        voice(41, "nvhai", "超萌奶娃"),
        voice(42, "db6", "知性女声"),
        voice(43, "wenrounvsheng", "温柔女声"),
        voice(44, "tvbfemale", "TVB女"),
        voice(45, "xindong", "元气少女"),
        voice(46, "liyuling", "玉玲"),
        voice(47, "xiaoxiao", "清仓促销员"),
        voice(48, "xiaoyao", "热血男孩"),
        voice(49, "qingsong", "轻松少年"),
        voice(50, "db8", "森系少年"),
        voice(51, "jixueguanggao", "鸡血广告"),
        voice(52, "tianjinhua", "天津话"),
        voice(53, "xiaopo", "说书先生"),
        voice(54, "zh-CN-shaanxi-XiaoniNeural", "陕西话"),
        voice(55, "zh-HK-WanLungNeural", "粤语男声"),
        voice(56, "zh-CN-henan-YundengNeural", "河南话"),
        voice(57, "v50", "英文男生"),
        voice(58, "zh-CN-liaoning-XiaobeiNeural", "东北话"),
        voice(59, "zh-TW-HsiaoChenNeural", "台湾话"),
        voice(60, "zh-CN-shandong-YunxiangNeural", "山东话"),
        voice(61, "zh-CN-sichuan-YunxiNeural", "四川话"),
        voice(62, "xindong", "中英双语"),
        voice(63, "zh-HK-HiuMaanNeural", "粤语女声"),
        voice(64, "wuu-CN-XiaotongNeural", "上海话")
    )

    fun preferences(context: Context) = HchatStorage.preferences(context, PREFS_NAME)

    fun selectedVoiceKey(context: Context): String {
        val value = preferences(context).getString(KEY_VOICE, DEFAULT_VOICE).orEmpty()
        return value.takeIf { key -> voiceOptions.any { it.key == key } } ?: DEFAULT_VOICE
    }

    fun selectedEngine(context: Context): String {
        return preferences(context).getString(KEY_ENGINE, DEFAULT_ENGINE)
            .orEmpty()
            .ifBlank { DEFAULT_ENGINE }
    }

    fun selectedSpeechRate(context: Context): Float {
        return normalizeSpeechRate(
            preferences(context).getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE)
        )
    }

    fun normalizeSpeechRate(value: Float): Float {
        if (!value.isFinite()) return DEFAULT_SPEECH_RATE
        return (value.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE) * 10f)
            .roundToInt() / 10f
    }

    fun onlineSpeechRate(value: Float): Int {
        return ((normalizeSpeechRate(value) - DEFAULT_SPEECH_RATE) * 10f)
            .roundToInt()
            .coerceIn(-9, 20)
    }

    fun isTtsEngine(value: String): Boolean = value.startsWith(ENGINE_TTS_PREFIX)

    fun ttsPackage(value: String): String {
        return value.takeIf(::isTtsEngine)?.removePrefix(ENGINE_TTS_PREFIX).orEmpty()
    }

    fun voiceId(key: String?): String {
        return voiceOptions.firstOrNull { it.key == key }?.voiceId
            ?: voiceOptions.first().voiceId
    }

    fun voiceLabel(key: String?): String {
        return voiceOptions.firstOrNull { it.key == key }?.label
            ?: voiceOptions.first().label
    }

    private fun voice(index: Int, voiceId: String, label: String): VoiceOption {
        return VoiceOption("$index:$voiceId", label, voiceId)
    }
}
