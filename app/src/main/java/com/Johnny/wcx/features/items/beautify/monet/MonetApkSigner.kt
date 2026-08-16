//package com.Johnny.wcx.features.items.beautify.monet
//
//import com.android.apksig.KeyConfig
//import com.Johnny.wcx.utils.WeLogger
//import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
//import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
//import org.bouncycastle.jce.provider.BouncyCastleProvider
//import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
//import java.io.File
//import java.math.BigInteger
//import java.security.KeyPairGenerator
//import java.security.Security
//import java.security.cert.X509Certificate
//import java.util.Date
//import com.android.apksig.ApkSigner as AndroidApkSigner
//
///**
// * 用运行时生成的自签名密钥对 overlay APK 做 V2/V3 签名。
// *
// * 静态 RRO overlay 不要求与目标应用同签名, 只要 APK 本身签名有效即可被系统解析安装。
// * 参考模块同样使用自签名 (无 v1 META-INF, 仅 APK Signing Block v2/v3)。
// */
//object MonetApkSigner {
//
//    private const val TAG = "MonetApkSigner"
//
//    init {
//        // libsu/其它模块可能已加载, 幂等添加。
//        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
//            Security.addProvider(BouncyCastleProvider())
//        }
//    }
//
//    /**
//     * 对 [unsignedApk] 签名并输出到 [signedApk]。
//     * @param minSdk overlay 的 minSdkVersion (api34 模板为 34, api31 模板为 31)。
//     */
//    fun sign(unsignedApk: File, signedApk: File, minSdk: Int) {
//        val kpg = KeyPairGenerator.getInstance("RSA")
//        kpg.initialize(2048)
//        val keyPair = kpg.generateKeyPair()
//
//        val now = System.currentTimeMillis()
//        val dn = org.bouncycastle.asn1.x500.X500Name("CN=WeKit Monet Overlay")
//        val notBefore = Date(now - 24L * 60 * 60 * 1000)
//        val notAfter = Date(now + 30L * 365 * 24 * 60 * 60 * 1000) // ~30 年
//
//        val certBuilder = JcaX509v3CertificateBuilder(
//            dn,
//            BigInteger.valueOf(now),
//            notBefore,
//            notAfter,
//            dn,
//            keyPair.public
//        )
//        val contentSigner = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
//        val cert: X509Certificate = JcaX509CertificateConverter()
//            .getCertificate(certBuilder.build(contentSigner))
//
//        val signerConfig = AndroidApkSigner.SignerConfig.Builder(
//            "WeKitMonet",
//            KeyConfig.Jca(keyPair.private),
//            listOf(cert)
//        ).build()
//
//        signedApk.parentFile?.mkdirs()
//        val signer = AndroidApkSigner.Builder(listOf(signerConfig))
//            .setInputApk(unsignedApk)
//            .setOutputApk(signedApk)
//            .setV1SigningEnabled(false)
//            .setV2SigningEnabled(true)
//            .setV3SigningEnabled(true)
//            .setMinSdkVersion(minSdk)
//            .build()
//        signer.sign()
//
//        WeLogger.i(TAG, "signed overlay: ${signedApk.length()} bytes -> $signedApk")
//    }
//}
