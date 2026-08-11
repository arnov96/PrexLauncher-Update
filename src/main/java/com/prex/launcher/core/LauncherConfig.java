package com.prex.launcher.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** User settings, persisted as JSON in the launcher home. */
public class LauncherConfig {

    public String theme = "modern";          // "modern" | "classic"
    public String username = "";
    public String modloader = "vanilla";     // "vanilla" | "fabric" | "optifine"
    public int memoryMb = 2048;
    public boolean showSnapshots = false;
    public String javaPath = "";             // custom Java executable (empty = use launcher's JVM)
    public String lastVersion = "";
    public boolean performanceMode = true;   // Aikar GC flags for smoother game FPS
    public boolean lowFxMode = false;        // disables UI animations on weak PCs

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static LauncherConfig load() {
        try {
            if (Files.exists(AppPaths.configFile())) {
                LauncherConfig cfg = GSON.fromJson(Files.readString(AppPaths.configFile(), StandardCharsets.UTF_8), LauncherConfig.class);
                if (cfg != null) {
                    if (cfg.memoryMb < 1024) cfg.memoryMb = 2048;
                    return cfg;
                }
            }
        } catch (Exception ignored) {
            // fall through to defaults
        }
        return new LauncherConfig();
    }

    public void save() {
        try {
            Files.createDirectories(AppPaths.base());
            Files.writeString(AppPaths.configFile(), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LauncherLog.log("Could not save config: " + e.getMessage());
        }
    }
}
