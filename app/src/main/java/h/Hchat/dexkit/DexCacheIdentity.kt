package h.Hchat.dexkit

/** 仅按微信包体与补丁身份判断定位缓存是否仍属于同一运行时。 */
object DexCacheIdentity {
    @JvmStatic
    fun sameRuntime(cachedKey: String?, runtimeKey: String?): Boolean {
        if (cachedKey.isNullOrBlank() || runtimeKey.isNullOrBlank()) return false
        if (cachedKey == runtimeKey) return true
        val cached = cachedKey.split('|')
        val runtime = runtimeKey.split('|')
        if (cached.size < 7 || runtime.size < 7) return false
        return cached.take(7) == runtime.take(7)
    }
}
