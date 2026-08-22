import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;

public abstract class EmbedErudaTask extends DefaultTask {

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

    private byte[] loadFromNpm() throws IOException {
        try {
            File tmpDir = Files.createTempDirectory("eruda-npm-").toFile();
            tmpDir.deleteOnExit();
            ProcessBuilder pb = new ProcessBuilder("npm", "pack", "eruda@3.4.3");
            pb.directory(tmpDir);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                output = sb.toString();
            }
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new IOException("npm pack failed: " + output);
            }
            String tgzName = null;
            for (String line : output.split("\n")) {
                line = line.trim();
                if (line.endsWith(".tgz")) {
                    tgzName = line;
                    break;
                }
            }
            if (tgzName == null) {
                throw new IOException("Could not find .tgz in npm output: " + output);
            }
            File tgz = new File(tmpDir, tgzName);
            ProcessBuilder tarPb = new ProcessBuilder("tar", "xzf", tgz.getAbsolutePath());
            tarPb.directory(tmpDir);
            tarPb.redirectErrorStream(true);
            int tarExit = tarPb.start().waitFor();
            if (tarExit != 0) {
                throw new IOException("tar extract failed");
            }
            File jsFile = new File(tmpDir, "package/eruda.js");
            if (!jsFile.exists()) {
                throw new IOException("eruda.js not found in npm package");
            }
            System.err.println("Loaded eruda.js from npm package");
            byte[] data = Files.readAllBytes(jsFile.toPath());
            // Clean up temp directory
            jsFile.delete();
            new File(tmpDir, "package").delete();
            tgz.delete();
            tmpDir.delete();
            return data;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted during npm install", e);
        }
    }

    @TaskAction
    public void generate() throws IOException {
        byte[] jsBytes;
        try {
            jsBytes = loadFromClasspath();
        } catch (IOException e) {
            System.err.println("Classpath load failed: " + e.getMessage());
            try {
                System.err.println("Trying npm pack...");
                jsBytes = loadFromNpm();
            } catch (IOException e2) {
                System.err.println("npm pack failed: " + e2.getMessage() + ", falling back to network download");
                jsBytes = downloadWithRetry(getUrl().get());
            }
        }

        // Write the raw minified JS into the variant's assets instead of embedding it as a
        // Kotlin string constant: a 500KB constant inflates the dex, while an assets entry is
        // stored deflated in the APK and read at runtime by ErudaConsole.
        File outDir = getOutputDir().get().getAsFile();
        File outputFile = new File(outDir, "eruda/eruda.min.js");
        outputFile.getParentFile().mkdirs();
        Files.write(outputFile.toPath(), jsBytes);
    }
}