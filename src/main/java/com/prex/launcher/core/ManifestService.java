package com.prex.launcher.core;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Fetches and caches Mojang's version manifest and per-version JSON files. */
public class ManifestService {

    public static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    public static final String MANIFEST_MIRROR = "https://bmclapi2.bangbang93.com/mc/game/version_manifest_v2.json";
    private static final long CACHE_TTL_MS = 24L * 3600 * 1000; // re-check every 24h

    private final Gson gson = new Gson();

    public record VersionEntry(String id, String type, String url) {
        @Override
        public String toString() {
            return "snapshot".equals(type) ? id + " (snapshot)" : id;
        }
    }

    /** Returns the version manifest, refreshing the cache at most once per day. */
    public VersionManifest getManifest(boolean forceRefresh) throws IOException {
        Path cache = AppPaths.manifestCache();
        boolean fresh = !forceRefresh && Files.exists(cache)
                && System.currentTimeMillis() - Files.getLastModifiedTime(cache).toMillis() < CACHE_TTL_MS;
        if (!fresh) {
            IOException firstErr = null;
            boolean refreshed = false;
            // try the official endpoint, then the BMCLAPI mirror (some networks block Mojang hosts)
            for (String url : new String[]{MANIFEST_URL, MANIFEST_MIRROR}) {
                try {
                    byte[] body = Http.get(url);
                    Files.createDirectories(cache.getParent());
                    Files.write(cache, body);
                    LauncherLog.log("Version manifest refreshed (" + url + ")");
                    refreshed = true;
                    break;
                } catch (IOException e) {
                    if (firstErr == null) firstErr = e;
                    LauncherLog.log("Manifest fetch failed (" + url + "): " + e.getMessage());
                }
            }
            if (!refreshed) {
                if (Files.exists(cache)) {
                    LauncherLog.log("Manifest refresh failed — using cached version list.");
                } else {
                    throw firstErr != null ? firstErr
                            : new IOException("Could not reach Mojang or the mirror to download the version list.");
                }
            }
        }
        return gson.fromJson(Files.readString(cache, StandardCharsets.UTF_8), VersionManifest.class);
    }

    /** Fetches (once) and caches the full JSON of a specific version. */
    public VersionJson getVersionJson(VersionEntry entry) throws IOException {
        Path file = AppPaths.versionJsonFile(entry.id());
        if (!Files.exists(file)) {
            if (entry.url() == null || entry.url().isBlank()) {
                throw new IOException("Version " + entry.id() + " is not installed locally.");
            }
            byte[] body = Http.get(entry.url());
            Files.createDirectories(file.getParent());
            Files.write(file, body);
        }
        return gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), VersionJson.class);
    }

    /** Loads any version by id: local file first, then the manifest. Null if unknown. */
    public VersionJson loadById(String id) throws IOException {
        Path file = AppPaths.versionJsonFile(id);
        if (Files.exists(file)) {
            return gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), VersionJson.class);
        }
        VersionManifest m = getManifest(false);
        if (m.versions != null) {
            for (VersionManifest.Version v : m.versions) {
                if (v.id.equals(id)) {
                    return getVersionJson(new VersionEntry(v.id, v.type, v.url));
                }
            }
        }
        return null;
    }

    /**
     * Fully resolved version json: merges any inheritsFrom chain (Fabric /
     * OptiFine profiles) into a complete launchable version.
     */
    public VersionJson loadResolved(String id) throws IOException {
        VersionJson v = loadById(id);
        if (v == null) throw new IOException("Unknown version: " + id);
        return VersionResolver.resolve(v, baseId -> {
            try {
                return loadById(baseId);
            } catch (IOException e) {
                return null;
            }
        });
    }

    /** Installed local versions (Fabric / OptiFine profiles), oldest first. */
    public List<VersionEntry> listLocalInstalled() {
        List<VersionEntry> out = new ArrayList<>();
        try (var stream = Files.list(AppPaths.versionsDir())) {
            for (Path d : (Iterable<Path>) stream::iterator) {
                String id = d.getFileName().toString();
                if (Files.isRegularFile(AppPaths.versionJsonFile(id))) {
                    out.add(new VersionEntry(id, "installed", ""));
                }
            }
        } catch (IOException ignored) {}
        out.sort(Comparator.comparing(VersionEntry::id));
        return out;
    }

    /** Playable versions, newest first. Releases always; snapshots only when requested. */
    public List<VersionEntry> listPlayable(boolean includeSnapshots) throws IOException {
        VersionManifest m = getManifest(false);
        List<VersionEntry> out = new ArrayList<>();
        if (m.versions != null) {
            for (VersionManifest.Version v : m.versions) {
                if ("release".equals(v.type) || (includeSnapshots && "snapshot".equals(v.type))) {
                    out.add(new VersionEntry(v.id, v.type, v.url));
                }
            }
        }
        out.sort(Comparator.comparing(VersionEntry::id).reversed());
        return out;
    }
}
