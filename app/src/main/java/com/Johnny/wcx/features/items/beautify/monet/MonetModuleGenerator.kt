//package com.Johnny.wcx.features.items.beautify.monet
//
//import android.os.Build
//import com.Johnny.wcx.utils.HostInfo
//import com.Johnny.wcx.utils.WeLogger
//import com.Johnny.wcx.utils.fs.KnownPaths
//import com.Johnny.wcx.utils.fs.createDirsSafe
//import java.io.File
//import kotlin.io.path.div
//
///**
// * 莫奈引擎 (模块) 的运行时生成入口。
// *
// * 流程:
// * 1. 从 assets 取出与当前 SDK 匹配的 overlay 模板 (api34 / api31);
// * 2. [MonetOverlayBuilder] 依据当前微信版本裁剪/新增颜色覆盖项;
// * 3. [MonetApkSigner] 自签名;
// * 4. [MonetModulePackager] 打包成 Magisk 模块 zip, 写入
// *    `KnownPaths.moduleData/monet_engine_module.zip`。
// */
//object MonetModuleGenerator {
//
//    private const val TAG = "MonetModuleGenerator"
//
//    /** 生成产物 zip 的最终位置。 */
//    val outputZip: File
//        get() = (KnownPaths.downloads / "monet_engine_module.zip").toFile()
//
//    private val workDir: File
//        get() = (KnownPaths.moduleCache / "monet").createDirsSafe().toFile()
//
//    sealed interface Progress {
//        data object Preparing : Progress
//        data object BuildingOverlay : Progress
//        data object Signing : Progress
//        data object Packaging : Progress
//        data class Done(val result: MonetOverlayBuilder.Result, val zip: File) : Progress
//        data class Failed(val error: Throwable) : Progress
//    }
//
////    val isSupported: Boolean
////        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && MonetOverlayBuilder.isWeChatHost()
//
//    /**
//     * 同步执行生成。应在后台线程调用。[onProgress] 在同一线程回调。
//     */
//    fun generate(onProgress: (Progress) -> Unit = {}): Progress {
//        return try {
//            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//                "系统 SDK 过低 (需 >= 31)"
//            }
//            require(MonetOverlayBuilder.isWeChatHost()) {
//                "只能在微信进程内生成 (当前: ${HostInfo.packageName})"
//            }
//
//            onProgress(Progress.Preparing)
//            val work = workDir
//            val templateApk = extractTemplate(work)
//            val minSdk =
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) 34 else 31
//
//            onProgress(Progress.BuildingOverlay)
//            val tables = MonetTables.load()
//            val builder = MonetOverlayBuilder(
//                tables = tables,
//                templateApk = templateApk,
//                isNightCapable = true
//            )
//            val unsigned = File(work, "overlay-unsigned.apk")
//            val buildResult = builder.build(unsigned)
//
//            onProgress(Progress.Signing)
//            val signed = File(work, "overlay-signed.apk")
//            MonetApkSigner.sign(unsigned, signed, minSdk)
//
//            onProgress(Progress.Packaging)
//            MonetModulePackager.pack(signed, outputZip)
//
//            // 清理中间产物, 保留最终 zip。
//            unsigned.delete()
//            signed.delete()
//            templateApk.delete()
//
//            Progress.Done(buildResult, outputZip).also { onProgress(it) }
//        } catch (e: Throwable) {
//            WeLogger.e(TAG, "monet module generation failed", e)
//            Progress.Failed(e).also { onProgress(it) }
//        }
//    }
//
//    private fun extractTemplate(work: File): File {
//        val (assetName, bytes) = MonetOverlayBuilder.templateBytes()
//        val out = File(work, assetName)
//        out.writeBytes(bytes)
//        return out
//    }
//}
