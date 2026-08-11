package com.prex.launcher.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Manages the game's mods/ folder.
 *
 * A mod is just a .jar file there. "Disabling" renames it to
 * "name.jar.disabled", which Minecraft/Fabric ignore but keep on disk so it can
 * be re-enabled with one click. This is the classic no-database approach.
 */
public final class ModManager {

    public static final String DISABLED_SUFFIX = ".jar.disabled";
    public static final String JAR_SUFFIX = ".jar";

    /** One entry in the mods folder. */
    public record ModEntry(String fileName, Path path, boolean enabled) {
        /** "sodium-fabric-0.5.8.jar" -> "sodium-fabric-0.5.8" */
        public String displayName() {
            String n = fileName;
            if (n.endsWith(DISABLED_SUFFIX)) n = n.substring(0, n.length() - DISABLED_SUFFIX.length());
            else if (n.endsWith(JAR_SUFFIX)) n = n.substring(0, n.length() - JAR_SUFFIX.length());
            return n;
        }
    }

    private ModManager() {}

    /** Lists every mod jar in the mods folder, alphabetically. */
    public static List<ModEntry> list() {
        List<ModEntry> out = new ArrayList<>();
        Path dir = AppPaths.modsDir();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String n = p.getFileName().toString();
                if (n.endsWith(JAR_SUFFIX)) {
                    out.add(new ModEntry(n, p, true));
                } else if (n.endsWith(DISABLED_SUFFIX)) {
                    out.add(new ModEntry(n, p, false));
                }
            });
        } catch (IOException ignored) {}
        out.sort(Comparator.comparing(ModEntry::displayName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /**
     * Enables or disables a mod by renaming the jar. Returns the new entry,
     * or null if the file vanished meanwhile.
     */
    public static ModEntry setEnabled(Path file, boolean enable) throws IOException {
        String n = file.getFileName().toString();
        Path target;
        if (enable) {
            if (n.endsWith(DISABLED_SUFFIX)) {
                target = file.resolveSibling(n.substring(0, n.length() - DISABLED_SUFFIX.length()) + JAR_SUFFIX);
            } else {
                return null; // already enabled
            }
        } else {
            if (n.endsWith(JAR_SUFFIX)) {
                target = file.resolveSibling(n + DISABLED_SUFFIX.substring(JAR_SUFFIX.length()));
            } else {
                return null; // already disabled
            }
        }
        Files.move(file, target);
        return new ModEntry(target.getFileName().toString(), target, enable);
    }

    /** True when a file with this name (in either enabled/disabled form) exists. */
    public static boolean exists(String fileName) {
        String base = stripSuffix(fileName);
        Path dir = AppPaths.modsDir();
        return Files.isRegularFile(dir.resolve(base + JAR_SUFFIX))
                || Files.isRegularFile(dir.resolve(base + DISABLED_SUFFIX));
    }

    /** Removes both the .jar and .disabled form of a mod. */
    public static void remove(String fileName) throws IOException {
        String base = stripSuffix(fileName);
        Path dir = AppPaths.modsDir();
        Files.deleteIfExists(dir.resolve(base + JAR_SUFFIX));
        Files.deleteIfExists(dir.resolve(base + DISABLED_SUFFIX));
    }

    private static String stripSuffix(String fileName) {
        if (fileName.endsWith(DISABLED_SUFFIX)) {
            return fileName.substring(0, fileName.length() - DISABLED_SUFFIX.length());
        }
        if (fileName.endsWith(JAR_SUFFIX)) {
            return fileName.substring(0, fileName.length() - JAR_SUFFIX.length());
        }
        return fileName;
    }
}
