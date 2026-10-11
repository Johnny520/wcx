package h.Hchat.hooks.items.script.agent

object ScriptPluginAgentUnifiedPatch {
    data class FileChange(
        val path: String,
        val content: String?,
        val operation: String,
        val sourcePath: String = ""
    )

    data class Plan(val changes: List<FileChange>)

    private data class Hunk(
        val oldStart: Int?,
        val lines: List<String>,
        val endOfFile: Boolean = false
    )

    private data class TextLines(
        val lines: MutableList<String>,
        val trailingNewline: Boolean,
        val separator: String
    ) {
        fun render(): String {
            if (lines.isEmpty()) return ""
            return lines.joinToString(separator) + if (trailingNewline) separator else ""
        }
    }

    fun plan(
        rawPatch: String,
        normalizePath: (String) -> String,
        readText: (String) -> String?
    ): Plan {
        val patch = rawPatch.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')
        val lines = patch.split('\n')
        require(lines.firstOrNull() == "*** Begin Patch") { "补丁必须以 *** Begin Patch 开始" }
        require(lines.lastOrNull() == "*** End Patch") { "补丁必须以 *** End Patch 结束" }

        val overlay = LinkedHashMap<String, String?>()
        val operations = LinkedHashMap<String, FileChange>()
        fun current(path: String): String? {
            return if (overlay.containsKey(path)) overlay[path] else readText(path)
        }
        fun record(change: FileChange) {
            overlay[change.path] = change.content
            operations[change.path] = change
        }

        var index = 1
        while (index < lines.lastIndex) {
            val header = lines[index]
            when {
                header.startsWith("*** Add File: ") -> {
                    val path = normalizePath(header.removePrefix("*** Add File: ").trim())
                    require(current(path) == null) { "新增文件已存在: $path" }
                    index++
                    val body = ArrayList<String>()
                    while (index < lines.lastIndex && !lines[index].startsWith("*** ")) {
                        val line = lines[index++]
                        require(line.startsWith('+')) { "新增文件内容每行必须以 + 开头: $path" }
                        body += line.substring(1)
                    }
                    val content = if (body.isEmpty()) "" else body.joinToString("\n", postfix = "\n")
                    record(FileChange(path, content, "add"))
                }

                header.startsWith("*** Delete File: ") -> {
                    val path = normalizePath(header.removePrefix("*** Delete File: ").trim())
                    require(current(path) != null) { "删除文件不存在: $path" }
                    record(FileChange(path, null, "delete"))
                    index++
                }

                header.startsWith("*** Update File: ") -> {
                    val sourcePath = normalizePath(header.removePrefix("*** Update File: ").trim())
                    val source = current(sourcePath) ?: error("更新文件不存在: $sourcePath")
                    index++
                    var destinationPath = sourcePath
                    if (index < lines.lastIndex && lines[index].startsWith("*** Move to: ")) {
                        destinationPath = normalizePath(lines[index].removePrefix("*** Move to: ").trim())
                        require(destinationPath != sourcePath) { "移动目标与源文件相同: $sourcePath" }
                        require(current(destinationPath) == null) { "移动目标已存在: $destinationPath" }
                        index++
                    }
                    val hunks = ArrayList<Hunk>()
                    var implicitLines: MutableList<String>? = null
                    var implicitEndOfFile = false
                    while (index < lines.lastIndex &&
                        (!lines[index].startsWith("*** ") || lines[index] == "*** End of File")
                    ) {
                        val line = lines[index++]
                        if (line == "*** End of File") {
                            implicitEndOfFile = true
                            break
                        }
                        if (line.startsWith("@@")) {
                            val oldStart = HUNK_HEADER.matchEntire(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            val hunkLines = ArrayList<String>()
                            var endOfFile = false
                            while (index < lines.lastIndex &&
                                !lines[index].startsWith("@@") &&
                                (!lines[index].startsWith("*** ") || lines[index] == "*** End of File")
                            ) {
                                if (lines[index] == "*** End of File") {
                                    endOfFile = true
                                    index++
                                    break
                                }
                                hunkLines += lines[index++]
                            }
                            require(hunkLines.isNotEmpty()) { "补丁区块不能为空: $sourcePath" }
                            validateHunkLines(sourcePath, hunkLines)
                            hunks += Hunk(oldStart, hunkLines, endOfFile)
                        } else {
                            if (implicitLines == null) implicitLines = ArrayList()
                            implicitLines += line
                        }
                    }
                    implicitLines?.takeIf { it.isNotEmpty() }?.let { hunkLines ->
                        require(hunks.isEmpty()) { "带 @@ 的补丁不能混用无标题区块: $sourcePath" }
                        validateHunkLines(sourcePath, hunkLines)
                        hunks += Hunk(null, hunkLines, implicitEndOfFile)
                    }
                    require(hunks.isNotEmpty() || destinationPath != sourcePath) {
                        "更新文件缺少补丁区块: $sourcePath"
                    }
                    val updated = if (hunks.isEmpty()) source else applyHunks(sourcePath, source, hunks)
                    if (destinationPath != sourcePath) {
                        record(FileChange(sourcePath, null, "move-source", sourcePath))
                        record(FileChange(destinationPath, updated, "move", sourcePath))
                    } else {
                        record(FileChange(sourcePath, updated, "update"))
                    }
                }

                header.isBlank() -> index++
                else -> error("未知补丁指令: $header")
            }
        }
        require(operations.isNotEmpty()) { "补丁没有文件变更" }
        return Plan(operations.values.toList())
    }

    private fun validateHunkLines(path: String, lines: List<String>) {
        lines.forEach { line ->
            require(line.startsWith(' ') || line.startsWith('+') || line.startsWith('-') ||
                line == "\\ No newline at end of file"
            ) { "补丁行必须以空格、+ 或 - 开头: $path" }
        }
    }

    private fun applyHunks(path: String, source: String, hunks: List<Hunk>): String {
        val text = parseTextLines(source)
        // 大文件（>500 行）禁用模糊空白匹配，避免 hunk 错位产生异常大 diff
        val largeFile = text.lines.size > 500
        var cursor = 0
        var lineDelta = 0
        hunks.forEachIndexed { hunkIndex, hunk ->
            val oldLines = hunk.lines.filterNot { it.startsWith('+') || it.startsWith("\\ ") }
                .map { it.substring(1) }
            val expected = hunk.oldStart?.let { (it - 1 + lineDelta).coerceAtLeast(0) }
            val position = when {
                hunk.endOfFile && oldLines.isEmpty() -> text.lines.size
                hunk.endOfFile -> findEndMatch(text.lines, oldLines)
                oldLines.isEmpty() -> expected?.coerceAtMost(text.lines.size) ?: cursor.coerceAtMost(text.lines.size)
                expected != null && matchesAt(text.lines, oldLines, expected) -> expected
                else -> findMatch(text.lines, oldLines, cursor, strict = largeFile)
            }
            require(position >= 0) {
                "第 ${hunkIndex + 1} 个补丁区块在 $path 中找不到匹配上下文，请重新读取文件后缩小补丁范围"
            }
            val newLines = replacementLines(text.lines, position, hunk.lines)
            repeat(oldLines.size) { text.lines.removeAt(position) }
            text.lines.addAll(position, newLines)
            cursor = position + newLines.size
            lineDelta += newLines.size - oldLines.size
        }
        return text.render()
    }

    private fun parseTextLines(source: String): TextLines {
        val separator = if (source.contains("\r\n")) "\r\n" else "\n"
        val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
        val trailing = normalized.endsWith('\n')
        val lines = when {
            normalized.isEmpty() -> mutableListOf()
            trailing -> normalized.dropLast(1).split('\n').toMutableList()
            else -> normalized.split('\n').toMutableList()
        }
        return TextLines(lines, trailing, separator)
    }

    private fun findMatch(lines: List<String>, expected: List<String>, start: Int, strict: Boolean = false): Int {
        if (expected.isEmpty()) return start.coerceIn(0, lines.size)
        val max = lines.size - expected.size
        if (max < 0) return -1
        for (index in start.coerceAtLeast(0)..max) {
            if (matchesAt(lines, expected, index)) return index
        }
        // 大文件严格模式禁用模糊空白匹配，避免错位
        if (strict) return -1
        return findUniqueWhitespaceMatch(lines, expected, start.coerceAtLeast(0), max)
    }

    private fun findEndMatch(lines: List<String>, expected: List<String>): Int {
        val position = lines.size - expected.size
        if (matchesAt(lines, expected, position)) return position
        return position.takeIf { it >= 0 && matchesAtIgnoringEdgeWhitespace(lines, expected, it) } ?: -1
    }

    private fun findUniqueWhitespaceMatch(
        lines: List<String>,
        expected: List<String>,
        start: Int,
        end: Int
    ): Int {
        var matched = -1
        for (index in start..end) {
            if (!matchesAtIgnoringEdgeWhitespace(lines, expected, index)) continue
            if (matched >= 0) return -1
            matched = index
        }
        return matched
    }

    private fun matchesAt(lines: List<String>, expected: List<String>, index: Int): Boolean {
        if (index < 0 || index + expected.size > lines.size) return false
        return expected.indices.all { offset -> lines[index + offset] == expected[offset] }
    }

    private fun matchesAtIgnoringEdgeWhitespace(
        lines: List<String>,
        expected: List<String>,
        index: Int
    ): Boolean {
        if (index < 0 || index + expected.size > lines.size) return false
        return expected.indices.all { offset ->
            lines[index + offset].trim() == expected[offset].trim()
        }
    }

    private fun replacementLines(
        source: List<String>,
        position: Int,
        hunkLines: List<String>
    ): List<String> {
        val replacement = ArrayList<String>()
        var sourceOffset = 0
        hunkLines.forEach { line ->
            when {
                line.startsWith(' ') -> {
                    replacement += source[position + sourceOffset]
                    sourceOffset++
                }
                line.startsWith('-') -> sourceOffset++
                line.startsWith('+') -> replacement += line.substring(1)
            }
        }
        return replacement
    }

    private val HUNK_HEADER = Regex("^@@\\s+-(\\d+)(?:,\\d+)?\\s+\\+\\d+(?:,\\d+)?\\s+@@.*$")
}
