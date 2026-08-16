package com.Johnny.wcx.agent.workspace

import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine-context element carrying the active [WorkspaceVfs] for a turn. Installed by the caller
 * (WeAgentService) around [com.Johnny.wcx.agent.engine.AgentSessionEngine.runTurn]; the fs
 * `@AgentTool` functions read it via `coroutineContext[VfsContext]`. It propagates into the engine's
 * `channelFlow` because that block runs in the collector's context.
 */
class VfsContext(val vfs: WorkspaceVfs) : AbstractCoroutineContextElement(VfsContext) {
    companion object Key : CoroutineContext.Key<VfsContext>
}

/**
 * Virtual filesystem that the model sees as three fixed roots:
 *  - `/workspace/…` → the session's bound workspace directory (null when unbound).
 *  - `/memory/…`    → the shared memory directory (null when memory is disabled).
 *  - `/cache/…`     → always-on scratch space for large tool outputs that don't fit in context.
 *
 * Path resolution strictly clamps every access inside its root via canonical-path prefix checks, so
 * a model can never escape via `..` or absolute paths. Every operation returns a model-readable
 * string (including on error) rather than throwing, so the engine needn't special-case failures.
 */
class WorkspaceVfs(
    private val workspaceRoot: File?,
    private val memoryRoot: File?,
    private val cacheRoot: File,
) {
    companion object {
        const val WORKSPACE_PREFIX = "/workspace/"
        const val MEMORY_PREFIX = "/memory/"
        const val CACHE_PREFIX = "/cache/"
        const val MEMORY_INDEX = "MEMORY.md"
        private const val WORKSPACE_ROOT_PATH = "/workspace"
        private const val MEMORY_ROOT_PATH = "/memory"
        private const val CACHE_ROOT_PATH = "/cache"
        private const val MAX_READ_BYTES = 256 * 1024
        private const val MAX_SEARCH_MATCHES = 200
    }

    private enum class Root { WORKSPACE, MEMORY, CACHE }

    private class Resolved(val file: File, val root: Root) {
        val isMemoryIndex: Boolean get() = root == Root.MEMORY && file.name == MEMORY_INDEX
    }

    private class VfsException(message: String) : Exception(message)

    val hasWorkspace: Boolean get() = workspaceRoot != null
    val hasMemory: Boolean get() = memoryRoot != null

    // -----------------------------------------------------------------------------------------
    // Public file operations (each returns a model-readable string)
    // -----------------------------------------------------------------------------------------

    fun readFile(path: String): String = guarded {
        val r = resolve(path)
        if (!r.file.exists()) return@guarded "File not found: $path"
        if (r.file.isDirectory) return@guarded "Path is a directory, not a file: $path"
        if (r.file.length() > MAX_READ_BYTES) return@guarded "File too large (> ${MAX_READ_BYTES / 1024} KiB): $path"
        r.file.readText()
    }

    /**
     * Read a line range from a file. Both [startLine] and [endLine] are 1-based and inclusive.
     * Passing null for either means "from the beginning" / "to the end" respectively. Returns the
     * selected lines joined by newlines, prefixed with a header reporting the actual line range and
     * total line count so the model can decide whether to request more.
     */
    fun readFileLines(path: String, startLine: Int?, endLine: Int?): String = guarded {
        val r = resolve(path)
        if (!r.file.exists()) return@guarded "File not found: $path"
        if (r.file.isDirectory) return@guarded "Path is a directory, not a file: $path"
        val allLines = r.file.readLines()
        val total = allLines.size
        val from = ((startLine ?: 1) - 1).coerceIn(0, total)
        val to = ((endLine ?: total) - 1).coerceIn(0, total - 1)
        if (from > to) return@guarded "Empty range: startLine=$startLine endLine=$endLine (file has $total lines)"
        val selected = allLines.subList(from, to + 1)
        "[Lines ${from + 1}–${to + 1} of $total]\n" + selected.joinToString("\n")
    }

    /**
     * Read one or more line ranges from a file using the compact range-spec syntax.
     *
     * Syntax: comma-separated segments, each of which is one of:
     *  - `N`   — single line N (1-based)
     *  - `N:M` — lines N through M inclusive (1-based); N or M may be empty (defaults: 1 / last line);
     *             negative values count from EOF: -1 = last line, -2 = second-to-last, etc.
     *
     * Examples:
     *  - `"1"`               → first line only
     *  - `"1:"`, `"1:-1"`, `":-1"` → entire file
     *  - `"1:2,5:6"`         → lines 1–2 and lines 5–6
     *  - `"1:10,20:30,100:"` → lines 1–10, 20–30, 100 to EOF
     *
     * Lines from overlapping or out-of-order segments are deduplicated and returned in file order.
     * Out-of-bounds indices are silently clamped to the file length; a reversed range (start > end
     * after resolution) is reported as an error.
     */
    fun readFileRanges(path: String, lines: String): String = guarded {
        val r = resolve(path)
        if (!r.file.exists()) return@guarded "File not found: $path"
        if (r.file.isDirectory) return@guarded "Path is a directory, not a file: $path"
        val allLines = r.file.readLines()
        val total = allLines.size
        if (total == 0) return@guarded "(empty file)"

        data class ResolvedRange(val from: Int, val to: Int) // 0-based, inclusive, already clamped

        val resolved = mutableListOf<ResolvedRange>()
        for (seg in lines.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
            val colon = seg.indexOf(':')
            if (colon < 0) {
                // Single-line segment: "N"
                val n = seg.toIntOrNull()
                    ?: return@guarded "Invalid range spec — expected integer in segment '$seg'"
                val idx = (if (n < 0) total + n else n - 1).coerceIn(0, total - 1)
                resolved.add(ResolvedRange(idx, idx))
            } else {
                // Range segment: "N:M", "N:", ":M", ":"
                val startStr = seg.substring(0, colon).trim()
                val endStr = seg.substring(colon + 1).trim()
                val startN = if (startStr.isEmpty()) 1
                else startStr.toIntOrNull()
                    ?: return@guarded "Invalid range spec — expected integer before ':' in segment '$seg'"
                val endN = if (endStr.isEmpty()) -1
                else endStr.toIntOrNull()
                    ?: return@guarded "Invalid range spec — expected integer after ':' in segment '$seg'"
                // Resolve negative indices relative to EOF before range-order check.
                val rawFrom = if (startN < 0) total + startN else startN - 1
                val rawTo = if (endN < 0) total + endN else endN - 1
                if (rawFrom > rawTo) return@guarded "Reversed range in '$seg': start (line ${rawFrom + 1}) is after end (line ${rawTo + 1})"
                resolved.add(ResolvedRange(rawFrom.coerceIn(0, total - 1), rawTo.coerceIn(0, total - 1)))
            }
        }

        if (resolved.isEmpty()) return@guarded "No ranges parsed from spec '$lines'."

        // Collect line indices in file order, deduplicating any overlaps between segments.
        val lineIndices = sortedSetOf<Int>()
        for (rng in resolved) {
            for (i in rng.from..rng.to) lineIndices.add(i)
        }

        val header = resolved.joinToString(", ") { rng ->
            if (rng.from == rng.to) "line ${rng.from + 1}" else "lines ${rng.from + 1}–${rng.to + 1}"
        }
        "[Selected: $header of $total total]\n" + lineIndices.joinToString("\n") { allLines[it] }
    }

    fun listDir(path: String): String = guarded {
        val r = resolve(path)
        if (!r.file.exists()) return@guarded "Directory not found: $path"
        if (!r.file.isDirectory) return@guarded "Path is a file, not a directory: $path"
        val entries = r.file.listFiles()?.sortedBy { it.name } ?: emptyList()
        if (entries.isEmpty()) "(empty directory)"
        else entries.joinToString("\n") { if (it.isDirectory) "${it.name}/" else it.name }
    }

    fun searchFiles(path: String, query: String): String = guarded {
        val r = resolve(path)
        if (!r.file.exists()) return@guarded "Path not found: $path"
        if (query.isEmpty()) return@guarded "Empty search query."
        val needle = query.lowercase()
        val base = r.file
        val matches = ArrayList<String>()
        base.walkTopDown().filter { it.isFile }.forEach { f ->
            if (matches.size >= MAX_SEARCH_MATCHES) return@forEach
            runCatching {
                if (f.length() > MAX_READ_BYTES) return@runCatching
                f.readText().lineSequence().forEachIndexed { i, line ->
                    if (matches.size < MAX_SEARCH_MATCHES && line.lowercase().contains(needle)) {
                        val virtual = virtualOf(r.root, f)
                        matches.add("$virtual:${i + 1}: ${line.trim().take(200)}")
                    }
                }
            }
        }
        if (matches.isEmpty()) "No matches for '$query'." else matches.joinToString("\n")
    }

    fun writeFile(path: String, content: String): String = guarded {
        val r = resolve(path)
        if (r.file.isDirectory) return@guarded "Path is a directory: $path"
        r.file.parentFile?.mkdirs()
        r.file.writeText(content)
        "Wrote ${content.toByteArray().size} bytes to $path"
    }

    fun appendFile(path: String, content: String): String = guarded {
        val r = resolve(path)
        if (r.file.isDirectory) return@guarded "Path is a directory: $path"
        r.file.parentFile?.mkdirs()
        r.file.appendText(content)
        "Appended ${content.toByteArray().size} bytes to $path"
    }

    fun deleteFile(path: String): String = guarded {
        val r = resolve(path)
        if (r.isMemoryIndex) return@guarded "Refusing to delete the protected memory index ${MEMORY_INDEX}."
        if (!r.file.exists()) return@guarded "File not found: $path"
        if (r.file.isDirectory) return@guarded "Refusing to delete a directory: $path"
        if (r.file.delete()) "Deleted $path" else "Failed to delete $path"
    }

    fun moveFile(from: String, to: String): String = guarded {
        val src = resolve(from)
        val dst = resolve(to)
        if (src.isMemoryIndex) return@guarded "Refusing to move the protected memory index ${MEMORY_INDEX}."
        if (!src.file.exists()) return@guarded "Source not found: $from"
        dst.file.parentFile?.mkdirs()
        if (src.file.renameTo(dst.file)) "Moved $from -> $to"
        else {
            // Cross-device fallback: copy + delete.
            runCatching {
                src.file.copyTo(dst.file, overwrite = true)
                src.file.delete()
            }.map { "Moved $from -> $to" }.getOrElse { "Failed to move $from -> $to: ${it.message}" }
        }
    }

    /**
     * Writes [content] to a randomly-named file in `/cache/` and returns its virtual path. Used by
     * network tools when a response exceeds the in-context size cap: they write the full content
     * here and return a truncation notice pointing the model to this path.
     */
    fun writeToCacheSpill(prefix: String, extension: String, content: String): String = guarded {
        cacheRoot.mkdirs()
        val name = "${prefix}_${java.util.UUID.randomUUID().toString().take(8)}.$extension"
        val file = File(cacheRoot, name)
        file.writeText(content)
        "$CACHE_ROOT_PATH/$name"
    }

    // -----------------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------------

    private inline fun guarded(block: () -> String): String =
        try {
            block()
        } catch (e: VfsException) {
            "Error: ${e.message}"
        } catch (e: Throwable) {
            "File operation failed: ${e.message ?: e.javaClass.simpleName}"
        }

    private fun resolve(virtualPath: String): Resolved {
        val normalized = virtualPath.trim()
        val (root, relative) = when {
            normalized == WORKSPACE_ROOT_PATH || normalized.startsWith(WORKSPACE_PREFIX) ->
                Root.WORKSPACE to normalized.removePrefix(WORKSPACE_ROOT_PATH).trimStart('/')

            normalized == MEMORY_ROOT_PATH || normalized.startsWith(MEMORY_PREFIX) ->
                Root.MEMORY to normalized.removePrefix(MEMORY_ROOT_PATH).trimStart('/')

            normalized == CACHE_ROOT_PATH || normalized.startsWith(CACHE_PREFIX) ->
                Root.CACHE to normalized.removePrefix(CACHE_ROOT_PATH).trimStart('/')

            else -> throw VfsException(
                "Path must start with '$WORKSPACE_PREFIX', '$MEMORY_PREFIX', or '$CACHE_PREFIX': '$virtualPath'"
            )
        }

        val rootDir = when (root) {
            Root.WORKSPACE -> workspaceRoot
                ?: throw VfsException("No workspace is bound to this session; '/workspace/' is unavailable.")

            Root.MEMORY -> memoryRoot
                ?: throw VfsException("Memory is disabled; '/memory/' is unavailable.")

            Root.CACHE -> cacheRoot
        }

        val rootPath = rootDir.canonicalFile.path
        val target = File(rootDir, relative).canonicalFile
        if (target.path != rootPath && !target.path.startsWith(rootPath + File.separator)) {
            throw VfsException("Path escapes its root and was rejected: '$virtualPath'")
        }
        return Resolved(target, root)
    }

    private fun virtualOf(root: Root, file: File): String {
        val rootDir = when (root) {
            Root.WORKSPACE -> workspaceRoot!!
            Root.MEMORY -> memoryRoot!!
            Root.CACHE -> cacheRoot
        }.canonicalFile
        val prefix = when (root) {
            Root.WORKSPACE -> WORKSPACE_ROOT_PATH
            Root.MEMORY -> MEMORY_ROOT_PATH
            Root.CACHE -> CACHE_ROOT_PATH
        }
        val rel = file.canonicalFile.path.removePrefix(rootDir.path).replace(File.separatorChar, '/')
        return (prefix + rel).ifEmpty { prefix }
    }
}
