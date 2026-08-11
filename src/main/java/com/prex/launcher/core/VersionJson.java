package com.prex.launcher.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The subset of a Minecraft version JSON (e.g. 1.21.1.json) the launcher needs,
 * plus platform rule evaluation helpers.
 */
public class VersionJson {

    /** Subclass marker: merge fields from {@link #inheritsFrom} before use (Fabric/OptiFine). */
    public String inheritsFrom;

    /** Set by VersionResolver: the vanilla base this version was merged from (null for vanilla). */
    public String baseId;

    public String id;
    public String type;
    public String mainClass;
    public String minecraftArguments;      // legacy versions (<= 1.12)
    public Arguments arguments;            // modern versions (>= 1.13)
    public AssetIndex assetIndex;
    public Downloads downloads;
    public List<Library> libraries;
    public JavaVersion javaVersion;

    public static final String OS_NAME;
    public static final String OS_ARCH;

    static {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) OS_NAME = "windows";
        else if (os.contains("mac")) OS_NAME = "osx";
        else OS_NAME = "linux";
        String arch = System.getProperty("os.arch", "");
        OS_ARCH = arch.contains("64") ? "x86_64" : "x86";
    }

    // ---------------------------------------------------------------- DTOs

    public static class Arguments {
        public List<JsonElement> game;      // String | {rules, value}
        public List<JsonElement> jvm;
    }

    public static class AssetIndex {
        public String id;
        public String url;
        public String sha1;
        public long size;
        public long totalSize;
    }

    public static class Downloads {
        public Download client;
        public Download server;
    }

    public static class Download {
        public String path;               // library artifacts only (e.g. com/google/gson/gson/2.10.1/gson-2.10.1.jar)
        public String sha1;
        public long size;
        public String url;
    }

    public static class JavaVersion {
        public int majorVersion;
    }

    public static class Library {
        public String name;
        public LibraryDownloads downloads;
        public List<Rule> rules;
        public Map<String, String> natives;   // "linux" -> "natives-linux"
        public Extract extract;
    }

    public static class LibraryDownloads {
        public Download artifact;
        public Map<String, Download> classifiers;
    }

    public static class Extract {
        public List<String> exclude;
    }

    public static class Rule {
        public String action;                 // "allow" | "disallow"
        public Os os;
        public Map<String, Boolean> features;
    }

    public static class Os {
        public String name;
        public String arch;
    }

    // ---------------------------------------------------------------- helpers

    /** Flattens an argument list (plain strings + rule-wrapped objects) into strings. */
    public static List<String> flattenArgs(List<JsonElement> args) {
        List<String> out = new ArrayList<>();
        if (args == null) return out;
        for (JsonElement e : args) {
            if (e.isJsonPrimitive()) {
                out.add(e.getAsString());
                continue;
            }
            JsonObject o = e.getAsJsonObject();
            if (!rulesAllow(o.getAsJsonArray("rules"))) continue;
            JsonElement value = o.get("value");
            if (value == null) continue;
            if (value.isJsonArray()) {
                for (JsonElement x : value.getAsJsonArray()) out.add(x.getAsString());
            } else if (value.isJsonPrimitive()) {
                out.add(value.getAsString());
            }
        }
        return out;
    }

    /** Resolves a library's classpath path, even when its JSON has no download URL (Fabric profiles). */
    public static String libraryPath(Library lib) {
        if (lib.downloads != null && lib.downloads.artifact != null
                && lib.downloads.artifact.path != null) {
            return lib.downloads.artifact.path;
        }
        if (lib.name != null) {
            try {
                return MavenResolver.pathFromName(lib.name);
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Rule evaluation for argument objects (JSON form). */
    public static boolean rulesAllow(JsonArray rules) {
        if (rules == null || rules.size() == 0) return true;
        for (JsonElement e : rules) {
            JsonObject r = e.getAsJsonObject();
            if (matchesOs(r.getAsJsonObject("os"), r.get("features"))) {
                return "allow".equals(r.get("action").getAsString());
            }
        }
        return false;
    }

    /** Rule evaluation for libraries (DTO form). */
    public static boolean rulesAllow(List<Rule> rules) {
        if (rules == null || rules.isEmpty()) return true;
        for (Rule r : rules) {
            boolean matches = true;
            if (r.os != null) {
                if (r.os.name != null && !r.os.name.equals(OS_NAME)) matches = false;
                if (r.os.arch != null && !r.os.arch.equals(OS_ARCH)) matches = false;
            }
            if (r.features != null && !r.features.isEmpty()) matches = false; // we enable no features
            if (matches) return "allow".equals(r.action);
        }
        return false;
    }

    private static boolean matchesOs(JsonObject os, JsonElement features) {
        if (os != null) {
            if (os.has("name") && !os.get("name").getAsString().equals(OS_NAME)) return false;
            if (os.has("arch") && !os.get("arch").getAsString().equals(OS_ARCH)) return false;
        }
        if (features != null && features.isJsonObject() && features.getAsJsonObject().size() > 0) return false;
        return true;
    }
}
