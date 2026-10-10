package me.yun.silk;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

public class AacCodec {

    private static final int TIMEOUT_US = 10000;
    private static final long DECODE_STALL_TIMEOUT_MS = 30000L;
    private static final int DEFAULT_SAMPLE_RATE = 44100;
    private static final int DEFAULT_CHANNEL_COUNT = 1;
    private static final int DEFAULT_BIT_RATE = 128000;

    public static class AudioInfo {
        int sampleRate;
        int channelCount;

        public AudioInfo(int sampleRate, int channelCount) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
        }
    }

    private static class DecodeResult {
        int code;
        AudioInfo audioInfo;

        DecodeResult(int code, AudioInfo audioInfo) {
            this.code = code;
            this.audioInfo = audioInfo;
        }
    }

    public static class PcmDecodeResult {
        private final int code;
        private final int sampleRate;

        PcmDecodeResult(int code, int sampleRate) {
            this.code = code;
            this.sampleRate = sampleRate;
        }

        public int getCode() {
            return code;
        }

        public int getSampleRate() {
            return sampleRate;
        }
    }

    public interface AacCallback {
        void onProgress(int progress);
        void onMessage(String msg);
    }

    public static AudioInfo getAudioInfo(String aacPath) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(aacPath);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                        ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : DEFAULT_SAMPLE_RATE;
                    int channelCount = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                        ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : DEFAULT_CHANNEL_COUNT;
                    extractor.release();
                    return new AudioInfo(sampleRate, channelCount);
                }
            }
            extractor.release();
            return new AudioInfo(DEFAULT_SAMPLE_RATE, DEFAULT_CHANNEL_COUNT);
        } catch (Exception e) {
            extractor.release();
            return new AudioInfo(DEFAULT_SAMPLE_RATE, DEFAULT_CHANNEL_COUNT);
        }
    }

    public static int decodeAacFile(String aacPath, String pcmPath, AacCallback callback) {
        return decodeAacFileWithInfo(aacPath, pcmPath, callback).code;
    }

    public static boolean isOggOpusFile(String filePath) {
        if (filePath == null || filePath.length() == 0) return false;
        RandomAccessFile input = null;
        try {
            input = new RandomAccessFile(filePath, "r");
            if (input.length() < 36) return false;
            byte[] header = new byte[27];
            input.readFully(header);
            if (header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S' ||
                header[4] != 0) {
                return false;
            }
            int segmentCount = header[26] & 0xFF;
            if (segmentCount == 0) return false;
            byte[] lacing = new byte[segmentCount];
            input.readFully(lacing);
            int packetSize = 0;
            boolean packetComplete = false;
            for (byte value : lacing) {
                int length = value & 0xFF;
                packetSize += length;
                if (length < 255) {
                    packetComplete = true;
                    break;
                }
            }
            if (!packetComplete || packetSize < 8) return false;
            byte[] signature = new byte[8];
            input.readFully(signature);
            return signature[0] == 'O' && signature[1] == 'p' &&
                signature[2] == 'u' && signature[3] == 's' &&
                signature[4] == 'H' && signature[5] == 'e' &&
                signature[6] == 'a' && signature[7] == 'd';
        } catch (Exception ignored) {
            return false;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public static int oggToPcmCompat(
        String oggPath,
        String pcmPath,
        SilkCodec codec
    ) {
        return decodeOggToPcmWithInfo(oggPath, pcmPath, codec, DEFAULT_SAMPLE_RATE).code;
    }

    public static int oggToSilkCompat(
        String oggPath,
        String silkPath,
        SilkCodec codec,
        int hz
    ) {
        if (!isOggOpusFile(oggPath)) {
            return codec.oggToSilk(oggPath, silkPath, hz);
        }
        new File(silkPath).delete();
        int result = mp4ToSilk(oggPath, silkPath, codec, hz);
        if (result == 0) return 0;
        new File(silkPath).delete();
        return result >= -2803 && result <= -2801 ? -401 : result;
    }

    public static int autoToPcmCompat(
        String audioPath,
        String pcmPath,
        SilkCodec codec
    ) {
        if (isOggOpusFile(audioPath) || codec.getFileType(audioPath) == 5) {
            return oggToPcmCompat(audioPath, pcmPath, codec);
        }
        return codec.autoToPcm(audioPath, pcmPath);
    }

    public static PcmDecodeResult autoToMonoPcmWithInfo(
        String audioPath,
        String pcmPath,
        SilkCodec codec,
        int silkSampleRate
    ) {
        File output = new File(pcmPath);
        output.delete();
        int fileType = codec.getFileType(audioPath);
        if (fileType == 1) {
            int sampleRate = validSampleRate(silkSampleRate, 24000);
            int code = codec.silkToPcm(audioPath, pcmPath, sampleRate);
            if (code != 0) output.delete();
            return new PcmDecodeResult(code, sampleRate);
        }
        if (fileType == 6) {
            return new PcmDecodeResult(-3, DEFAULT_SAMPLE_RATE);
        }
        DecodeResult mediaResult = decodeAacFileWithInfo(audioPath, pcmPath, null);
        if (Thread.currentThread().isInterrupted()) {
            output.delete();
            return new PcmDecodeResult(-804, mediaResult.audioInfo.sampleRate);
        }
        if (mediaResult.code == 0) {
            return new PcmDecodeResult(
                0,
                validPcmSampleRate(mediaResult.audioInfo.sampleRate, DEFAULT_SAMPLE_RATE)
            );
        }
        output.delete();
        int code;
        int sampleRate;
        switch (fileType) {
            case 2:
                sampleRate = getPcmMetadataSampleRate(audioPath, DEFAULT_SAMPLE_RATE);
                code = codec.mp3ToPcm(audioPath, pcmPath);
                break;
            case 3:
                sampleRate = getWavPcmSampleRate(audioPath, DEFAULT_SAMPLE_RATE);
                code = codec.wavToPcm(audioPath, pcmPath);
                break;
            case 4:
                sampleRate = getPcmMetadataSampleRate(audioPath, DEFAULT_SAMPLE_RATE);
                code = codec.flacToPcm(audioPath, pcmPath);
                break;
            case 5:
                DecodeResult oggResult = decodeOggToPcmWithInfo(
                    audioPath,
                    pcmPath,
                    codec,
                    DEFAULT_SAMPLE_RATE
                );
                code = oggResult.code;
                sampleRate = getPcmMetadataSampleRate(
                    audioPath,
                    oggResult.audioInfo.sampleRate
                );
                break;
            default:
                sampleRate = getPcmMetadataSampleRate(audioPath, DEFAULT_SAMPLE_RATE);
                code = codec.autoToPcm(audioPath, pcmPath);
                break;
        }
        if (code != 0) output.delete();
        return new PcmDecodeResult(code, validPcmSampleRate(sampleRate, DEFAULT_SAMPLE_RATE));
    }

    public static int autoToSilkCompat(
        String audioPath,
        String silkPath,
        SilkCodec codec,
        int hz
    ) {
        if (isOggOpusFile(audioPath) || codec.getFileType(audioPath) == 5) {
            return oggToSilkCompat(audioPath, silkPath, codec, hz);
        }
        return codec.autoToSilk(audioPath, silkPath, hz);
    }

    private static DecodeResult decodeAacFileWithInfo(String aacPath, String pcmPath, AacCallback callback) {
        if (callback != null) callback.onMessage("开始解码: " + aacPath);
        
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        FileOutputStream fos = null;
        boolean decodeSucceeded = false;
        try {
            File inputFile = new File(aacPath);
            if (!inputFile.exists()) {
                if (callback != null) callback.onMessage("文件不存在: " + aacPath);
                return new DecodeResult(-801, new AudioInfo(DEFAULT_SAMPLE_RATE, DEFAULT_CHANNEL_COUNT));
            }

            extractor.setDataSource(aacPath);
            int audioTrackIndex = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    inputFormat = format;
                    if (callback != null) callback.onMessage("找到音频轨道: " + i);
                    break;
                }
            }
            if (audioTrackIndex == -1) {
                if (callback != null) callback.onMessage("未找到音频轨道");
                return new DecodeResult(-802, new AudioInfo(DEFAULT_SAMPLE_RATE, DEFAULT_CHANNEL_COUNT));
            }
            extractor.selectTrack(audioTrackIndex);

            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : DEFAULT_SAMPLE_RATE;
            int channelCount = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                ? inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : DEFAULT_CHANNEL_COUNT;
            int outputSampleRate = sampleRate;
            int outputChannelCount = channelCount;

            if (callback != null) callback.onMessage("参数: " + sampleRate + "Hz, " + channelCount + "通道");

            codec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME));
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            fos = new FileOutputStream(pcmPath);
            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            ByteBuffer[] outputBuffers = codec.getOutputBuffers();

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;
            long decodedBytes = 0L;
            long lastProgressAt = SystemClock.elapsedRealtime();

            while (!sawOutputEOS) {
                if (Thread.currentThread().isInterrupted()) {
                    return new DecodeResult(-804, new AudioInfo(outputSampleRate, 1));
                }
                if (!sawInputEOS) {
                    int inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                        inputBuffer.clear();
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sawInputEOS = true;
                        } else {
                            codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                        lastProgressAt = SystemClock.elapsedRealtime();
                    }
                }

                int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                if (outputBufferIndex >= 0) {
                    boolean outputEos =
                        (bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if (bufferInfo.size > 0) {
                        lastProgressAt = SystemClock.elapsedRealtime();
                        ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        
                        byte[] buffer = new byte[bufferInfo.size];
                        outputBuffer.get(buffer);
                        
                        if (outputChannelCount == 1) {
                            fos.write(buffer);
                            decodedBytes += buffer.length;
                        } else {
                            int channels = Math.max(1, outputChannelCount);
                            int frameCount = bufferInfo.size / (channels * 2);
                            byte[] monoBuffer = new byte[frameCount * 2];
                            for (int frame = 0; frame < frameCount; frame++) {
                                long sum = 0L;
                                for (int channel = 0; channel < channels; channel++) {
                                    int index = (frame * channels + channel) * 2;
                                    sum += (short) ((buffer[index] & 0xFF) | (buffer[index + 1] << 8));
                                }
                                int sample = (int) (sum / channels);
                                monoBuffer[frame * 2] = (byte)(sample & 0xFF);
                                monoBuffer[frame * 2 + 1] = (byte)((sample >> 8) & 0xFF);
                            }
                            fos.write(monoBuffer);
                            decodedBytes += monoBuffer.length;
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false);
                    if (outputEos) {
                        sawOutputEOS = true;
                        lastProgressAt = SystemClock.elapsedRealtime();
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                    lastProgressAt = SystemClock.elapsedRealtime();
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    if (outputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        outputSampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        outputChannelCount = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                    lastProgressAt = SystemClock.elapsedRealtime();
                }
                if (SystemClock.elapsedRealtime() - lastProgressAt > DECODE_STALL_TIMEOUT_MS) {
                    if (callback != null) callback.onMessage("解码超时: 解码器长时间无输出");
                    return new DecodeResult(
                        -803,
                        new AudioInfo(outputSampleRate, Math.max(1, outputChannelCount))
                    );
                }
            }

            if (decodedBytes <= 0L) {
                if (callback != null) callback.onMessage("解码失败: 未产生音频数据");
                return new DecodeResult(-803, new AudioInfo(outputSampleRate, 1));
            }
            fos.close();
            fos = null;
            if (callback != null) callback.onMessage("解码完成");
            decodeSucceeded = true;
            return new DecodeResult(0, new AudioInfo(outputSampleRate, 1));
        } catch (Exception e) {
            if (callback != null) callback.onMessage("解码异常: " + e.getMessage());
            e.printStackTrace();
            return new DecodeResult(-803, new AudioInfo(DEFAULT_SAMPLE_RATE, DEFAULT_CHANNEL_COUNT));
        } finally {
            try {
                if (fos != null) fos.close();
            } catch (Exception ignored) {}
            try {
                if (codec != null) codec.stop();
            } catch (Exception ignored) {}
            try {
                if (codec != null) codec.release();
            } catch (Exception ignored) {}
            try {
                extractor.release();
            } catch (Exception ignored) {}
            if (!decodeSucceeded && pcmPath != null && pcmPath.length() > 0) {
                new File(pcmPath).delete();
            }
        }
    }

    public static int encodePcmToAac(String pcmPath, String aacPath, int sampleRate, int channels, AacCallback callback) {
        if (callback != null) callback.onMessage("开始编码 AAC: " + pcmPath);
        
        File pcmFile = new File(pcmPath);
        if (!pcmFile.exists()) {
            if (callback != null) callback.onMessage("PCM 文件不存在");
            return -901;
        }

        try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
            format.setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_BIT_RATE);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            RandomAccessFile raf = new RandomAccessFile(pcmPath, "r");
            FileOutputStream fos = new FileOutputStream(aacPath);
            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            ByteBuffer[] outputBuffers = codec.getOutputBuffers();

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;
            int frameCount = 0;
            long fileSize = pcmFile.length();

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    int inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                        inputBuffer.clear();
                        
                        byte[] pcmChunk = new byte[Math.min(4096, (int)(fileSize - raf.getFilePointer()))];
                        int bytesRead = raf.read(pcmChunk);
                        
                        if (bytesRead > 0) {
                            inputBuffer.put(pcmChunk, 0, bytesRead);
                            long timeUs = (frameCount * 1024L * 1000000L) / sampleRate;
                            codec.queueInputBuffer(inputBufferIndex, 0, bytesRead, timeUs, 0);
                            frameCount++;
                        } else {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sawInputEOS = true;
                        }

                        if (callback != null && fileSize > 0) {
                            int progress = (int)(raf.getFilePointer() * 100 / fileSize);
                            callback.onProgress(Math.min(progress, 99));
                        }
                    }
                }

                int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                if (outputBufferIndex >= 0) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEOS = true;
                    
                    if (bufferInfo.size > 0) {
                        ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                        byte[] aacFrame = new byte[bufferInfo.size];
                        outputBuffer.get(aacFrame);
                        
                        byte[] adtsHeader = createAdtsHeader(sampleRate, channels, aacFrame.length);
                        fos.write(adtsHeader);
                        fos.write(aacFrame);
                    }
                    
                    codec.releaseOutputBuffer(outputBufferIndex, false);
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                }
            }

            raf.close();
            fos.close();
            codec.stop();
            codec.release();
            
            if (callback != null) {
                callback.onMessage("编码完成");
                callback.onProgress(100);
            }
            return 0;
        } catch (Exception e) {
            if (callback != null) callback.onMessage("编码异常: " + e.getMessage());
            e.printStackTrace();
            return -902;
        }
    }

    public static int encodePcmToM4a(String pcmPath, String m4aPath, int sampleRate, int channels, AacCallback callback) {
        if (callback != null) callback.onMessage("开始编码 M4A: " + pcmPath);
        
        File pcmFile = new File(pcmPath);
        if (!pcmFile.exists()) {
            if (callback != null) callback.onMessage("PCM 文件不存在");
            return -911;
        }

        try {
            MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
            format.setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_BIT_RATE);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

            MediaCodec codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            MediaMuxer muxer = new MediaMuxer(m4aPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int trackIndex = -1;
            boolean muxerStarted = false;

            RandomAccessFile raf = new RandomAccessFile(pcmPath, "r");
            ByteBuffer[] inputBuffers = codec.getInputBuffers();
            ByteBuffer[] outputBuffers = codec.getOutputBuffers();

            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;
            int frameCount = 0;
            long fileSize = pcmFile.length();
            int frameSize = 1024 * channels * 2;

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    int inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (inputBufferIndex >= 0) {
                        ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                        inputBuffer.clear();
                        
                        byte[] pcmChunk = new byte[Math.min(frameSize, (int)(fileSize - raf.getFilePointer()))];
                        int bytesRead = raf.read(pcmChunk);
                        
                        if (bytesRead > 0) {
                            inputBuffer.put(pcmChunk, 0, bytesRead);
                            long timeUs = (frameCount * 1024L * 1000000L) / sampleRate;
                            codec.queueInputBuffer(inputBufferIndex, 0, bytesRead, timeUs, 0);
                            frameCount++;
                        } else {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sawInputEOS = true;
                        }

                        if (callback != null && fileSize > 0) {
                            int progress = (int)(raf.getFilePointer() * 100 / fileSize);
                            callback.onProgress(Math.min(progress, 99));
                        }
                    }
                }

                int outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                if (outputBufferIndex >= 0) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawOutputEOS = true;
                    
                    if (bufferInfo.size > 0 && !sawOutputEOS) {
                        if (!muxerStarted) {
                            MediaFormat outputFormat = codec.getOutputFormat();
                            trackIndex = muxer.addTrack(outputFormat);
                            muxer.start();
                            muxerStarted = true;
                        }
                        
                        ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo);
                    }
                    
                    codec.releaseOutputBuffer(outputBufferIndex, false);
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = codec.getOutputBuffers();
                }
            }

            raf.close();
            codec.stop();
            codec.release();
            if (muxerStarted) muxer.stop();
            muxer.release();
            
            if (callback != null) {
                callback.onMessage("编码完成");
                callback.onProgress(100);
            }
            return 0;
        } catch (Exception e) {
            if (callback != null) callback.onMessage("编码异常: " + e.getMessage());
            e.printStackTrace();
            return -912;
        }
    }

    private static byte[] createAdtsHeader(int sampleRate, int channels, int frameLength) {
        int sampleRateIndex = getSampleRateIndex(sampleRate);
        byte[] adtsHeader = new byte[7];
        adtsHeader[0] = (byte) 0xFF;
        adtsHeader[1] = (byte) 0xF1;
        adtsHeader[2] = (byte) (((2 - 1) << 6) | (sampleRateIndex << 2) | (channels >> 2));
        adtsHeader[3] = (byte) (((channels & 3) << 6) | ((frameLength + 7) >> 11));
        adtsHeader[4] = (byte) (((frameLength + 7) >> 3) & 0xFF);
        adtsHeader[5] = (byte) ((((frameLength + 7) & 7) << 5) | 0x1F);
        adtsHeader[6] = (byte) 0xFC;
        return adtsHeader;
    }

    private static int getSampleRateIndex(int sampleRate) {
        switch (sampleRate) {
            case 96000: return 0;
            case 88200: return 1;
            case 64000: return 2;
            case 48000: return 3;
            case 44100: return 4;
            case 32000: return 5;
            case 24000: return 6;
            case 22050: return 7;
            case 16000: return 8;
            case 12000: return 9;
            case 11025: return 10;
            case 8000: return 11;
            default: return 4;
        }
    }

    private static int validSampleRate(int sampleRate, int fallback) {
        switch (sampleRate) {
            case 8000:
            case 11025:
            case 12000:
            case 16000:
            case 22050:
            case 24000:
            case 32000:
            case 44100:
            case 48000:
            case 64000:
            case 88200:
            case 96000:
                return sampleRate;
            default:
                return fallback > 0 ? fallback : DEFAULT_SAMPLE_RATE;
        }
    }

    private static int validPcmSampleRate(int sampleRate, int fallback) {
        return sampleRate >= 4000 && sampleRate <= 384000
            ? sampleRate
            : (fallback > 0 ? fallback : DEFAULT_SAMPLE_RATE);
    }

    private static int getPcmMetadataSampleRate(String filePath, int fallback) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(filePath);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
            if (value != null && value.length() > 0) {
                return validPcmSampleRate(Integer.parseInt(value), fallback);
            }
        } catch (Exception ignored) {
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }
        }
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(filePath);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/") &&
                    format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    return validPcmSampleRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), fallback);
                }
            }
        } catch (Exception ignored) {
        } finally {
            extractor.release();
        }
        return validPcmSampleRate(fallback, DEFAULT_SAMPLE_RATE);
    }

    private static int getWavPcmSampleRate(String wavPath, int fallback) {
        RandomAccessFile input = null;
        try {
            input = new RandomAccessFile(wavPath, "r");
            if (input.length() < 12L || input.readInt() != 0x52494646) return fallback;
            input.skipBytes(4);
            if (input.readInt() != 0x57415645) return fallback;
            while (input.getFilePointer() + 8L <= input.length()) {
                int chunkId = input.readInt();
                long chunkSize = ((long) Integer.reverseBytes(input.readInt())) & 0xffffffffL;
                long chunkStart = input.getFilePointer();
                if (chunkId == 0x666d7420 && chunkSize >= 16L) {
                    input.skipBytes(4);
                    int sampleRate = Integer.reverseBytes(input.readInt());
                    return validPcmSampleRate(sampleRate, fallback);
                }
                long nextChunk = chunkStart + chunkSize + (chunkSize & 1L);
                if (nextChunk <= chunkStart || nextChunk > input.length()) break;
                input.seek(nextChunk);
            }
        } catch (Exception ignored) {
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception ignored) {
                }
            }
        }
        return getPcmMetadataSampleRate(wavPath, fallback);
    }

    private static int getMetadataSampleRate(String filePath, int fallback) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(filePath);
            String sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE);
            if (sampleRate != null && sampleRate.length() > 0) {
                return validSampleRate(Integer.parseInt(sampleRate), fallback);
            }
        } catch (Exception ignored) {
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }
        }
        return validSampleRate(fallback, DEFAULT_SAMPLE_RATE);
    }

    private static int getWavSampleRate(String wavPath, int fallback) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(wavPath, "r");
            if (raf.length() >= 28) {
                raf.seek(24);
                int sampleRate = raf.readUnsignedByte()
                    | (raf.readUnsignedByte() << 8)
                    | (raf.readUnsignedByte() << 16)
                    | (raf.readUnsignedByte() << 24);
                return validSampleRate(sampleRate, fallback);
            }
        } catch (Exception ignored) {
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (Exception ignored) {
                }
            }
        }
        return validSampleRate(fallback, DEFAULT_SAMPLE_RATE);
    }

    private static DecodeResult decodeOggToPcmWithInfo(
        String oggPath,
        String pcmPath,
        SilkCodec codec,
        int fallbackSampleRate
    ) {
        if (isOggOpusFile(oggPath)) {
            DecodeResult result = decodeAacFileWithInfo(oggPath, pcmPath, null);
            if (result.code == 0) return result;
            new File(pcmPath).delete();
            return new DecodeResult(-401, result.audioInfo);
        }
        int result = codec.oggToPcm(oggPath, pcmPath);
        return new DecodeResult(
            result,
            new AudioInfo(getMetadataSampleRate(oggPath, fallbackSampleRate), 1)
        );
    }

    public static int mp4ToSilk(String mp4Path, String silkPath, SilkCodec codec, int hz) {
        String tempPcm = silkPath + ".temp.pcm";
        String resampledPcm = silkPath + ".temp." + hz + ".pcm";
        try {
            DecodeResult decodeResult = decodeAacFileWithInfo(mp4Path, tempPcm, null);
            if (decodeResult.code != 0) return decodeResult.code - 2000;
            AudioInfo audioInfo = decodeResult.audioInfo;

            String encodePcm = tempPcm;
            int encodePcmHz = audioInfo.sampleRate;
            if (audioInfo.sampleRate != hz) {
                if (!resampleMonoPcm16(tempPcm, resampledPcm, audioInfo.sampleRate, hz)) {
                    return -701;
                }
                encodePcm = resampledPcm;
                encodePcmHz = hz;
            }

            return codec.pcmToSilk(encodePcm, silkPath, hz, encodePcmHz, 1);
        } catch (Exception e) {
            e.printStackTrace();
            return -1031;
        } finally {
            new File(tempPcm).delete();
            new File(resampledPcm).delete();
        }
    }

    private static boolean resampleMonoPcm16(
        String inputPath,
        String outputPath,
        int sourceRate,
        int targetRate
    ) throws Exception {
        if (sourceRate <= 0 || targetRate <= 0) return false;

        File inputFile = new File(inputPath);
        long byteLength = inputFile.length();
        if (byteLength < 2 || byteLength > Integer.MAX_VALUE - 1) return false;

        int inputLength = (int) (byteLength & ~1L);
        byte[] inputBytes = new byte[inputLength];
        RandomAccessFile input = new RandomAccessFile(inputFile, "r");
        try {
            input.readFully(inputBytes);
        } finally {
            input.close();
        }

        int inputSamples = inputLength / 2;
        int outputSamples = Math.max(1, (int) Math.round(inputSamples * (double) targetRate / sourceRate));
        byte[] outputBytes = new byte[outputSamples * 2];
        double ratio = sourceRate / (double) targetRate;

        for (int i = 0; i < outputSamples; i++) {
            double sourcePosition = i * ratio;
            int sourceIndex = (int) sourcePosition;
            double fraction = sourcePosition - sourceIndex;

            int sampleA = readPcm16(inputBytes, Math.min(sourceIndex, inputSamples - 1));
            int sampleB = readPcm16(inputBytes, Math.min(sourceIndex + 1, inputSamples - 1));
            int sample = (int) Math.round(sampleA + (sampleB - sampleA) * fraction);
            sample = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));

            int outputIndex = i * 2;
            outputBytes[outputIndex] = (byte) (sample & 0xFF);
            outputBytes[outputIndex + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        FileOutputStream output = new FileOutputStream(outputPath);
        try {
            output.write(outputBytes);
        } finally {
            output.close();
        }
        return true;
    }

    private static int readPcm16(byte[] bytes, int sampleIndex) {
        int byteIndex = sampleIndex * 2;
        return (short) ((bytes[byteIndex] & 0xFF) | (bytes[byteIndex + 1] << 8));
    }

    public static int silkToM4a(String silkPath, String m4aPath, SilkCodec codec, int hz) {
        try {
            String tempPcm = m4aPath + ".temp.pcm";
            int result = codec.silkToPcm(silkPath, tempPcm, hz);
            if (result != 0) return result;

            result = encodePcmToM4a(tempPcm, m4aPath, hz, 1, null);
            new File(tempPcm).delete();
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return -1001;
        }
    }

    public static int mp4ToM4a(String mp4Path, String m4aPath, int hz) {
        String tempPcm = m4aPath + ".temp.pcm";
        try {
            DecodeResult decodeResult = decodeAacFileWithInfo(mp4Path, tempPcm, null);
            if (decodeResult.code != 0) return decodeResult.code - 2000;

            return encodePcmToM4a(tempPcm, m4aPath, decodeResult.audioInfo.sampleRate, 1, null);
        } catch (Exception e) {
            e.printStackTrace();
            return -1061;
        } finally {
            new File(tempPcm).delete();
        }
    }

    public static int mp4ToAac(String mp4Path, String aacPath, int hz) {
        String tempPcm = aacPath + ".temp.pcm";
        try {
            DecodeResult decodeResult = decodeAacFileWithInfo(mp4Path, tempPcm, null);
            if (decodeResult.code != 0) return decodeResult.code - 2000;

            return encodePcmToAac(tempPcm, aacPath, decodeResult.audioInfo.sampleRate, 1, null);
        } catch (Exception e) {
            e.printStackTrace();
            return -1051;
        } finally {
            new File(tempPcm).delete();
        }
    }

    public static int m4aToSilk(String m4aPath, String silkPath, SilkCodec codec, int hz) {
        return mp4ToSilk(m4aPath, silkPath, codec, hz);
    }

    public static int aacToSilk(String aacPath, String silkPath, SilkCodec codec, int hz) {
        return mp4ToSilk(aacPath, silkPath, codec, hz);
    }

    public static int m4aToAac(String m4aPath, String aacPath, int hz) {
        return mp4ToAac(m4aPath, aacPath, hz);
    }

    public static int m4aToM4a(String m4aPath, String m4aPathOut, int hz) {
        return mp4ToM4a(m4aPath, m4aPathOut, hz);
    }

    public static int autoToAac(String inputPath, String aacPath, SilkCodec codec, int hz) {
        if (isOggOpusFile(inputPath)) return oggToAac(inputPath, aacPath, hz);
        int fileType = codec.getFileType(inputPath);
        switch (fileType) {
            case 1: return silkToAac(inputPath, aacPath, codec, hz);
            case 2: return mp3ToAac(inputPath, aacPath, hz);
            case 3: return wavToAac(inputPath, aacPath, hz);
            case 4: return flacToAac(inputPath, aacPath, hz);
            case 5: return oggToAac(inputPath, aacPath, hz);
            case 7: return m4aToAac(inputPath, aacPath, hz);
            case 8: return mp4ToAac(inputPath, aacPath, hz);
            default: return -2;
        }
    }

    public static int autoToM4a(String inputPath, String m4aPath, SilkCodec codec, int hz) {
        if (isOggOpusFile(inputPath)) return oggToM4a(inputPath, m4aPath, hz);
        int fileType = codec.getFileType(inputPath);
        switch (fileType) {
            case 1: return silkToM4a(inputPath, m4aPath, codec, hz);
            case 2: return mp3ToM4a(inputPath, m4aPath, hz);
            case 3: return wavToM4a(inputPath, m4aPath, hz);
            case 4: return flacToM4a(inputPath, m4aPath, hz);
            case 5: return oggToM4a(inputPath, m4aPath, hz);
            case 7: return m4aToM4a(inputPath, m4aPath, hz);
            case 8: return mp4ToM4a(inputPath, m4aPath, hz);
            default: return -2;
        }
    }

    public static int autoAacToSilk(String inputPath, String silkPath, SilkCodec codec, int hz) {
        return m4aToSilk(inputPath, silkPath, codec, hz);
    }

    public static int silkToAac(String silkPath, String aacPath, SilkCodec codec, int hz) {
        try {
            String tempPcm = aacPath + ".temp.pcm";
            int result = codec.silkToPcm(silkPath, tempPcm, hz);
            if (result != 0) return result;
            
            result = encodePcmToAac(tempPcm, aacPath, hz, 1, null);
            new File(tempPcm).delete();
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return -1001;
        }
    }

    private static int mp3ToAac(String mp3Path, String aacPath, int sampleRate) {
        String tempPcm = aacPath + ".temp.pcm";
        try {
            SilkCodec codec = new SilkCodec();
            int result = codec.mp3ToPcm(mp3Path, tempPcm);
            if (result != 0) return result;

            int pcmSampleRate = getMetadataSampleRate(mp3Path, sampleRate);
            return encodePcmToAac(tempPcm, aacPath, pcmSampleRate, 1, null);
        } catch (Exception e) {
            e.printStackTrace();
            return -1011;
        } finally {
            new File(tempPcm).delete();
        }
    }

    private static int mp3ToM4a(String mp3Path, String m4aPath, int sampleRate) {
        String tempPcm = m4aPath + ".temp.pcm";
        try {
            SilkCodec codec = new SilkCodec();
            int result = codec.mp3ToPcm(mp3Path, tempPcm);
            if (result != 0) return result;

            int pcmSampleRate = getMetadataSampleRate(mp3Path, sampleRate);
            return encodePcmToM4a(tempPcm, m4aPath, pcmSampleRate, 1, null);
        } catch (Exception e) {
            e.printStackTrace();
            return -1012;
        } finally {
            new File(tempPcm).delete();
        }
    }

    private static int wavToAac(String wavPath, String aacPath, int sampleRate) {
        String tempPcm = aacPath + ".temp.pcm";
        try {
            SilkCodec codec = new SilkCodec();
            int result = codec.wavToPcm(wavPath, tempPcm);
            if (result != 0) return result;

            int pcmSampleRate = getWavSampleRate(wavPath, sampleRate);
            return encodePcmToAac(tempPcm, aacPath, pcmSampleRate, 1, null);
        } catch (Exception e) {
            e.printStackTrace();
            return -1021;
        } finally {
            new File(tempPcm).delete();
        }
    }

    private static int wavToM4a(String wavPath, String m4aPath, int sampleRate) {
        String tempPcm = m4aPath + ".temp.pcm";
        try {
            SilkCodec codec = new SilkCodec();
            int result = codec.wavToPcm(wavPath, tempPcm);
            if (result != 0) return result;

            int pcmSampleRate = getWavSampleRate(wavPath, sampleRate);
            return encodePcmToM4a(tempPcm, m4aPath, pcmSampleRate, 1, null);
        } catch (Exception e) {
            e.printStackTrace();
            return -1022;
        } finally {
            new File(tempPcm).delete();
        }
    }

    private static int flacToAac(String flacPath, String aacPath, int sampleRate) {
        String tempPcm = aacPath + ".temp.pcm";
        try {
            SilkCodec codec = new SilkCodec();
            int result = codec.flacToPcm(flacPath, tempPcm);
            if (result != 0) return result;

            int pcmSampleRate = getMetadataSampleRate(flacPath, sampleRate);
            return encodePcmToAac(tempPcm, aacPath, pcmSampleRate, 1, null);
        } catch (Exception e) {
            e.printStackTrace();
            return -1051;
        } finally {
            new File(tempPcm).delete();
        }
    }

    private static int flacToM4a(String flacPath, String m4aPath, int sampleRate) {
        String tempPcm = m4aPath + ".temp.pcm";
        try {
            SilkCodec codec = new SilkCodec();
            int result = codec.flacToPcm(flacPath, tempPcm);
            if (result != 0) return result;

            int pcmSampleRate = getMetadataSampleRate(flacPath, sampleRate);
            return encodePcmToM4a(tempPcm, m4aPath, pcmSampleRate, 1, null);
        } catch (Exception e) {
            e.printStackTrace();
            return -1061;
        } finally {
            new File(tempPcm).delete();
        }
    }

    private static int oggToAac(String oggPath, String aacPath, int sampleRate) {
        String tempPcm = aacPath + ".temp.pcm";
        new File(aacPath).delete();
        try {
            SilkCodec codec = new SilkCodec();
            DecodeResult decodeResult = decodeOggToPcmWithInfo(
                oggPath,
                tempPcm,
                codec,
                sampleRate
            );
            if (decodeResult.code != 0) return decodeResult.code;

            int result = encodePcmToAac(
                tempPcm,
                aacPath,
                decodeResult.audioInfo.sampleRate,
                1,
                null
            );
            if (result != 0) new File(aacPath).delete();
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            new File(aacPath).delete();
            return -1051;
        } finally {
            new File(tempPcm).delete();
        }
    }

    private static int oggToM4a(String oggPath, String m4aPath, int sampleRate) {
        String tempPcm = m4aPath + ".temp.pcm";
        new File(m4aPath).delete();
        try {
            SilkCodec codec = new SilkCodec();
            DecodeResult decodeResult = decodeOggToPcmWithInfo(
                oggPath,
                tempPcm,
                codec,
                sampleRate
            );
            if (decodeResult.code != 0) return decodeResult.code;

            int result = encodePcmToM4a(
                tempPcm,
                m4aPath,
                decodeResult.audioInfo.sampleRate,
                1,
                null
            );
            if (result != 0) new File(m4aPath).delete();
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            new File(m4aPath).delete();
            return -1061;
        } finally {
            new File(tempPcm).delete();
        }
    }

    public static int aacToPcm(String aacPath, String pcmPath) {
        return decodeAacFile(aacPath, pcmPath, null);
    }

    public static int pcmToAac(String pcmPath, String aacPath, int sampleRate, int channels) {
        return encodePcmToAac(pcmPath, aacPath, sampleRate, channels, null);
    }

    public static int pcmToM4a(String pcmPath, String m4aPath, int sampleRate, int channels) {
        return encodePcmToM4a(pcmPath, m4aPath, sampleRate, channels, null);
    }

    public static int m4aToPcm(String m4aPath, String pcmPath) {
        return decodeAacFile(m4aPath, pcmPath, null);
    }

    public static int decodeM4aFile(String m4aPath, String pcmPath, AacCallback callback) {
        return decodeAacFile(m4aPath, pcmPath, callback);
    }

    public static long getDuration(String filePath) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(filePath);
            String durationStr = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                return Long.parseLong(durationStr);
            }
        } catch (Exception e) {
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                }
            }
        }
        return 0;
    }

    public static String getErrorMessage(int code) {
        if (code == 0) return "成功";
        if (code >= -801 && code <= -802) return "AAC/M4A 解码错误 (文件读取失败)";
        if (code == -803) return "AAC/M4A 解码错误 (格式不支持)";
        if (code >= -901 && code <= -902) return "AAC 编码错误 (文件操作失败)";
        if (code >= -911 && code <= -912) return "M4A 编码错误 (Muxer 失败)";
        if (code >= -1001 && code <= -1009) return "Silk 转 AAC/M4A 错误";
        if (code >= -1011 && code <= -1012) return "MP3 转 AAC/M4A 错误";
        if (code >= -1021 && code <= -1022) return "WAV 转 AAC/M4A 错误";
        if (code >= -1031 && code <= -1039) return "M4A/AAC 转 Silk 错误";
        if (code >= -1051 && code <= -1059) return "M4A/AAC 转 AAC 错误";
        if (code >= -1061 && code <= -1069) return "M4A/AAC 转 M4A 错误";
        if (code == -2000) return "M4A/AAC 转 Silk 错误 (解码失败)";
        return "错误码: " + code + " → 未知错误";
    }
}
