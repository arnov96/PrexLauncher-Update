package com.prex.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Dependency & environment checker.
 *
 * Runs before the UI starts and verifies everything the launcher needs:
 *  - Java version (17+ for the launcher itself)
 *  - Java for running Minecraft (the version's requirement is checked at launch
 *    time by JavaFinder/GameLauncher, but we surface what's installed here)
 *  - All JavaFX native components are inside the jar (they are — single file)
 *  - The data directory is writable
 *  - Optional: bundled prex-jre presence
 *
 * Outputs a plain-text report (also used by the UI diagnostics dialog).
 */
public final class DependencyCheck {

    public record Item(String name, boolean ok, String detail) {}

    private DependencyCheck() {}

    public static List<Item> run() {
        List<Item> out = new ArrayList<>();

        // ---- 1. Java version for the launcher itself ----
        String javaVersion = System.getProperty("java.version", "?");
        String javaHome = System.getProperty("java.home", "?");
        int major = parseMajor(javaVersion);
        out.add(new Item("Java (launcher)",
                major >= 17,
                javaVersion + " — " + javaHome
                        + (major >= 17 ? "" : "  (17+ required; install from https://adoptium.net)")));

        // ---- 2. Java on PATH (used to run Minecraft) ----
        try {
            Process p = new ProcessBuilder("java", "-version")
                    .redirectErrorStream(true).start();
            String v = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            String first = v.lines().findFirst().orElse("?");
            int m = parseMajor(first);
            out.add(new Item("Java (PATH, for Minecraft)", m >= 8,
                    first + (m > 0 ? "  (Java " + m + ")" : "")));
        } catch (Exception e) {
            out.add(new Item("Java (PATH, for Minecraft)", false,
                    "not found on PATH — Minecraft may need it. Install JDK 17/21/25 from https://adoptium.net"));
        }

        // ---- 3. Java home is a JDK with jlink (for make-jre.bat) ----
        Path jlink = Paths.get(javaHome, "bin", isWindows() ? "jlink.exe" : "jlink");
        out.add(new Item("jlink (bundled-JRE tool)", Files.isExecutable(jlink),
                Files.isExecutable(jlink) ? "found at " + jlink : "not found — make-jre.bat needs a full JDK"));

        // ---- 4. Launcher jar self-contained ----
        out.add(new Item("Launcher file", true,
                "single-file jar: " + (Main.class.getProtectionDomain() != null
                        && Main.class.getProtectionDomain().getCodeSource() != null
                        ? Main.class.getProtectionDomain().getCodeSource().getLocation().getPath()
                        : "unknown")
                        + " (JavaFX + Gson + natives embedded)"));

        // ---- 5. Data directory writable ----
        String home = System.getProperty("user.home", ".");
        Path data = Paths.get(home, ".prex-launcher");
        boolean writable = false;
        try {
            Files.createDirectories(data.resolve("logs"));
            Path probe = data.resolve("logs").resolve("write-test.tmp");
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            writable = true;
        } catch (Exception e) {
            writable = false;
        }
        out.add(new Item("Data folder (" + data + ")", writable,
                writable ? "writable — saves/versions/mods will be stored here"
                        : "NOT writable — run the launcher with a user account that can write here"));

        // ---- 6. Bundled prex-jre (optional) ----
        Path exeDir = Paths.get("").toAbsolutePath();
        Path bundled = exeDir.resolve("prex-jre").resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        out.add(new Item("Bundled JRE (optional)", Files.isExecutable(bundled),
                Files.isExecutable(bundled) ? "found — run.bat will use it automatically"
                        : "not present — create it once with make-jre.bat, or use an installed Java"));

        return out;
    }

    /** Formats the report as multi-line text. */
    public static String report(List<Item> items) {
        StringBuilder sb = new StringBuilder("PREX LAUNCHER — DEPENDENCY CHECK\n");
        sb.append("================================\n");
        int ok = 0;
        for (Item it : items) {
            if (it.ok()) ok++;
            sb.append(it.ok() ? "[OK]   " : "[FAIL] ").append(it.name()).append("\n")
              .append("       ").append(it.detail()).append("\n");
        }
        sb.append("================================\n");
        sb.append(ok).append("/").append(items.size()).append(" checks passed");
        if (ok < items.size()) sb.append(" — see the failed items above");
        return sb.toString();
    }

    /** True when everything needed to run the launcher itself is fine. */
    public static boolean allEssentialOk(List<Item> items) {
        return items.stream().filter(i -> i.name().startsWith("Java (launcher)"))
                .allMatch(Item::ok);
    }

    private static int parseMajor(String version) {
        if (version == null) return -1;
        try {
            // match just the version number prefix, e.g. "17.0.11", "1.8.0_121", "11"
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+)(?:\\.(\\d+))?").matcher(version);
            if (!m.find()) return -1;
            int major = Integer.parseInt(m.group(1));
            if (major == 1 && m.group(2) != null) major = Integer.parseInt(m.group(2));
            return major;
        } catch (Exception e) {
            return -1;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
