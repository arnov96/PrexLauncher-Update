package com.prex.launcher.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Fabric modloader support via the Fabric Meta API, with automatic fallback
 * to the BMCLAPI mirror (some networks block meta.fabricmc.net).
 *
 * Flow: pick the newest stable loader for the game version, fetch its launch
 * profile JSON (which uses "inheritsFrom"), and store it as a local version
 * the same way the official installers do. VersionResolver merges it with the
 * vanilla version at launch time; libraries without download URLs are resolved
 * through MavenResolver (Fabric maven → Maven Central).
 */
public final class FabricService {

    private static final String[] META_BASES = {
            "https://meta.fabricmc.net/v2",
            "https://bmclapi2.bangbang93.com/fabric-meta/v2"
    };
    private final Gson gson = new Gson();

    /** Fetches from the first reachable base; throws if all fail. */
    private byte[] fetchFromBases(String path) throws IOException {
        IOException last = null;
        for (String base : META_BASES) {
            try {
                return Http.get(base + path);
            } catch (IOException e) {
                last = e;
                LauncherLog.log("Fabric meta unreachable (" + base + path + "): " + e.getMessage());
            }
        }
        throw last != null ? last : new IOException("Could not reach the Fabric API.");
    }

    /** Installs the newest stable Fabric loader for the given Minecraft version. */
    public ManifestService.VersionEntry ensure(String gameVersion) throws IOException {
        JsonArray loaders = gson.fromJson(new String(fetchFromBases("/versions/loader/" + gameVersion), StandardCharsets.UTF_8), JsonArray.class);
        if (loaders == null || loaders.size() == 0) {
            throw new IOException("Fabric does not support Minecraft " + gameVersion + " yet.");
        }
        String loader = null;
        for (JsonElement e : loaders) {
            JsonObject o = e.getAsJsonObject().getAsJsonObject("loader");
            if (o.has("stable") && o.get("stable").getAsBoolean()) { loader = o.get("version").getAsString(); break; }
        }
        if (loader == null) {
            loader = loaders.get(0).getAsJsonObject().getAsJsonObject("loader").get("version").getAsString();
        }

        byte[] profile = fetchFromBases("/versions/loader/" + gameVersion + "/" + loader + "/profile/json");
        JsonObject p = gson.fromJson(new String(profile, StandardCharsets.UTF_8), JsonObject.class);
        String id = p.get("id").getAsString();

        var file = AppPaths.versionJsonFile(id);
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.write(file, profile);
            LauncherLog.log("Fabric " + loader + " profile installed for " + gameVersion + " (" + id + ")");
        }
        return new ManifestService.VersionEntry(id, "installed", "");
    }
}
