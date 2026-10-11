package h.Hchat.hooks.items.script.agent

/**
 * 合并兼容 OpenAI 接口的两种流格式：增量片段和截至当前的完整内容。
 */
object ScriptPluginAgentTextMerge {
    fun appendStream(current: String, incoming: String): String {
        if (incoming.isEmpty()) return current
        if (current.isEmpty()) return incoming
        if (incoming == current) return current
        if (incoming.startsWith(current)) return incoming
        if (current.startsWith(incoming)) return current
        return current + incoming
    }

    fun mergeReply(current: String, incoming: String): String {
        return appendStream(current, incoming)
    }
}
