package com.prex.launcher.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongConsumer;
import java.util.function.BooleanSupplier;

/**
 * Client for the Modrinth modding API (https://docs.modrinth.com).
 *
 * Used by the Mods page to search mods, pick a version compatible with the
 * selected Minecraft version + loader, and download the mod jar into the
 * game's mods/ folder. All network access goes through {@link Http} so the
 * same retry/user-agent hardening applies.
 */
public class ModrinthService {

    public static final String API = "https://api.modrinth.com/v2";
    public static final int DEFAULT_LIMIT = 25;

    private final Gson gson = new Gson();

    /** A project as returned by the search endpoint. */
    public record SearchHit(String slug, String title, String description, String project_id,
                            String icon_url, long downloads, List<String> categories) {}

    /** A downloadable file inside a project version. */
    public record FileEntry(String url, String filename, long size, boolean primary) {}

    /** One published version of a project (e.g. "Sodium 0.5.8 for 1.20.1"). */
    public record ProjectVersion(String id, String name, String version_number,
                                 List<String> game_versions, List<String> loaders,
                                 List<FileEntry> files) {
        /** The main file to download for this version. */
        public FileEntry primaryFile() {
            if (files == null) return null;
            for (FileEntry f : files) if (f.primary) return f;
            return files.isEmpty() ? null : files.get(0);
        }
    }

    /** Result of {@link #search}. */
    public record SearchResult(List<SearchHit> hits, long total_hits) {}

    /**
     * Searches Modrinth. loader (e.g. "fabric") and gameVersion (e.g. "1.20.1")
     * are optional; when given they filter results as facets.
     */
    public SearchResult search(String query, String loader, String gameVersion, int limit) throws IOException {
        List<String> facets = new ArrayList<>();
        if (loader != null && !loader.isBlank()) facets.add("[\"categories:" + loader + "\"]");
        if (gameVersion != null && !gameVersion.isBlank()) facets.add("[\"versions:" + gameVersion + "\"]");

        StringBuilder url = new StringBuilder(API + "/search?query=" + enc(query) + "&limit=" + limit);
        if (!facets.isEmpty()) {
            url.append("&facets=").append(enc("[" + String.join(",", facets) + "]"));
        }
        String body = Http.getText(url.toString());
        return gson.fromJson(body, SearchResult.class);
    }

    /**
     * Fetches versions of a project that support the given game version and
     * loader. Newest first. Returns an empty list when none match.
     */
    public List<ProjectVersion> versions(String projectId, String gameVersion, String loader) throws IOException {
        List<String> params = new ArrayList<>();
        if (gameVersion != null && !gameVersion.isBlank()) {
            params.add("game_versions=" + enc("[\"" + gameVersion + "\"]"));
        }
        if (loader != null && !loader.isBlank()) {
            params.add("loaders=" + enc("[\"" + loader + "\"]"));
        }
        String url = API + "/project/" + enc(projectId) + "/version"
                + (params.isEmpty() ? "" : "?" + String.join("&", params));
        String body = Http.getText(url);
        Type type = new TypeToken<List<ProjectVersion>>() {}.getType();
        List<ProjectVersion> list = gson.fromJson(body, type);
        if (list == null) return new ArrayList<>();
        list.sort(Comparator.comparing(ProjectVersion::version_number).reversed());
        return list;
    }

    /**
     * Downloads a mod file into the mods folder (through a .part temp file).
     * Returns the final path, or null when cancelled.
     */
    public Path download(FileEntry file, Path modsDir, LongConsumer onBytes, BooleanSupplier cancelled)
            throws IOException {
        Files.createDirectories(modsDir);
        Path dest = modsDir.resolve(file.filename());
        boolean ok = Http.download(file.url(), dest, null, onBytes, cancelled);
        if (cancelled != null && cancelled.getAsBoolean()) return null;
        return ok ? dest : null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
