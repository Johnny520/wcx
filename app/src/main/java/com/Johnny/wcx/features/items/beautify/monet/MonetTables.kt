//package com.Johnny.wcx.features.items.beautify.monet
//
//import com.Johnny.wcx.utils.WeLogger
//import kotlinx.serialization.SerialName
//import kotlinx.serialization.Serializable
//import kotlinx.serialization.json.Json
//
///**
// * 单条颜色覆盖规则。
// *
// * [light] / [night] 是覆盖目标, 取值为:
// * - `@android:color/system_*` 框架动态色引用 (由 [MonetOverlayBuilder] 在运行时解析为对应 SDK 的框架资源 id), 或
// * - `#aarrggbb` 字面颜色 (半透明遮罩等)。
// */
//@Serializable
//data class MonetColorRule(
//    @SerialName("l") val light: String,
//    @SerialName("n") val night: String,
//    /** 参考版本中该资源的原始颜色值 (#aarrggbb), 用于运行时校验以防混淆名漂移。可能为空。 */
//    @SerialName("v") val expectedValue: String? = null,
//)
//
//@Serializable
//data class MonetVersionTable(
//    @SerialName("colors") val colors: Map<String, MonetColorRule> = emptyMap(),
//)
//
///**
// * 内置的 (微信资源名 -> Monet 目标) 映射数据。
// *
// * 结构:
// * - [versions]: 按 versionCode 精确匹配的表 (由各反编译版本生成)。命中时优先使用。
// * - [generic]: 参考版本 (8.0.69 Play) 的完整语义表, 带 [MonetColorRule.expectedValue]。
// *   用于未知版本兜底: 逐名在运行时微信 arsc 中校验原始色值, 不匹配则丢弃 (RRO 会忽略未匹配的名字)。
// * - [brandByValue]: 品牌/强调色的 (原始色值 -> 目标) 映射。用于未知版本时按色值发现被混淆的语义色名。
// * - [surfByPair]: 会随深浅色翻转的背景色, 按 (浅色值|深色值) 键。用于未知版本时按值对发现被混淆的背景色名。
// *   仅含翻转的色对 (浅≠深), 可证明是背景而非静态文字, 因此按 surface 映射安全。
// */
//@Serializable
//data class MonetTables(
//    @SerialName("versions") val versions: Map<String, MonetVersionTable> = emptyMap(),
//    @SerialName("generic") val generic: Map<String, MonetColorRule> = emptyMap(),
//    @SerialName("brandByValue") val brandByValue: Map<String, MonetColorRule> = emptyMap(),
//    @SerialName("surfByPair") val surfByPair: Map<String, MonetColorRule> = emptyMap(),
//) {
//    companion object {
//        private val TAG = "MonetTables"
//
//        private val json = Json { ignoreUnknownKeys = true }
//
//        fun load(): MonetTables {
//            return json.decodeFromString<MonetTables>(
//                MonetEmbeddedAssets.MONET_TABLES_JSON.decodeToString()
//            ).also {
//                WeLogger.i(
//                    TAG,
//                    "loaded monet tables: versions=${it.versions.keys}, generic=${it.generic.size}"
//                )
//            }
//        }
//    }
//}
