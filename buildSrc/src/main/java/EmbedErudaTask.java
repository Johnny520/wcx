import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;

public abstract class EmbedErudaTask extends DefaultTask {

    private static final int MAX_PART_LENGTH = 64000;

    @Input
    public abstract Property<String> getUrl();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Input
    public abstract Property<String> getNamespace();

    @TaskAction
    public void generate() throws IOException {
        String jsContent = new String(URI.create(getUrl().get()).toURL().openStream().readAllBytes());
        String pkg = getNamespace().get();
        File outDir = getOutputDir().get().getAsFile();
        File outputFile = new File(outDir, pkg.replace(".", "/") + "/eruda/ErudaProvider.kt");

        String escaped = jsContent.replace("$", "${'$'}");

        int partCount = (escaped.length() + MAX_PART_LENGTH - 1) / MAX_PART_LENGTH;

        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(pkg).append(".eruda\n");
        sb.append("\n");
        sb.append("@Suppress(\"unused\")\n");
        sb.append("object ErudaProvider {\n");

        for (int i = 0; i < partCount; i++) {
            int start = i * MAX_PART_LENGTH;
            int end = Math.min(start + MAX_PART_LENGTH, escaped.length());
            String part = escaped.substring(start, end);
            sb.append("    private const val PART_").append(i).append(" = \"\"\"").append(part).append("\"\"\"\n");
        }

        StringBuilder parts = new StringBuilder();
        for (int i = 0; i < partCount; i++) {
            if (i > 0) parts.append(" + ");
            parts.append("PART_").append(i);
        }

        sb.append("\n");
        sb.append("    val ERUDA_JS: String by lazy { ").append(parts).append(" }\n");
        sb.append("}\n");

        outputFile.getParentFile().mkdirs();
        Files.writeString(outputFile.toPath(), sb.toString());
    }
}