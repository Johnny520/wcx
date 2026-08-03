# ─── Xposed Module ────────────────────────────────────────────────
# Keep Xposed framework entry points
-keep class de.robv.android.xposed.** { *; }
-keep class io.github.libxposed.** { *; }
-keep class com.Johnny.wcx.entry.** { *; }
-keep class com.Johnny.wcx.application.** { *; }

# Keep assets/xposed_init entry
-keepattributes *Annotation*

# ─── Kotlin ────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-keep class kotlin.coroutines.Continuation { *; }
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }
-keepnames class kotlinx.** { *; }

# ─── Serialization ──────────────────────────────────────────────────
-keep class kotlinx.serialization.** { *; }
-keepattributes *Annotation*, InnerClasses
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
-keep class androidx.room.** { *; }

# ─── Compose ────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ─── Reflection ─────────────────────────────────────────────────────
-keep class com.Johnny.wcx.features.** { *; }
-keep class com.Johnny.wcx.hooks.** { *; }
-keep class com.Johnny.wcx.datas.** { *; }
-keepclassmembers class * {
    @com.Johnny.wcx.annotations.* *;
}

# ─── Dependencies ───────────────────────────────────────────────────
-dontwarn com.alibaba.fastjson2.**
-keep class com.alibaba.fastjson2.** { *; }
-dontwarn io.netty.**
-keep class io.netty.** { *; }
-dontwarn com.google.protobuf.**
-keep class com.google.protobuf.** { *; }
-dontwarn com.tencent.wcdb.**
-dontwarn org.slf4j.**
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**

# ─── OkHttp / Ktor ──────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }

# ─── MiuiX / MaterialKolor ──────────────────────────────────────────
-dontwarn com.materialkolor.**
-keep class com.materialkolor.** { *; }
-dontwarn miuix.**
-keep class miuix.** { *; }

# ─── General ────────────────────────────────────────────────────────
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes Exceptions
-dontwarn javax.**
-dontwarn java.lang.invoke.**
-keep class com.tencent.mm.** { *; }