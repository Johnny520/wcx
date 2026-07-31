import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public abstract class EmbedMonetAssetsTask extends DefaultTask {

    private static final int MAX_PART_LENGTH = 60000;

    @InputDirectory
    public abstract DirectoryProperty getInputDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Input
    public abstract Property<String> getNamespace();

    @TaskAction
    public void generate() throws IOException {
        String pkg = getNamespace().get();
        File inDir = getInputDir().get().getAsFile();
        File outDir = getOutputDir().get().getAsFile();
        File outputFile = new File(outDir, pkg.replace(".", "/") + "/features/items/beautify/monet/MonetEmbeddedAssets.kt");

        List<Map.Entry<String, String>> entries = List.of(
                new AbstractMap.SimpleEntry<>("template_api34.apk", "TEMPLATE_API34"),
                new AbstractMap.SimpleEntry<>("template_api31.apk", "TEMPLATE_API31"),
                new AbstractMap.SimpleEntry<>("monet_tables.json", "MONET_TABLES_JSON"),
                new AbstractMap.SimpleEntry<>("customize.sh", "CUSTOMIZE_SH"),
                new AbstractMap.SimpleEntry<>("update-binary", "UPDATE_BINARY"),
                new AbstractMap.SimpleEntry<>("updater-script", "UPDATER_SCRIPT")
        );

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".features.items.beautify.monet\n");
        sb.append("\n");
        sb.append("import java.util.Base64\n");
        sb.append("\n");
        sb.append("/**\n");
        sb.append(" * 由 EmbedMonetAssetsTask 生成: 把莫奈 overlay 的构建输入以 base64 字面值内嵌,\n");
        sb.append(" * 运行时解码为 ByteArray。模块进程内无法访问自身 assets, 故内嵌。\n");
        sb.append(" */\n");
        sb.append("@Suppress(\"unused\", \"SpellCheckingInspection\")\n");
        sb.append("object MonetEmbeddedAssets {\n");
        sb.append("\n");

        List<Map.Entry<String, Integer>> accessors = new ArrayList<>();

        for (Map.Entry<String, String> entry : entries) {
            String fileName = entry.getKey();
            String accessor = entry.getValue();
            File file = new File(inDir, fileName);
            if (!file.isFile()) {
                throw new IllegalArgumentException("missing embedded monet input: " + file);
            }
            String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath()));
            int partCount = (b64.length() + MAX_PART_LENGTH - 1) / MAX_PART_LENGTH;

            for (int i = 0; i < partCount; i++) {
                int start = i * MAX_PART_LENGTH;
                int end = Math.min(start + MAX_PART_LENGTH, b64.length());
                sb.append("    private const val ").append(accessor).append("_").append(i)
                        .append(" = \"").append(b64.substring(start, end)).append("\"\n");
            }
            accessors.add(new AbstractMap.SimpleEntry<>(accessor, partCount));
        }

        sb.append("\n");
        for (Map.Entry<String, Integer> acc : accessors) {
            String accessor = acc.getKey();
            int partCount = acc.getValue();
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < partCount; i++) {
                if (i > 0) joined.append(" + ");
                joined.append(accessor).append("_").append(i);
            }
            sb.append("    val ").append(accessor).append(": ByteArray by lazy { Base64.getDecoder().decode(")
                    .append(joined).append(") }\n");
        }

        sb.append("}\n");

        outputFile.getParentFile().mkdirs();
        Files.writeString(outputFile.toPath(), sb.toString());
    }
}