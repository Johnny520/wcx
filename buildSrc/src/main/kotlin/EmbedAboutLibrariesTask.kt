import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * JVM class-file CONSTANT_Utf8 entries are limited to 65535 bytes.
 * For ASCII JSON (1 byte/char), the raw-content ceiling is 65535 chars.
 * We use 64000 as a safe per-part limit to stay well under.
 */
private const val MAX_PART_LENGTH = 64000

abstract class EmbedAboutLibrariesTask : DefaultTask() {
    @get:InputFile
    abstract val inputFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val namespace: Property<String>

    @TaskAction
    fun generate() {
        val jsonContent = inputFile.get().asFile.readText()
        val pkg = namespace.get()
        val outDir = outputDir.get().asFile
        val outputFile = outDir.resolve("${pkg.replace(".", "/")}/aboutlibraries/AboutLibrariesProvider.kt")

        // Escape $ → ${'$'} for Kotlin raw string to prevent accidental interpolation
        val dollar = '$'
        val escaped = jsonContent.replace("$", "${dollar}{'${dollar}'}")

        val partCount = (escaped.length + MAX_PART_LENGTH - 1) / MAX_PART_LENGTH

        val sb = StringBuilder()
        sb.appendLine("package $pkg.aboutlibraries")
        sb.appendLine()
        sb.appendLine("object AboutLibrariesProvider {")

        // Write each part as a private const val
        for (i in 0 until partCount) {
            val start = i * MAX_PART_LENGTH
            val end = minOf(start + MAX_PART_LENGTH, escaped.length)
            val part = escaped.substring(start, end)
            sb.appendLine("    private const val PART_$i = \"\"\"$part\"\"\"")
        }

        // Combined lazy val
        val parts = (0 until partCount).joinToString(" + ") { "PART_$it" }
        sb.appendLine()
        sb.appendLine("    val ABOUT_LIBRARIES_JSON: String by lazy { $parts }")
        sb.appendLine("}")

        outputFile.parentFile.mkdirs()
        outputFile.writeText(sb.toString())
    }
}
