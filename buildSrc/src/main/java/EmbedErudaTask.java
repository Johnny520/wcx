import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;

public abstract class EmbedErudaTask extends DefaultTask {

    private static final int MAX_PART_LENGTH = 64000;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 5000;

    private static final String[] FALLBACK_URLS = {
        "https://cdn.jsdelivr.net/npm/eruda@3.4.3/eruda.min.js",
        "https://unpkg.com/eruda@3.4.3/eruda.min.js",
        "https://raw.githubusercontent.com/liriliri/eruda/master/eruda.min.js",
    };

    @Input
    public abstract Property<String> getUrl();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Input
    public abstract Property<String> getNamespace();

    private byte[] downloadWithRetry(String primaryUrl) throws IOException {
        String[] urls = new String[FALLBACK_URLS.length + 1];
        urls[0] = primaryUrl;
        System.arraycopy(FALLBACK_URLS, 0, urls, 1, FALLBACK_URLS.length);

        IOException lastException = null;
        for (String url : urls) {
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    try (InputStream is = URI.create(url).toURL().openStream()) {
                        System.err.println("Successfully downloaded from " + url);
                        return is.readAllBytes();
                    }
                } catch (IOException e) {
                    lastException = e;
                    if (attempt < MAX_RETRIES) {
                        System.err.println("Download attempt " + attempt + " from " + url + " failed, retrying in " + RETRY_DELAY_MS + "ms: " + e.getMessage());
                        try {
                            Thread.sleep(RETRY_DELAY_MS);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new IOException("Interrupted during retry", ie);
                        }
                    }
                }
            }
            System.err.println("All attempts failed for " + url + ", trying next URL...");
        }
        throw new IOException("Failed to download from all URLs after " + (urls.length * MAX_RETRIES) + " total attempts", lastException);
    }

    private byte[] loadFromClasspath() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("eruda.js")) {
            if (is == null) {
                throw new IOException("eruda.js not found in classpath");
            }
            System.err.println("Loaded eruda.js from classpath resource");
            return is.readAllBytes();
        }
    }

    @TaskAction
    public void generate() throws IOException {
        byte[] jsBytes;
        try {
            jsBytes = loadFromClasspath();
        } catch (IOException e) {
            System.err.println("Classpath load failed: " + e.getMessage() + ", falling back to network download");
            jsBytes = downloadWithRetry(getUrl().get());
        }
        String jsContent = new String(jsBytes);
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