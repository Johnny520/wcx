package com.Johnny.wcx.dexkit.resolution

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.features.core.BaseFeature
import com.Johnny.wcx.utils.HostInfo
import org.luckypray.dexkit.DexKitBridge

data class DexHostMetadata(
    val versionCode: Long,
    val versionName: String,
    val isGooglePlay: Boolean,
) {
    companion object {
        fun currentAndroidHost() = DexHostMetadata(
            versionCode = HostInfo.versionCode,
            versionName = HostInfo.versionName,
            isGooglePlay = HostInfo.isHostGooglePlay,
        )
    }
}

object DexResolutionContext {
    private data class Session(
        val dexKit: DexKitBridge,
        val host: DexHostMetadata,
    )

    private val current = ThreadLocal<Session?>()

    val dexKit: DexKitBridge
        get() = current.get()?.dexKit ?: error("Dex resolution context is not active")

    val host: DexHostMetadata
        get() = current.get()?.host ?: error("Dex resolution context is not active")

    internal fun <T> withResolutionContext(
        dexKit: DexKitBridge,
        host: DexHostMetadata,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(Session(dexKit, host))
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }
}

fun IResolveDex.resolveAllDex(
    dexKit: DexKitBridge,
    host: DexHostMetadata = DexHostMetadata.currentAndroidHost(),
) = DexResolutionContext.withResolutionContext(dexKit, host) {
    (this as BaseFeature).resolveInlineDex(dexKit)
    resolveDex(dexKit)
}
