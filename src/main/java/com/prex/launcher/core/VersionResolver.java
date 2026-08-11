package com.prex.launcher.core;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Resolves Minecraft version JSON files that declare an "inheritsFrom" base
 * (Fabric loader profiles, OptiFine installs) into a complete, launchable
 * version by merging the base version with the child profile:
 *
 *  - libraries:  base + child (child wins on name collision)
 *  - arguments:  base jvm/game args + child args (deduped)
 *  - client jar, asset index, java version: child if present, else base
 *  - mainClass:  child if present, else base
 */
public final class VersionResolver {

    private VersionResolver() {}

    public static VersionJson resolve(VersionJson v, Function<String, VersionJson> loader) {
        return resolve(v, loader, new HashSet<>());
    }

    private static VersionJson resolve(VersionJson v, Function<String, VersionJson> loader, Set<String> seen) {
        if (v.inheritsFrom == null || v.inheritsFrom.isBlank()) return v;
        if (!seen.add(v.inheritsFrom)) {
            throw new IllegalStateException("Circular inheritsFrom: " + v.inheritsFrom);
        }
        VersionJson base = loader.apply(v.inheritsFrom);
        if (base == null) {
            throw new IllegalStateException("Missing base version '" + v.inheritsFrom + "' for " + v.id);
        }
        VersionJson baseResolved = resolve(base, loader, seen);
        return merge(baseResolved, v);
    }

    private static VersionJson merge(VersionJson base, VersionJson child) {
        VersionJson out = new VersionJson();
        out.id = child.id;
        out.baseId = base.id;
        out.type = child.type != null ? child.type : base.type;

        out.mainClass = child.mainClass != null && !child.mainClass.isBlank()
                ? child.mainClass : base.mainClass;

        // arguments: base first, then child, deduped
        if (base.arguments != null || child.arguments != null) {
            out.arguments = new VersionJson.Arguments();
            out.arguments.jvm = mergeArgs(base.arguments != null ? base.arguments.jvm : null,
                    child.arguments != null ? child.arguments.jvm : null);
            out.arguments.game = mergeArgs(base.arguments != null ? base.arguments.game : null,
                    child.arguments != null ? child.arguments.game : null);
        }

        out.minecraftArguments = child.minecraftArguments != null
                ? child.minecraftArguments : base.minecraftArguments;

        // libraries: base then child, child replaces same-name entries
        Map<String, VersionJson.Library> libs = new LinkedHashMap<>();
        if (base.libraries != null) {
            for (VersionJson.Library l : base.libraries) {
                if (l.name != null) libs.put(l.name, l);
            }
        }
        if (child.libraries != null) {
            for (VersionJson.Library l : child.libraries) {
                if (l.name != null) libs.put(l.name, l);
            }
        }
        out.libraries = new ArrayList<>(libs.values());

        out.downloads = child.downloads != null ? child.downloads : base.downloads;
        out.assetIndex = child.assetIndex != null ? child.assetIndex : base.assetIndex;
        out.javaVersion = child.javaVersion != null ? child.javaVersion : base.javaVersion;
        return out;
    }

    private static List<JsonElement> mergeArgs(List<JsonElement> base, List<JsonElement> child) {
        List<JsonElement> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (base != null) for (JsonElement e : base) { out.add(e); seen.add(e.toString()); }
        if (child != null) for (JsonElement e : child) {
            if (seen.add(e.toString())) out.add(e);
        }
        return out;
    }
}
