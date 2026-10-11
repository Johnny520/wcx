package h.Hchat.hooks.items.script.agent

object ScriptPluginAgentDiff {
    fun between(before: ScriptPluginAgentDraft?, after: ScriptPluginAgentDraft): String {
        if (before == null) {
            return listOf(
                "新增 main.java\n${formatBlock(after.mainJava.lines(), '+')}",
                "新增 info.prop\n${formatBlock(after.infoProp.lines(), '+')}"
            ).joinToString("\n\n").trimEnd()
        }
        return listOf(
            betweenText("main.java", before.mainJava, after.mainJava),
            betweenText("info.prop", before.infoProp, after.infoProp)
        ).filter { it.isNotBlank() }
            .joinToString("\n\n")
            .ifBlank { "无代码变化" }
    }

    private fun betweenText(name: String, before: String, after: String): String {
        if (before == after) return ""
        val oldLines = before.lines()
        val newLines = after.lines()
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) prefix++
        var suffix = 0
        while (
            suffix < oldLines.size - prefix &&
            suffix < newLines.size - prefix &&
            oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]
        ) suffix++
        val oldMiddle = oldLines.subList(prefix, oldLines.size - suffix)
        val newMiddle = newLines.subList(prefix, newLines.size - suffix)
        return buildString {
            append("$name\n")
            append("@@ 行 ${prefix + 1} @@\n")
            append(formatBlock(oldMiddle, '-'))
            append(formatBlock(newMiddle, '+'))
        }.trimEnd()
    }

    private fun formatBlock(lines: List<String>, marker: Char): String {
        val maxLines = 160
        val visible = if (lines.size <= maxLines) lines else {
            lines.take(maxLines / 2) +
                listOf("... (${lines.size - maxLines} 行已折叠) ...") +
                lines.takeLast(maxLines / 2)
        }
        return visible.joinToString("\n") { "$marker $it" } + if (visible.isNotEmpty()) "\n" else ""
    }
}
