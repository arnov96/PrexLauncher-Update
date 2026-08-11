package com.prex.launcher.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Locates Java runtimes and reports their version.
 *
 * Minecraft versions have a required Java major (from the version JSON, e.g.
 * 26.x -> 21, 1.20.5 -> 21, 1.18 -> 17, 1.8 -> 8). If the default Java is too
 * old, {@link #findJavaInRange} scans the common install folders for a newer
 * one, so the game gets a proper JVM instead of dying with
 * "Unrecognized option" / "fatal exception".
 */
public final class JavaFinder {

    private static final Map<String, Integer> MAJOR_CACHE = new ConcurrentHashMap<>();

    private JavaFinder() {}

    /** Default choice: the JVM the launcher itself runs on. Falls back to JAVA_HOME, then PATH. */
    public static String find() {
        String self = selfJava();
        if (self != null) return self;
        String jh = System.getenv("JAVA_HOME");
        if (jh != null && !jh.isBlank()) {
            Path p = Path.of(jh, "bin", exeName());
            if (Files.isExecutable(p)) return p.toString();
        }
        String onPath = onPath();
        if (onPath != null) return onPath;
        return self != null ? self : "java";
    }

    /** Major version of a java executable (17, 21, 8, ...). -1 if unknown. */
    public static int majorOf(String javaExecutable) {
        if (javaExecutable == null) return -1;
        Integer cached = MAJOR_CACHE.get(javaExecutable);
        if (cached != null) return cached;
        int major = detectMajor(javaExecutable);
        MAJOR_CACHE.put(javaExecutable, major);
        return major;
    }

    /** Human description: path + (Java N). */
    public static String describe(String javaExecutable) {
        int m = majorOf(javaExecutable);
        return javaExecutable + (m > 0 ? "  (Java " + m + ")" : "  (version unknown)");
    }

    /** "java 21.0.3" / "openjdk version \"21.0.3\"..." / "java version \"1.8.0_121\"" */
    private static int detectMajor(String javaExecutable) {
        try {
            Process p = new ProcessBuilder(javaExecutable, "-version").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            Matcher m = Pattern.compile("\"(\\d+)(?:\\.(\\d+))?").matcher(out);
            if (m.find()) {
                int major = Integer.parseInt(m.group(1));
                if (major == 1 && m.group(2) != null) major = Integer.parseInt(m.group(2));
                return major;
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * Finds a java executable with major in [minMajor, maxMajor].
     * Prefers the current default; then scans common install folders.
     * Returns null when nothing suitable exists.
     */
    public static String findJavaInRange(int minMajor, int maxMajor) {
        // current default first
        String cur = find();
        int curMajor = majorOf(cur);
        if (curMajor >= minMajor && curMajor <= maxMajor) return cur;

        for (String dir : candidateDirs()) {
            Path exe = Path.of(dir, "bin", exeName());
            if (!Files.isExecutable(exe)) continue;
            int m = majorOf(exe.toString());
            if (m >= minMajor && m <= maxMajor) {
                LauncherLog.log("Found Java " + m + " at " + exe);
                return exe.toString();
            }
        }
        return null;
    }

    /** Convenience: a Java at least {@code minMajor} old. */
    public static String findMajorAtLeast(int minMajor) {
        return findJavaInRange(minMajor, 99);
    }

    /** Convenience: an older Java (for legacy Minecraft, e.g. Java 8). */
    public static String findMajorAtMost(int maxMajor) {
        return findJavaInRange(8, maxMajor);
    }

    private static List<String> candidateDirs() {
        List<String> dirs = new ArrayList<>();
        String pf = System.getenv("ProgramFiles");
        String pf86 = System.getenv("ProgramFiles(x86)");
        String local = System.getenv("LOCALAPPDATA");
        String[] bases = {pf, pf86, local != null ? local + "\\Programs" : null,
                "C:\\Program Files", "C:\\Program Files (x86)"};
        String[] vendors = {"Eclipse Adoptium", "Microsoft", "Java", "Zulu", "BellSoft",
                "AdoptOpenJDK", "Amazon Corretto", "LibericaJDK"};
        for (String base : bases) {
            if (base == null) continue;
            for (String vendor : vendors) {
                Path vdir = Path.of(base, vendor);
                if (Files.isDirectory(vdir)) {
                    try (var s = Files.list(vdir)) {
                        s.filter(Files::isDirectory).forEach(p -> dirs.add(p.toString()));
                    } catch (IOException ignored) {}
                }
            }
        }
        // linux / mac
        for (String base : new String[]{"/usr/lib/jvm", "/Library/Java/JavaVirtualMachines"}) {
            Path vdir = Path.of(base);
            if (Files.isDirectory(vdir)) {
                try (var s = Files.list(vdir)) {
                    s.filter(Files::isDirectory).forEach(p -> dirs.add(p.toString()));
                } catch (IOException ignored) {}
            }
        }
        return dirs;
    }

    private static String selfJava() {
        String home = System.getProperty("java.home");
        if (home == null || home.isBlank()) return null;
        Path p = Path.of(home, "bin", exeName());
        return Files.isExecutable(p) ? p.toString() : null;
    }

    private static String onPath() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : path.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) continue;
            Path p = Path.of(dir, exeName());
            if (Files.isExecutable(p)) return p.toString();
        }
        return null;
    }

    private static String exeName() {
        return System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
    }

    /** Runs `java -version` and returns its stderr text (where java prints its version). */
    public static String versionOf(String javaExecutable) {
        try {
            Process p = new ProcessBuilder(javaExecutable, "-version")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return out.lines().findFirst().orElse("unknown");
        } catch (IOException | InterruptedException e) {
            return "cannot run: " + e.getMessage();
        }
    }
}
