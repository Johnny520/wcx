package com.Johnny.wcx.utils

object AudioUtils {

    external fun anyToSilk(mp3Path: String, silkPath: String): Boolean
    external fun silkToPcm(silkPath: String, pcmPath: String): Boolean
    external fun pcmToMp3(silkPath: String, pcmPath: String): Boolean
    external fun getDurationMs(path: String): Long
}
