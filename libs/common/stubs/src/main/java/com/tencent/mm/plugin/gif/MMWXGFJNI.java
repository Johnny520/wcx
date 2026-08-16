package com.tencent.mm.plugin.gif;

import android.graphics.Bitmap;

public class MMWXGFJNI {

    public static final int WXAM_SCENE_MISC = 5;

    public static native byte[] nativeWxamToGif(byte[] bArr);

    public static boolean isWxGF(byte[] bArr, int i17) { return false; }

    public static native long nativeInitWxAMDecoder();

    public static native int nativeDecodeBufferHeader(long j17, byte[] bArr, int i17);

    public static native int nativeDecodeBufferFrame(long j17, byte[] bArr, int i17, Bitmap bitmap, int[] iArr);

    public static native int nativeGetOption(long j17, byte[] bArr, int i17, int[] iArr);

    public static native int nativeRewindBuffer(long j17);

    public static native int nativeUninit(long j17);

    public static byte[] wxam2PicBuf(byte[] bArr, int i17, int i18) { return null; }
}
