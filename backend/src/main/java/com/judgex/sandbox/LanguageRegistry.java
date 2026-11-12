package com.judgex.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Which languages this box can actually compile/run right now.
 * Probe once at boot, cache the answer, expose it to the UI so the language
 * picker never offers something this machine cannot run.
 *
 * Executables are resolved to absolute paths on purpose: the judge worker may be
 * started from a shell whose PATH is not the one a user sees.
 */
@Component
public class LanguageRegistry {

    private static final Logger log = LoggerFactory.getLogger(LanguageRegistry.class);

    public record Language(
            String id,
            String name,
            String extension,
            String runtime,
            List<String> compileCommand,
            List<String> runCommand,
            String heapOption
    ) {
        public boolean needsCompile() {
            return compileCommand != null && !compileCommand.isEmpty();
        }

        /**
         * Run command carrying the heap cap the problem asked for.
         *
         * <p>In local mode only the runtimes that accept a heap flag on their command line
         * (Java, Node) can be capped; Python/C++ have no equivalent that works on Windows
         * without job objects. The container sandbox enforces the same number for every
         * language through {@code --memory}.
         */
        public List<String> runCommand(int memoryMb) {
            if (heapOption == null || memoryMb <= 0) {
                return runCommand;
            }
            List<String> cmd = new ArrayList<>(runCommand.size() + 1);
            cmd.add(runCommand.get(0));
            cmd.add(heapOption.formatted(memoryMb));
            cmd.addAll(runCommand.subList(1, runCommand.size()));
            return cmd;
        }
    }

    private final Map<String, Language> available = new LinkedHashMap<>();

    public LanguageRegistry() {
        String javac = tool("javac");
        String java = tool("java");
        if (javac != null && java != null) {
            available.put("java", new Language("java", "Java 17", "Main.java",
                    "JDK " + System.getProperty("java.version"),
                    List.of(javac, "-encoding", "UTF-8", "-J-Duser.language=en", "-J-Duser.country=US", "Main.java"),
                    List.of(java, "-Xss64m", "-XX:+UseSerialGC", "-cp", ".", "Main"),
                    "-Xmx%dm"));
        }

        String python = firstExisting("python", pythonCandidates());
        if (python != null) {
            String version = version(python);
            if (version != null) {
                available.put("python", new Language("python", "Python 3", "main.py", version,
                        List.of(python, "-c", "import ast;ast.parse(open('main.py',encoding='utf-8').read())"),
                        List.of(python, "-I", "main.py"), null));
            }
        }

        String node = firstExisting("node", List.of(
                "C:\\Program Files\\nodejs\\node.exe",
                "C:\\Program Files (x86)\\nodejs\\node.exe"));
        if (node != null) {
            String version = version(node);
            if (version != null) {
                available.put("javascript", new Language("javascript", "JavaScript (Node)", "main.js", version,
                        List.of(node, "--check", "main.js"), List.of(node, "main.js"), "--max-old-space-size=%d"));
            }
        }

        String gpp = firstExisting("g++", List.of(
                "C:\\msys64\\mingw64\\bin\\g++.exe",
                "C:\\MinGW\\bin\\g++.exe",
                "C:\\Program Files\\mingw64\\bin\\g++.exe"));
        if (gpp != null) {
            String version = version(gpp);
            if (version != null) {
                available.put("cpp", new Language("cpp", "C++17", "main.cpp", version,
                        List.of(gpp, "-O2", "-std=c++17", "-o", "main.exe", "main.cpp"),
                        List.of("./main.exe"), null));
            }
        }

        log.info("sandbox languages available: {} -> {}", available.keySet(),
                available.values().stream().map(l -> l.id() + "=" + l.runtime()).toList());
    }

    // ---------------------------------------------------------------- probing

    private static String version(String exe) {
        try {
            Process p = new ProcessBuilder(exe, "--version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (p.waitFor(6, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isBlank()) {
                return out.lines().findFirst().orElse("unknown");
            }
        } catch (Exception e) {
            log.debug("version probe failed for {}: {}", exe, e.toString());
        }
        return null;
    }

    private static String firstExisting(String name, List<String> extra) {
        for (String candidate : extra) {
            if (candidate != null && new File(candidate).isFile()) {
                return candidate;
            }
        }
        return which(name);
    }

    /** PATH lookup that also understands MSYS-style "/c/foo" entries. */
    private static String which(String exe) {
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String raw : path.split(File.pathSeparator)) {
            String dir = normalizePathEntry(raw);
            if (dir.isBlank()) {
                continue;
            }
            for (String candidate : executableNames(exe)) {
                File f = new File(dir, candidate);
                if (f.isFile()) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static String tool(String exe) {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        for (String candidate : executableNames(exe)) {
            File f = bin.resolve(candidate).toFile();
            if (f.isFile()) {
                return f.getAbsolutePath();
            }
        }
        return which(exe);
    }

    private static List<String> executableNames(String exe) {
        if (File.separatorChar != '\\') {
            return List.of(exe);
        }
        return List.of(exe + ".exe", exe, exe + ".cmd", exe + ".bat");
    }

    private static String normalizePathEntry(String raw) {
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() > 1) {
            raw = raw.substring(1, raw.length() - 1);
        }
        if (raw.matches("^/[a-zA-Z](/.*)?$")) {
            char drive = Character.toUpperCase(raw.charAt(1));
            String rest = raw.length() > 2 ? raw.substring(2) : "";
            return drive + ":" + rest.replace('/', '\\');
        }
        return raw;
    }

    private static List<String> pythonCandidates() {
        List<String> out = new ArrayList<>();
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            out.addAll(glob(localAppData + "\\Programs\\Python", "python.exe"));
        }
        out.add("C:\\Python313\\python.exe");
        out.add("C:\\Python312\\python.exe");
        out.add("C:\\Python311\\python.exe");
        out.add("C:\\Python310\\python.exe");
        return out;
    }

    private static List<String> glob(String root, String fileName) {
        List<String> found = new ArrayList<>();
        Path dir = Path.of(root);
        if (!Files.isDirectory(dir)) {
            return found;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "Python*")) {
            for (Path p : stream) {
                Path exe = p.resolve(fileName);
                if (Files.isRegularFile(exe)) {
                    found.add(exe.toString());
                }
            }
        } catch (IOException ignored) {
            // ignore
        }
        return found;
    }

    // ---------------------------------------------------------------- lookup

    public Language get(String id) {
        return id == null ? null : available.get(id);
    }

    public boolean supports(String id) {
        return id != null && available.containsKey(id);
    }

    public List<Language> all() {
        return new ArrayList<>(available.values());
    }
}
