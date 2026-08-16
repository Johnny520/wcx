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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public abstract class GenerateMethodHashesTask extends DefaultTask {

    @InputDirectory
    public abstract DirectoryProperty getSourceDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    @Input
    public abstract Property<String> getNamespace();

    @TaskAction
    public void generate() throws IOException, NoSuchAlgorithmException {
        File srcDir = getSourceDir().get().getAsFile();
        File outDir = getOutputDir().get().getAsFile();
        File outputFile = new File(outDir, getNamespace().get().replace(".", "/") + "/dexkit/cache/GeneratedMethodHashes.kt");

        Map<String, String> hashMap = new TreeMap<>();

        Files.walk(srcDir.toPath())
                .filter(p -> p.toFile().isFile() && p.toString().endsWith(".kt"))
                .forEach(file -> {
                    try {
                        String content = Files.readString(file);
                        if (!content.contains("IResolveDex")) return;

                        String cleanContent = content
                                .replaceAll("//[^\n]*", "")
                                .replaceAll("/\\*[\\s\\S]*?\\*/", "");

                        Matcher pkgMatcher = Pattern.compile("package\\s+([\\w.]+)").matcher(cleanContent);
                        String packageName = pkgMatcher.find() ? pkgMatcher.group(1) : null;

                        Pattern classPattern = Pattern.compile("\\b(?:class|object)\\s+(\\w+)\\b");
                        Matcher classMatcher = classPattern.matcher(cleanContent);
                        List<String[]> declarations = new ArrayList<>();
                        while (classMatcher.find()) {
                            declarations.add(new String[]{classMatcher.group(1), String.valueOf(classMatcher.start())});
                        }

                        String className = null;
                        for (int i = 0; i < declarations.size(); i++) {
                            String[] decl = declarations.get(i);
                            int matchStart = Integer.parseInt(decl[1]);
                            int braceIndex = cleanContent.indexOf('{', matchStart);
                            int closingBraceIndex = cleanContent.indexOf('}', matchStart);
                            int nextDeclIndex = i + 1 < declarations.size() ? Integer.parseInt(declarations.get(i + 1)[1]) : cleanContent.length();

                            if (braceIndex == -1 || braceIndex >= nextDeclIndex ||
                                    (closingBraceIndex != -1 && braceIndex >= closingBraceIndex)) {
                                continue;
                            }

                            String signature = cleanContent.substring(matchStart, braceIndex);
                            if (signature.contains(":") && Pattern.compile("\\bIResolveDex\\b").matcher(signature).find()) {
                                className = decl[0];
                                break;
                            }
                        }

                        if (className == null) return;

                        String fullClassName = packageName != null ? packageName + "." + className : className;
                        List<String> blocks = new ArrayList<>();

                        Matcher resolveDexMatch = Pattern.compile("override\\s+fun\\s+resolveDex\\s*\\(").matcher(cleanContent);
                        if (resolveDexMatch.find()) {
                            int start = cleanContent.indexOf('{', resolveDexMatch.end() - 1);
                            if (start != -1) {
                                int count = 0;
                                for (int j = start; j < cleanContent.length(); j++) {
                                    if (cleanContent.charAt(j) == '{') count++;
                                    else if (cleanContent.charAt(j) == '}') count--;
                                    if (count == 0) {
                                        blocks.add(cleanContent.substring(start, j + 1));
                                        break;
                                    }
                                }
                            }
                        }

                        Pattern inlinePattern = Pattern.compile("\\bby\\s+dex(?:Class|Method|Constructor)\\b");
                        Pattern separatorPattern = Pattern.compile("\\b(val|fun|private|public|internal|class|object|override)\\b");
                        Matcher inlineMatcher = inlinePattern.matcher(cleanContent);
                        while (inlineMatcher.find()) {
                            int startScan = inlineMatcher.end();
                            int nextOpenBrace = cleanContent.indexOf('{', startScan);
                            if (nextOpenBrace != -1) {
                                String intermediate = cleanContent.substring(startScan, nextOpenBrace);
                                if (!separatorPattern.matcher(intermediate).find()) {
                                    int count = 0;
                                    for (int j = nextOpenBrace; j < cleanContent.length(); j++) {
                                        if (cleanContent.charAt(j) == '{') count++;
                                        else if (cleanContent.charAt(j) == '}') count--;
                                        if (count == 0) {
                                            blocks.add(cleanContent.substring(nextOpenBrace, j + 1));
                                            break;
                                        }
                                    }
                                }
                            }
                        }

                        if (blocks.isEmpty()) {
                            throw new RuntimeException("Class " + fullClassName + " implements IResolveDex but has neither a resolveDex() body nor any inline dex blocks.");
                        }

                        String combinedBody = String.join("\n", blocks);
                        MessageDigest md = MessageDigest.getInstance("MD5");
                        byte[] digest = md.digest(combinedBody.getBytes());
                        StringBuilder hex = new StringBuilder();
                        for (byte b : digest) {
                            hex.append(String.format("%02x", b));
                        }
                        hashMap.put(fullClassName, hex.toString());
                    } catch (IOException | NoSuchAlgorithmException e) {
                        throw new RuntimeException(e);
                    }
                });

        outputFile.getParentFile().mkdirs();

        String hashEntries = hashMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> "\"" + e.getKey() + "\" to \"" + e.getValue() + "\"")
                .collect(Collectors.joining(", \n"));

        String content = "package " + getNamespace().get() + ".dexkit.cache\n\n" +
                "object GeneratedMethodHashes {\n" +
                "    val HASHES = mapOf(" + hashEntries + ")\n" +
                "}\n";

        Files.writeString(outputFile.toPath(), content);
    }
}