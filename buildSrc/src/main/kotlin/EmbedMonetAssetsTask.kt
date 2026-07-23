import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.util.Base64

/**
 * JVM class-file CONSTANT_Utf8 entries are limited to 65535 bytes.
 * Base64 is ASCII (1 byte/char), so we cap each part well under that.
 */
private const val MAX_PART_LENGTH = 60000

/**
 * Embeds the Monet overlay build inputs (template APKs, tables, magisk scripts) as base64
 * string constants, decoded to [ByteArray] at runtime.
 *
 * WeKit runs inside WeChat's process, where `application.assets` resolves to the HOST's assets,
 * not the module's — so module assets are unreachable. Embedding as class constants (like
 * [EmbedErudaTask] / [EmbedAboutLibrariesTask]) is the reliable way to ship binary blobs.
 */
abstract class EmbedMonetAssetsTask : DefaultTask() {
    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val namespace: Property<String>

    @TaskAction
    fun generate() {
        val pkg = namespace.get()
        val inDir = inputDir.get().asFile
        val outDir = outputDir.get().asFile
        val outputFile =
            outDir.resolve("${pkg.replace(".", "/")}/features/items/beautify/monet/MonetEmbeddedAssets.kt")

        // (asset file name -> kotlin accessor name)
        val entries = listOf(
            "template_api34.apk" to "TEMPLATE_API34",
            "template_api31.apk" to "TEMPLATE_API31",
            "monet_tables.json" to "MONET_TABLES_JSON",
            "customize.sh" to "CUSTOMIZE_SH",
            "update-binary" to "UPDATE_BINARY",
            "updater-script" to "UPDATER_SCRIPT",
        )

        val sb = StringBuilder()
        sb.appendLine("package $pkg.features.items.beautify.monet")
        sb.appendLine()
        sb.appendLine("import java.util.Base64")
        sb.appendLine()
        sb.appendLine("/**")
        sb.appendLine(" * 由 EmbedMonetAssetsTask 生成: 把莫奈 overlay 的构建输入以 base64 字面值内嵌,")
        sb.appendLine(" * 运行时解码为 ByteArray。模块进程内无法访问自身 assets, 故内嵌。")
        sb.appendLine(" */")
        sb.appendLine("@Suppress(\"unused\", \"SpellCheckingInspection\")")
        sb.appendLine("object MonetEmbeddedAssets {")
        sb.appendLine()

        val accessors = ArrayList<Pair<String, Int>>()

        for ((fileName, accessor) in entries) {
            val file = inDir.resolve(fileName)
            require(file.isFile) { "missing embedded monet input: $file" }
            val b64 = Base64.getEncoder().encodeToString(file.readBytes())
            val partCount = (b64.length + MAX_PART_LENGTH - 1) / MAX_PART_LENGTH

            for (i in 0 until partCount) {
                val start = i * MAX_PART_LENGTH
                val end = minOf(start + MAX_PART_LENGTH, b64.length)
                sb.appendLine("    private const val ${accessor}_$i = \"${b64.substring(start, end)}\"")
            }
            accessors += accessor to partCount
        }

        sb.appendLine()
        for ((accessor, partCount) in accessors) {
            val joined = (0 until partCount).joinToString(" + ") { "${accessor}_$it" }
            sb.appendLine("    val $accessor: ByteArray by lazy { Base64.getDecoder().decode($joined) }")
        }

        sb.appendLine("}")

        outputFile.parentFile.mkdirs()
        outputFile.writeText(sb.toString())
    }
}
