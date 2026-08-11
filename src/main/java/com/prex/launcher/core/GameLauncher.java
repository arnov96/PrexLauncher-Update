package com.prex.launcher.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Builds the JVM command line for a Minecraft version and spawns the game.
 * Supports both the modern "arguments" format (>= 1.13) and the legacy
 * "minecraftArguments" format (<= 1.12).
 */
public final class GameLauncher {

    public record LaunchCommand(List<String> command, Path gameDir, Path nativesDir) {}

    private static final String BRAND = "Prex";
    private static final String BRAND_VERSION = "1.7.1";

    public LaunchCommand buildCommand(VersionJson v, ManifestService.VersionEntry entry,
                                      String username, String offlineUuid, LauncherConfig cfg) throws IOException {
        AppPaths.ensureAll();
        Path nativesDir = AppPaths.nativesDir(v.id);
        Files.createDirectories(nativesDir);

        // ---- classpath: client jar + every allowed library jar ----
        List<String> classpath = new ArrayList<>();
        Path jar = AppPaths.clientJar(v.baseId != null ? v.baseId : v.id);
        if (!Files.exists(jar)) throw new IOException("Client jar missing for " + v.id + " — please reinstall.");
        classpath.add(jar.toString());
        if (v.libraries != null) {
            for (VersionJson.Library lib : v.libraries) {
                if (!VersionJson.rulesAllow(lib.rules)) continue;
                String path = VersionJson.libraryPath(lib);
                if (path == null) continue;
                Path p = AppPaths.librariesDir().resolve(path);
                if (Files.exists(p)) classpath.add(p.toString());
                else LauncherLog.log("WARN missing library: " + path);
            }
        }

        // ---- JVM arguments ----
        List<String> jvm = new ArrayList<>();
        if (v.arguments != null && v.arguments.jvm != null) {
            for (String s : VersionJson.flattenArgs(v.arguments.jvm)) {
                if (s.equals("-cp") || s.contains("${classpath}")) continue; // we add -cp ourselves
                jvm.add(s);
            }
        }
        if (jvm.stream().noneMatch(a -> a.startsWith("-Djava.library.path"))) {
            jvm.add(0, "-Djava.library.path=" + nativesDir);
        }

        Map<String, String> tokens = tokens(v, entry, username, offlineUuid);

        List<String> cmd = new ArrayList<>();
        cmd.add(resolveJava(cfg, v));
        cmd.add("-Xmx" + cfg.memoryMb + "M");
        cmd.add("-Xms" + Math.min(cfg.memoryMb, 1024) + "M");
        addPerformanceFlags(cmd, cfg, v);
        for (String s : jvm) cmd.add(substitute(s, tokens));
        cmd.add("-cp");
        cmd.add(String.join(File.pathSeparator, classpath));
        cmd.add(v.mainClass != null && !v.mainClass.isBlank() ? v.mainClass : "net.minecraft.client.main.Main");

        List<String> gameArgs;
        if (v.arguments != null && v.arguments.game != null) {
            gameArgs = VersionJson.flattenArgs(v.arguments.game);
        } else if (v.minecraftArguments != null && !v.minecraftArguments.isBlank()) {
            gameArgs = new ArrayList<>(List.of(v.minecraftArguments.trim().split("\\s+")));
        } else {
            gameArgs = List.of();
        }
        for (String g : gameArgs) cmd.add(substitute(g, tokens));

        return new LaunchCommand(cmd, AppPaths.gameDir(), nativesDir);
    }

    /**
     * Aikar-style GC tuning flags — reduce Minecraft's garbage-collection
     * hitches, which is what makes the game feel stuttery. Only applied on
     * Java 17+ and when performance mode is enabled.
     */
    private static void addPerformanceFlags(List<String> cmd, LauncherConfig cfg, VersionJson v) {
        if (!cfg.performanceMode) return;
        int javaMajor = JavaFinder.majorOf(cmd.get(0));
        if (javaMajor < 17) return;   // legacy Java 8 doesn't support all of these
        cmd.add("-XX:+UseG1GC");
        cmd.add("-XX:+ParallelRefProcEnabled");
        cmd.add("-XX:MaxGCPauseMillis=100");
        cmd.add("-XX:+UnlockExperimentalVMOptions");
        cmd.add("-XX:+DisableExplicitGC");
        cmd.add("-XX:+AlwaysPreTouch");
        cmd.add("-XX:G1NewSizePercent=30");
        cmd.add("-XX:G1MaxNewSizePercent=40");
        cmd.add("-XX:G1HeapRegionSize=8M");
        cmd.add("-XX:G1ReservePercent=20");
        cmd.add("-XX:G1HeapWastePercent=5");
        cmd.add("-XX:G1MixedGCCountTarget=4");
        cmd.add("-XX:InitiatingHeapOccupancyPercent=15");
        cmd.add("-XX:G1MixedGCLiveThresholdPercent=90");
        cmd.add("-XX:SurvivorRatio=32");
        cmd.add("-XX:MaxTenuringThreshold=1");
    }

    /** Spawns the game; every stdout line goes to {@code stdoutSink}, exit code to {@code onExit}. */
    public Process launch(LaunchCommand cmd, Consumer<String> stdoutSink, Consumer<Integer> onExit) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd.command());
        pb.directory(cmd.gameDir().toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    try { stdoutSink.accept(line); } catch (Exception ignored) {}
                }
            } catch (IOException ignored) {}
        }, "game-stdout");
        reader.setDaemon(true);
        reader.start();
        p.onExit().thenAccept(pp -> {
            try { onExit.accept(pp.exitValue()); } catch (Exception ignored) {}
        });
        return p;
    }

    // ---------------------------------------------------------------- internals

    private Map<String, String> tokens(VersionJson v, ManifestService.VersionEntry entry,
                                       String username, String offlineUuid) {
        String accessToken = UUID.randomUUID().toString().replace("-", "");
        Map<String, String> t = new HashMap<>();
        t.put("auth_player_name", username);
        t.put("auth_uuid", offlineUuid);
        t.put("auth_access_token", accessToken);
        t.put("auth_session", "token:" + accessToken + ":" + offlineUuid);
        t.put("auth_xuid", "0");
        t.put("user_type", "legacy");
        t.put("user_properties", "{}");
        t.put("user_property_map", "{}");
        t.put("version_name", v.id);
        t.put("version_type", v.type != null ? v.type : "release");
        t.put("game_directory", AppPaths.gameDir().toString());
        t.put("assets_root", AppPaths.assetsDir().toString());
        t.put("assets_index_name", v.assetIndex != null ? v.assetIndex.id : "legacy");
        t.put("natives_directory", AppPaths.nativesDir(v.id).toString());
        t.put("launcher_name", BRAND);
        t.put("launcher_version", BRAND_VERSION);
        t.put("clientid", UUID.randomUUID().toString());
        t.put("resolution_width", "854");
        t.put("resolution_height", "480");
        return t;
    }

    private static String substitute(String arg, Map<String, String> tokens) {
        String out = arg;
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue());
        }
        return out;
    }

    /**
     * Chooses the Java for the game: the configured/current one if its major
     * satisfies the version's requirement, otherwise an auto-found one, or a
     * clear error instead of the JVM's cryptic "Unrecognized option" crash.
     */
    private static String resolveJava(LauncherConfig cfg, VersionJson v) throws IOException {
        String java = javaExecutable(cfg);
        if (Boolean.getBoolean("prex.skipJavaCheck")) return java; // test-only bypass
        int have = JavaFinder.majorOf(java);
        int need = v.javaVersion != null && v.javaVersion.majorVersion > 0
                ? v.javaVersion.majorVersion : 8;

        if (need > have) {
            String better = JavaFinder.findMajorAtLeast(need);
            if (better != null) {
                LauncherLog.log("Java " + have + " is too old for " + v.id
                        + " (needs " + need + ") — using " + better);
                return better;
            }
            throw new IOException("Minecraft " + v.id + " requires Java " + need
                    + "+ but only Java " + have + " was found (" + java + ").\n"
                    + "Install Java " + need + " from https://adoptium.net — "
                    + "Prex will find and use it automatically.");
        }
        if (need <= 16 && have > 16) {
            String older = JavaFinder.findMajorAtMost(16);
            if (older != null) {
                LauncherLog.log("Java " + have + " is too new for " + v.id
                        + " (needs Java " + need + ") — using " + older);
                return older;
            }
            throw new IOException("Minecraft " + v.id + " needs an older Java ("
                    + need + "), but only Java " + have + " was found.\n"
                    + "Install Java " + need + " and set it in Settings → Java path.");
        }
        return java;
    }

    private static String javaExecutable(LauncherConfig cfg) {
        if (cfg.javaPath != null && !cfg.javaPath.isBlank()) return cfg.javaPath;
        return JavaFinder.find();
    }
}
