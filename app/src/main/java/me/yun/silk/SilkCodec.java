package me.yun.silk;

public class SilkCodec {

    /** 时长限制：60秒 */
    public static final long MAX_DURATION_MS = 60_000L;

    /**
     * 获取文件实际类型（通过文件头检测）
     *
     * @param filePath 文件路径
     * @return 文件类型常量 0 = 未知类型 1 = Silk 2 = MP3 3 = WAV 4 = FLAC 5 = OGG 6 = PCM 7 = M4A 8 = MP4
     */
    public native int getFileType(String filePath);

    /**
     * MP3 转 Silk
     *
     * @param mp3Path 输入 MP3 文件路径
     * @param silkPath 输出 Silk 文件路径
     * @param hz Silk 编码内部采样率
     * @return 0=成功, 负数=错误码
     */
    public native int mp3ToSilk(String mp3Path, String silkPath, int hz);

    /**
     * WAV 转 Silk
     *
     * @param wavPath 输入 WAV 文件路径
     * @param silkPath 输出 Silk 文件路径
     * @param hz Silk 编码内部采样率
     * @return 0=成功, 负数=错误码
     */
    public native int wavToSilk(String wavPath, String silkPath, int hz);

    /**
     * FLAC 转 Silk
     *
     * @param flacPath 输入 FLAC 文件路径
     * @param silkPath 输出 Silk 文件路径
     * @param hz Silk 编码内部采样率
     * @return 0=成功, 负数=错误码
     */
    public native int flacToSilk(String flacPath, String silkPath, int hz);

    /**
     * OGG 转 Silk
     *
     * @param oggPath 输入 OGG 文件路径
     * @param silkPath 输出 Silk 文件路径
     * @param hz Silk 编码内部采样率
     * @return 0=成功, 负数=错误码
     */
    public native int oggToSilk(String oggPath, String silkPath, int hz);

    /**
     * PCM 转 Silk
     *
     * @param pcmPath 输入 PCM 文件路径
     * @param silkPath 输出 Silk 文件路径
     * @param hz Silk 编码内部采样率
     * @param pcmHz 输入 PCM 文件采样率
     * @param channels 输入 PCM 文件声道数 (1=单声道, 2=立体声)
     * @return 0=成功, 负数=错误码
     */
    public native int pcmToSilk(String pcmPath, String silkPath, int hz, int pcmHz, int channels);

    /**
     * 自动识别音频格式并转 Silk
     *
     * @param audioPath 输入音频文件路径
     * @param silkPath 输出 Silk 文件路径
     * @param hz Silk 编码内部采样率
     * @return 0=成功, 负数=错误码
     */
    public native int autoToSilk(String audioPath, String silkPath, int hz);

    /**
     * Silk 转 MP3
     *
     * @param silkPath 输入 Silk 文件路径
     * @param mp3Path 输出 MP3 文件路径
     * @param hz 输出 MP3 采样率
     * @return 0=成功, 负数=错误码
     */
    public native int silkToMp3(String silkPath, String mp3Path, int hz);


    /**
     * Silk 转 PCM
     *
     * @param silkPath 输入 Silk 文件路径
     * @param pcmPath 输出 PCM 文件路径
     * @param hz 输出 PCM 采样率
     * @return 0=成功, 负数=错误码
     */
    public native int silkToPcm(String silkPath, String pcmPath, int hz);

    /**
     * MP3 转 PCM
     *
     * @param mp3Path 输入 MP3 文件路径
     * @param pcmPath 输出 PCM 文件路径
     * @return 0=成功, 负数=错误码
     */
    public native int mp3ToPcm(String mp3Path, String pcmPath);

    /**
     * WAV 转 PCM
     *
     * @param wavPath 输入 WAV 文件路径
     * @param pcmPath 输出 PCM 文件路径
     * @return 0=成功, 负数=错误码
     */
    public native int wavToPcm(String wavPath, String pcmPath);

    /**
     * FLAC 转 PCM
     *
     * @param flacPath 输入 FLAC 文件路径
     * @param pcmPath 输出 PCM 文件路径
     * @return 0=成功, 负数=错误码
     */
    public native int flacToPcm(String flacPath, String pcmPath);

    /**
     * OGG 转 PCM
     *
     * @param oggPath 输入 OGG 文件路径
     * @param pcmPath 输出 PCM 文件路径
     * @return 0=成功, 负数=错误码
     */
    public native int oggToPcm(String oggPath, String pcmPath);

    /**
     * 自动识别音频格式并转 PCM
     *
     * @param audioPath 输入音频文件路径
     * @param pcmPath 输出 PCM 文件路径
     * @return 0=成功, 负数=错误码
     */
    public native int autoToPcm(String audioPath, String pcmPath);

    /**
     * 获取音频时长（毫秒）
     *
     * @param filePath 音频文件路径
     * @return 时长，单位毫秒 (例如 2秒返回 2000)
     */
    public native long getDuration(String filePath);

    /**
     * 获取限制后的音频时长（毫秒）。若超过 {@link #MAX_DURATION_MS} 则截断。
     *
     * @param filePath 音频文件路径
     * @return 时长（毫秒），最高 {@value #MAX_DURATION_MS}
     */
    public long getDurationLimited(String filePath) {
        long duration = getDuration(filePath);
        return duration > MAX_DURATION_MS ? MAX_DURATION_MS : duration;
    }
}
