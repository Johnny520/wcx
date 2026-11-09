# ─── Xposed Module Entry Points ────────────────────────────────────
# These MUST be kept with original names — Xposed framework loads them
-keep class de.robv.android.xposed.** { *; }
-keep class io.github.libxposed.** { *; }
-keep class com.Johnny.wcx.entry.** { *; }
-keep class com.Johnny.wcx.application.** { *; }

# ─── Feature / Hook Classes ─────────────────────────────────────────
# Keep class structure (for reflection/Xposed callback) but allow obfuscation
-keep class com.Johnny.wcx.features.** { *; }
-keep,allowobfuscation class com.Johnny.wcx.hooks.** { *; }
-keep,allowobfuscation class com.Johnny.wcx.datas.** { *; }

# Keep annotation-annotated members (used by compile-time processors)
-keepclassmembers,allowobfuscation class * {
    @com.Johnny.wcx.annotations.* *;
}

# ─── Kotlin ────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keep class kotlin.coroutines.Continuation { *; }
-dontwarn kotlinx.coroutines.**

# kotlin-reflect 在运行时反射路径中使用；暂时保留其内建表，避免反射/脚本功能回归。
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

# ─── Serialization ──────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
# 不再全量 keep kotlinx.serialization 运行库。运行时入口由普通可达性分析保留，
# 应用内生成的 serializer 仍由下方规则保留，允许 R8 移除未使用的格式/实现代码。
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.Johnny.wcx.**$$serializer { *; }
-keepclassmembers class com.Johnny.wcx.** {
    *** Companion;
}
-keepclasseswithmembers class com.Johnny.wcx.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ─── Room ───────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ─── Compose ────────────────────────────────────────────────────────
# 不要全量 keep androidx.compose：Compose 依赖自带 consumer rules，
# 全量 keep 会阻止 R8 删除未使用的 UI/运行时实现，显著增大 release APK。
-dontwarn androidx.compose.**

# ─── Third-party (dontwarn only, allow R8 optimization) ──────────────
-dontwarn com.alibaba.fastjson2.**
-dontwarn io.netty.**
-dontwarn com.google.protobuf.**
-dontwarn com.tencent.wcdb.**
-dontwarn org.slf4j.**
-dontwarn org.mozilla.javascript.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn io.ktor.**
-dontwarn com.materialkolor.**
-dontwarn miuix.**
-dontwarn javax.**
-dontwarn java.lang.invoke.**

# ─── WeChat Stubs ───────────────────────────────────────────────────
# 微信 API stub 为 compileOnly；不应打入模块 APK，也无需对其设置 keep 规则。

# ─── Obfuscation Enhancements ───────────────────────────────────────
-repackageclasses
-allowaccessmodification
-overloadaggressively
-useuniqueclassmembernames

# ─── Attributes ─────────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes Exceptions
-keepattributes SourceFile,LineNumberTable

# ─── Keep resource names used in code ───────────────────────────────
-keepclassmembers class **.R$* {
    public static <fields>;
}

# R$plurals 类本身必须保留：R8 会因 R$plurals 成员被常量内联而整体移除该类，
# 但 Kotlin 对 pluralStringResource(R.plurals.*) 的编译引用仍指向它。
-keep class **.R$plurals { *; }
# --- Hchat 脚本子系统迁移：依赖的容错规则 ---
# kavaref-core 部分内部类在运行时按需加载，R8 静态分析缺失（不影响实际功能）
-dontwarn com.highcapable.kavaref.**
# 迁移引入的反射/DexKit 相关库
-dontwarn io.github.skylot.jadx.**
-dontwarn com.android.tools.smali.**
-dontwarn com.github.REAndroid.**
-dontwarn org.jf.dexlib2.**
-dontwarn dev.rikka.ndk.thirdparty.**
-keep class com.highcapable.kavaref.** { *; }
-keep class h.Hchat.** { *; }

-dontwarn com.termux.**
-keep class com.termux.** { *; }

# 内置 rhino jar (app/libs/rhino-45f29f6.jar) 含 fork 的 Kotlin 可空性检测扩展
# (org.mozilla.kotlin.KotlinNullabilityDetector)，其引用 Kotlin 编译器内部 metadata API
# (kotlin.metadata.*)，运行环境不提供该依赖。禁用 R8 缺失类告警，不影响常规 JS 功能。
-dontwarn kotlin.metadata.**
-dontwarn org.mozilla.kotlin.**
# ─── Hchat 脚本子系统：R8 不可见的「按类名解析」引用 ─────────────────
# BeanShell 用户脚本引擎：脚本与 API 前置代码在运行时按【原始类名】解析类，
# R8 静态分析看不到这类引用，会把类重命名/裁掉，表现为
# 「Class: Xxx not found in namespace」导致所有插件加载失败。
-keep class bsh.** { *; }
# 脚本 API 前置代码（ScriptPluginRuntime 内以字符串 eval）按名导入的类
-keep class org.luckypray.dexkit.DexKitBridge { *; }
-keep class me.hd.wauxv.** { *; }
# 脚本 ABI 强引用锚点：以真实类型引用这些只被脚本按类名引用的类，避免 R8 裁剪/改名
-keep class h.Hchat.hooks.items.script.ScriptPluginAbi { *; }
-keep class me.hd.wauxv.plugin.api.callback.** { *; }
-keep class me.hd.wauxv.data.bean.** { *; }
-keep class me.hd.wauxv.data.bean.info.** { *; }
-keep class me.yun.silk.** { *; }
-keep class me.yun.silk.utils.** { *; }
# Android Xml API 返回系统 XmlPullParser 实现，接口 ABI 不能重命名或收窄
-keep class org.xmlpull.v1.** { *; }
# Miuix Nav/Compose 从库字节码解析该 composition local
-keep class androidx.lifecycle.viewmodel.compose.** { *; }
# Hchat 反射访问的微信内部类字段名（R8 改名后反射取不到）
-keepclassmembers class * {
    java.lang.String e;
    java.lang.String f;
    java.lang.String h;
    java.lang.String i;
    java.lang.String m;
    java.lang.String d;
}
