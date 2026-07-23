import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest

abstract class GenerateMethodHashesTask : DefaultTask() {
    @get:InputDirectory
    abstract val sourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val namespace: Property<String>

    @TaskAction
    fun generate() {
        val srcDir = sourceDir.get().asFile
        val outDir = outputDir.get().asFile
        val outputFile = outDir.resolve("${namespace.get().replace(".", "/")}/dexkit/cache/GeneratedMethodHashes.kt")

        val hashMap = mutableMapOf<String, String>()

        // Pre-filter files containing the token to save time, then strictly validate inside
        srcDir.walk().filter { it.isFile && it.extension == "kt" && it.readText().contains("IResolveDex") }.forEach { file ->
            val content = file.readText()

            // Strip comments to avoid matching class/object keywords in KDOC or line comments
            val cleanContent = content
                .replace(Regex("//[^\n]*"), "")       // line comments
                .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")  // block comments (including KDOC)

            val packageName = Regex("""package\s+([\w.]+)""").find(cleanContent)?.groupValues?.get(1)

            // A file may declare helpers before its feature. Select the declaration that actually
            // implements IResolveDex instead of assuming the first class/object is the feature.
            val classRegex = Regex("""\b(?:class|object)\s+(\w+)\b""")
            val declarations = classRegex.findAll(cleanContent).toList()
            val resolveDexDeclaration = declarations.withIndex().firstNotNullOfOrNull { (index, match) ->
                val braceIndex = cleanContent.indexOf('{', match.range.first)
                val closingBraceIndex = cleanContent.indexOf('}', match.range.first)
                val nextDeclarationIndex = declarations.getOrNull(index + 1)?.range?.first ?: cleanContent.length
                if (
                    braceIndex == -1 ||
                    braceIndex >= nextDeclarationIndex ||
                    (closingBraceIndex != -1 && braceIndex >= closingBraceIndex)
                ) {
                    return@firstNotNullOfOrNull null
                }

                val signature = cleanContent.substring(match.range.first, braceIndex)
                if (signature.contains(":") && Regex("""\bIResolveDex\b""").containsMatchIn(signature)) {
                    match
                } else {
                    null
                }
            } ?: return@forEach // Skip files that only import or reference the interface internally
            val className = resolveDexDeclaration.groupValues[1]

            val fullClassName = if (packageName != null) "$packageName.$className" else className
            val blocks = mutableListOf<String>()

            // 1. Extract resolveDex method body if it exists
            val resolveDexMatch = Regex("""override\s+fun\s+resolveDex\s*\(""").find(cleanContent)
            if (resolveDexMatch != null) {
                val start = cleanContent.indexOf('{', resolveDexMatch.range.last)
                if (start != -1) {
                    var count = 0
                    for (i in start until cleanContent.length) {
                        if (cleanContent[i] == '{') count++ else if (cleanContent[i] == '}') count--
                        if (count == 0) {
                            blocks.add(cleanContent.substring(start, i + 1))
                            break
                        }
                    }
                }
            }

            // 2. Extract inline search blocks if they exist (by dexClass, by dexMethod, by dexConstructor)
            val inlineKeywordRegex = Regex("""\bby\s+dex(?:Class|Method|Constructor)\b""")
            val separatorRegex = Regex("""\b(val|fun|private|public|internal|class|object|override)\b""")

            inlineKeywordRegex.findAll(cleanContent).forEach { match ->
                val startScan = match.range.last + 1
                val nextOpenBrace = cleanContent.indexOf('{', startScan)
                if (nextOpenBrace != -1) {
                    val intermediate = cleanContent.substring(startScan, nextOpenBrace)
                    if (!separatorRegex.containsMatchIn(intermediate)) {
                        var count = 0
                        for (i in nextOpenBrace until cleanContent.length) {
                            if (cleanContent[i] == '{') count++ else if (cleanContent[i] == '}') count--
                            if (count == 0) {
                                blocks.add(cleanContent.substring(nextOpenBrace, i + 1))
                                break
                            }
                        }
                    }
                }
            }

            // Guardrail check only applies to actual implementations now
            if (blocks.isEmpty()) {
                error("Class $fullClassName implements IResolveDex but has neither a resolveDex() body nor any inline dex blocks.")
            }

            val combinedBody = blocks.joinToString(separator = "\n")
            val hash = MessageDigest.getInstance("MD5").digest(combinedBody.toByteArray()).joinToString("") { "%02x".format(it) }
            hashMap[fullClassName] = hash
        }

        outputFile.parentFile.mkdirs()
        outputFile.writeText(
            """
            package ${namespace.get()}.dexkit.cache

            object GeneratedMethodHashes {
                val HASHES = mapOf(${hashMap.entries.sortedBy { it.key }.joinToString(", \n") { "\"${it.key}\" to \"${it.value}\"" }})
            }
        """.trimIndent()
        )
    }
}
