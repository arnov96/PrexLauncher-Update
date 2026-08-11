package com.prex.launcher.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * All filesystem locations used by the launcher.
 *
 * Root: <user.home>/.prex-launcher  (override with -Darenacraft.home=/some/path for dev)
 *   ├─ game/            <- acts as .minecraft (saves, options, versions, libraries, assets, mods)
 *   ├─ cache/           <- version manifest cache
 *   ├─ logs/            <- launcher.log + game.log + crash.log
 *   └─ config.json      <- user settings
 *
 * On first run after the rename, data from the old .arenacraft-launcher
 * folder is migrated automatically so nothing needs to be re-downloaded.
 */
public final class AppPaths {

    private static final Path BASE;

    static {
        String prop = System.getProperty("arenacraft.home");
        if (prop != null && !prop.isBlank()) {
            BASE = Path.of(prop);
        } else {
            Path home = Path.of(System.getProperty("user.home", "."));
            Path newDir = home.resolve(".prex-launcher");
            Path oldDir = home.resolve(".arenacraft-launcher");
            if (!Files.exists(newDir) && Files.exists(oldDir)) {
                migrate(oldDir, newDir);
            }
            BASE = newDir;
        }
    }

    private AppPaths() {}

    /** Moves (or copies) old launcher data into the new .prex-launcher folder. */
    private static void migrate(Path oldDir, Path newDir) {
        try {
            Files.move(oldDir, newDir);
            System.err.println("Prex: migrated data from " + oldDir + " to " + newDir);
        } catch (Exception e) {
            try {
                copyRecursive(oldDir, newDir);
                System.err.println("Prex: copied data from " + oldDir + " to " + newDir);
            } catch (Exception ignored) {
                System.err.println("Prex: could not migrate old data (" + e.getMessage() + ")");
            }
        }
    }

    private static void copyRecursive(Path from, Path to) throws IOException {
        try (var stream = Files.walk(from)) {
            for (Path f : (Iterable<Path>) stream::iterator) {
                Path dest = to.resolve(from.relativize(f).toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(f, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    public static Path base()           { return BASE; }
    public static Path gameDir()        { return BASE.resolve("game"); }
    public static Path versionsDir()    { return gameDir().resolve("versions"); }
    public static Path librariesDir()   { return gameDir().resolve("libraries"); }
    public static Path assetsDir()      { return gameDir().resolve("assets"); }
    public static Path assetsIndexesDir(){ return assetsDir().resolve("indexes"); }
    public static Path assetsObjectsDir(){ return assetsDir().resolve("objects"); }
    public static Path cacheDir()       { return BASE.resolve("cache"); }
    public static Path logsDir()        { return BASE.resolve("logs"); }
    public static Path configFile()     { return BASE.resolve("config.json"); }
    public static Path manifestCache()  { return cacheDir().resolve("version_manifest_v2.json"); }
    public static Path launcherLog()    { return logsDir().resolve("launcher.log"); }
    public static Path gameLog()        { return logsDir().resolve("game.log"); }

    public static Path versionDir(String id)    { return versionsDir().resolve(id); }
    public static Path clientJar(String id)     { return versionDir(id).resolve(id + ".jar"); }
    public static Path versionJsonFile(String id){ return versionDir(id).resolve(id + ".json"); }
    public static Path nativesDir(String id)    { return versionDir(id).resolve("natives"); }
    public static Path modsDir()                { return gameDir().resolve("mods"); }

    /** Creates every directory the launcher needs. */
    public static void ensureAll() throws IOException {
        for (Path p : new Path[]{BASE, gameDir(), versionsDir(), librariesDir(), assetsDir(),
                assetsIndexesDir(), assetsObjectsDir(), cacheDir(), logsDir()}) {
            Files.createDirectories(p);
        }
    }
}
