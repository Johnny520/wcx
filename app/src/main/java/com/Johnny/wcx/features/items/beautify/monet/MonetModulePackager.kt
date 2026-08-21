//package com.Johnny.wcx.features.items.beautify.monet
//
//import android.os.Build
//import com.Johnny.wcx.utils.HostInfo
//import com.Johnny.wcx.utils.WeLogger
//import java.io.File
//import java.util.zip.CRC32
//import java.util.zip.ZipEntry
//import java.util.zip.ZipOutputStream
//
///**
// * 把签名后的 overlay APK 打包成可在 Magisk 中直接刷入的模块 zip。
// *
// * 结构与参考模块一致:
// * ```
// * module.prop
// * customize.sh
// * META-INF/com/google/android/update-binary   (Magisk 通用 installer)
// * META-INF/com/google/android/updater-script  (#MAGISK)
// * system/priv-app/MonetWeChat.apk             (SDK >= 34)  或
// * system/product/overlay/MonetWeChat.apk      (SDK 31-33)
// * ```
// * 系统开机时把该 APK 作为静态 RRO 加载, 无需 idmap 手工生成。
// */
//object MonetModulePackager {
//
//    private const val TAG = "MonetModulePackager"
//
//    private const val OVERLAY_APK_NAME = "MonetWeChat.apk"
//
//    /**
//     * @param signedOverlayApk 已签名的 overlay APK
//     * @param outputZip 目标模块 zip 路径
//     */
//    fun pack(signedOverlayApk: File, outputZip: File) {
//        val apkInstallPath =
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
//                "system/priv-app/$OVERLAY_APK_NAME"
//            else
//                "system/product/overlay/$OVERLAY_APK_NAME"
//
//        outputZip.parentFile?.mkdirs()
//        ZipOutputStream(outputZip.outputStream().buffered()).use { zos ->
//            zos.putTextEntry("module.prop", buildModuleProp())
//            zos.putRawEntry("customize.sh", MonetEmbeddedAssets.CUSTOMIZE_SH)
//            zos.putRawEntry(
//                "META-INF/com/google/android/update-binary",
//                MonetEmbeddedAssets.UPDATE_BINARY
//            )
//            zos.putRawEntry(
//                "META-INF/com/google/android/updater-script",
//                MonetEmbeddedAssets.UPDATER_SCRIPT
//            )
//            zos.putFileEntry(apkInstallPath, signedOverlayApk)
//        }
//        WeLogger.i(TAG, "packed magisk module: ${outputZip.length()} bytes -> $outputZip")
//    }
//
//    private fun buildModuleProp(): String {
//        val vc = HostInfo.versionCode
//        val vn = HostInfo.versionName
//        return buildString {
//            appendLine("id=wekit-monet-engine")
//            appendLine("name=微信莫奈引擎 (WeKit)")
//            appendLine("version=$vn ($vc)")
//            appendLine("versionCode=$vc")
//            appendLine("author=Ujhhgtg")
//            append("description=为微信 $vn 启用动态壁纸取色, 由 WeKit 在运行时生成")
//        }
//    }
//
//    private fun ZipOutputStream.putTextEntry(name: String, content: String) {
//        putRawEntry(name, content.toByteArray(Charsets.UTF_8))
//    }
//
//    private fun ZipOutputStream.putFileEntry(name: String, file: File) {
//        putRawEntry(name, file.readBytes())
//    }
//
//    private fun ZipOutputStream.putRawEntry(name: String, bytes: ByteArray) {
//        val entry = ZipEntry(name).apply {
//            // 用 DEFLATED, 与参考模块一致; APK 内部已是 zip, 压缩率有限但无害。
//            method = ZipEntry.DEFLATED
//        }
//        putNextEntry(entry)
//        write(bytes)
//        closeEntry()
//    }
//
//    @Suppress("unused")
//    private fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value
//}
