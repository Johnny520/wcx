package h.Hchat.hooks.items.script.agent

object ScriptPluginAgentUnifiedDiff {
    private enum class Kind { EQUAL, DELETE, ADD }

    private data class Operation(val kind: Kind, val text: String)

    fun textDiff(path: String, before: String?, after: String?): String {
        val oldLines = lines(before.orEmpty())
        val newLines = lines(after.orEmpty())
        val operations = lineDiff(oldLines, newLines)
        val changedIndices = operations.indices.filter { operations[it].kind != Kind.EQUAL }
        if (changedIndices.isEmpty()) {
            return buildString {
                append("diff --git a/").append(path).append(" b/").append(path).append('\n')
                append("--- a/").append(path).append('\n')
                append("+++ b/").append(path).append('\n')
                append("File bytes changed without line-level text changes")
            }
        }

        val oldBefore = IntArray(operations.size + 1)
        val newBefore = IntArray(operations.size + 1)
        var oldLine = 1
        var newLine = 1
        operations.forEachIndexed { index, operation ->
            oldBefore[index] = oldLine
            newBefore[index] = newLine
            if (operation.kind != Kind.ADD) oldLine++
            if (operation.kind != Kind.DELETE) newLine++
        }
        oldBefore[operations.size] = oldLine
        newBefore[operations.size] = newLine

        return buildString {
            append("diff --git a/").append(path).append(" b/").append(path).append('\n')
            append("--- ").append(if (before == null) "/dev/null" else "a/$path").append('\n')
            append("+++ ").append(if (after == null) "/dev/null" else "b/$path").append('\n')
            hunkRanges(operations, changedIndices).forEach { range ->
                val hunk = operations.subList(range.first, range.last + 1)
                val oldCount = hunk.count { it.kind != Kind.ADD }
                val newCount = hunk.count { it.kind != Kind.DELETE }
                append("@@ -").append(rangeText(oldBefore[range.first], oldCount))
                    .append(" +").append(rangeText(newBefore[range.first], newCount)).append(" @@\n")
                hunk.forEach { operation ->
                    append(
                        when (operation.kind) {
                            Kind.EQUAL -> ' '
                            Kind.DELETE -> '-'
                            Kind.ADD -> '+'
                        }
                    ).append(operation.text).append('\n')
                }
            }
        }.trimEnd()
    }

    private fun lineDiff(oldLines: List<String>, newLines: List<String>): List<Operation> {
        var prefix = 0
        while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) prefix++
        var suffix = 0
        while (suffix < oldLines.size - prefix && suffix < newLines.size - prefix &&
            oldLines[oldLines.lastIndex - suffix] == newLines[newLines.lastIndex - suffix]
        ) suffix++

        val oldMiddle = oldLines.subList(prefix, oldLines.size - suffix)
        val newMiddle = newLines.subList(prefix, newLines.size - suffix)
        val result = ArrayList<Operation>(oldLines.size + newLines.size)
        oldLines.take(prefix).forEach { result += Operation(Kind.EQUAL, it) }
        if (oldMiddle.size.toLong() * newMiddle.size.toLong() <= 2_000_000L) {
            val lcs = Array(oldMiddle.size + 1) { IntArray(newMiddle.size + 1) }
            for (oldIndex in oldMiddle.lastIndex downTo 0) {
                for (newIndex in newMiddle.lastIndex downTo 0) {
                    lcs[oldIndex][newIndex] = if (oldMiddle[oldIndex] == newMiddle[newIndex]) {
                        lcs[oldIndex + 1][newIndex + 1] + 1
                    } else {
                        maxOf(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1])
                    }
                }
            }
            var oldIndex = 0
            var newIndex = 0
            while (oldIndex < oldMiddle.size || newIndex < newMiddle.size) {
                when {
                    oldIndex < oldMiddle.size && newIndex < newMiddle.size &&
                        oldMiddle[oldIndex] == newMiddle[newIndex] -> {
                        result += Operation(Kind.EQUAL, oldMiddle[oldIndex])
                        oldIndex++
                        newIndex++
                    }
                    newIndex < newMiddle.size &&
                        (oldIndex >= oldMiddle.size || lcs[oldIndex][newIndex + 1] > lcs[oldIndex + 1][newIndex]) -> {
                        result += Operation(Kind.ADD, newMiddle[newIndex++])
                    }
                    else -> result += Operation(Kind.DELETE, oldMiddle[oldIndex++])
                }
            }
        } else {
            oldMiddle.forEach { result += Operation(Kind.DELETE, it) }
            newMiddle.forEach { result += Operation(Kind.ADD, it) }
        }
        if (suffix > 0) oldLines.takeLast(suffix).forEach { result += Operation(Kind.EQUAL, it) }
        return result
    }

    private fun hunkRanges(operations: List<Operation>, changedIndices: List<Int>): List<IntRange> {
        val groups = ArrayList<Pair<Int, Int>>()
        var first = changedIndices.first()
        var last = first
        changedIndices.drop(1).forEach { index ->
            val equalBetween = operations.subList(last + 1, index).count { it.kind == Kind.EQUAL }
            if (equalBetween > 6) {
                groups += first to last
                first = index
            }
            last = index
        }
        groups += first to last
        return groups.map { (firstChange, lastChange) ->
            var start = firstChange
            repeat(3) {
                if (start > 0 && operations[start - 1].kind == Kind.EQUAL) start--
            }
            var end = lastChange
            repeat(3) {
                if (end + 1 < operations.size && operations[end + 1].kind == Kind.EQUAL) end++
            }
            start..end
        }
    }

    private fun lines(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        return when {
            normalized.isEmpty() -> emptyList()
            normalized.endsWith('\n') -> normalized.dropLast(1).split('\n')
            else -> normalized.split('\n')
        }
    }

    private fun rangeText(startLine: Int, count: Int): String {
        return if (count == 0) "${(startLine - 1).coerceAtLeast(0)},0" else "$startLine,$count"
    }
}
