package com.prex.launcher.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Downloads a complete Minecraft version: client jar, all libraries, native
 * natives and the full asset pack — with parallel transfers and SHA-1 checks.
 */
public final class GameInstaller {

    public interface Listener {
        void onStatus(String message);
        void onProgress(long doneBytes, long totalBytes, String currentFile);
    }

    public static class DownloadException extends IOException {
        public final List<DownloadManager.Task> failed;
        DownloadException(String message, List<DownloadManager.Task> failed) {
            super(message);
            this.failed = failed;
        }
    }

    private record NativesZip(Path zip, List<String> exclude) {}

    private final DownloadManager downloader = new DownloadManager(8);
    private final Gson gson = new Gson();
    private volatile boolean cancelled;

    public void cancel() { cancelled = true; downloader.cancel(); }
    public boolean wasCancelled() { return cancelled; }

    public void install(VersionJson v, ManifestService.VersionEntry entry, Listener listener) throws IOException {
        cancelled = false;
        AppPaths.ensureAll();
        if (v.downloads == null || v.downloads.client == null)
            throw new IOException("No downloadable client for version " + v.id);
        if (v.assetIndex == null)
            throw new IOException("Version " + v.id + " is too old (pre-1.6) to be supported.");

        Path jar = AppPaths.clientJar(v.baseId != null ? v.baseId : v.id);
        Path indexFile = AppPaths.assetsIndexesDir().resolve(v.assetIndex.id + ".json");

        // ---- phase 1: client jar + asset index + libraries + native jars ----
        List<DownloadManager.Task> tasks = new ArrayList<>();
        long baseTotal = 0;
        tasks.add(new DownloadManager.Task(v.downloads.client.url, jar,
                v.downloads.client.sha1, v.downloads.client.size, "client.jar"));
        baseTotal += v.downloads.client.size;
        tasks.add(new DownloadManager.Task(v.assetIndex.url, indexFile,
                v.assetIndex.sha1, v.assetIndex.size, "assets index"));
        baseTotal += v.assetIndex.size;

        List<NativesZip> nativeZips = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        if (v.libraries != null) {
            for (VersionJson.Library lib : v.libraries) {
                if (!VersionJson.rulesAllow(lib.rules)) continue;
                VersionJson.LibraryDownloads dl = lib.downloads;

                // artifact path/url: may be absent in the JSON (Fabric profiles) —
                // derive the maven path from the library name instead.
                String artPath = VersionJson.libraryPath(lib);
                if (artPath == null) continue;
                Path dest = AppPaths.librariesDir().resolve(artPath);
                String url = dl != null && dl.artifact != null ? dl.artifact.url : null;
                String sha1 = dl != null && dl.artifact != null ? dl.artifact.sha1 : null;
                long size = dl != null && dl.artifact != null ? dl.artifact.size : 0;

                if (url == null) {
                    // already installed (e.g. by OptiFine's installer) or resolvable via maven
                    if (Files.isRegularFile(dest)) continue;
                    url = MavenResolver.resolveOrNull(artPath);
                    if (url == null) { unresolved.add(lib.name); continue; }
                }
                tasks.add(new DownloadManager.Task(url, dest, sha1, size, shortName(lib.name)));
                baseTotal += size;

                // native classifiers (legacy-style zip natives) — only present with a downloads map
                if (dl != null && lib.natives != null && dl.classifiers != null) {
                    String cls = lib.natives.get(VersionJson.OS_NAME);
                    if (cls != null) {
                        VersionJson.Download nd = dl.classifiers.get(cls);
                        if (nd != null && nd.url != null) {
                            Path ndest = AppPaths.librariesDir().resolve(nd.path);
                            tasks.add(new DownloadManager.Task(nd.url, ndest, nd.sha1, nd.size,
                                    shortName(lib.name) + " natives"));
                            baseTotal += nd.size;
                            nativeZips.add(new NativesZip(ndest,
                                    lib.extract != null ? lib.extract.exclude : null));
                        }
                    }
                }
            }
        }

        listener.onStatus("Downloading " + tasks.size() + " core files (" + fmt(baseTotal) + ")…");
        if (!unresolved.isEmpty()) {
            throw new IOException("Cannot locate libraries: " + String.join(", ", unresolved));
        }
        List<DownloadManager.Task> failed = downloader.run(tasks, adapter(listener, 0, baseTotal));
        if (cancelled) throw new IOException("cancelled");
        if (!failed.isEmpty()) throw new DownloadException(describe(failed), failed);

        // ---- phase 2: every asset object ----
        JsonObject indexJson = gson.fromJson(Files.readString(indexFile), JsonObject.class);
        JsonObject objects = indexJson.has("objects") ? indexJson.getAsJsonObject("objects") : new JsonObject();
        List<DownloadManager.Task> assetTasks = new ArrayList<>();
        long assetsTotal = 0;
        for (Map.Entry<String, JsonElement> e : objects.entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            String hash = o.get("hash").getAsString();
            long size = o.get("size").getAsLong();
            String rel = hash.substring(0, 2) + "/" + hash;
            assetTasks.add(new DownloadManager.Task("https://resources.download.minecraft.net/" + rel,
                    AppPaths.assetsObjectsDir().resolve(rel), hash, size, e.getKey()));
            assetsTotal += size;
        }

        listener.onStatus("Downloading " + assetTasks.size() + " assets (" + fmt(assetsTotal) + ")…");
        failed = downloader.run(assetTasks, adapter(listener, baseTotal, baseTotal + assetsTotal));
        if (cancelled) throw new IOException("cancelled");
        if (!failed.isEmpty()) throw new DownloadException(describe(failed), failed);

        // ---- phase 3: unpack native libraries ----
        listener.onStatus("Extracting native libraries…");
        extractNatives(AppPaths.nativesDir(v.id), nativeZips);
        listener.onStatus("Version " + v.id + " is ready.");
    }

    // ---------------------------------------------------------------- helpers

    private DownloadManager.Listener adapter(Listener l, long offset, long total) {
        return new DownloadManager.Listener() {
            @Override
            public void onProgress(long done, long t, String file) {
                l.onProgress(offset + done, total, file);
            }
        };
    }

    private void extractNatives(Path nativesDir, List<NativesZip> zips) throws IOException {
        Files.createDirectories(nativesDir);
        for (NativesZip nz : zips) {
            if (!Files.exists(nz.zip())) continue;
            try (ZipFile zf = new ZipFile(nz.zip().toFile())) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    if (ze.isDirectory()) continue;
                    String name = ze.getName().replace('\\', '/');
                    if (nz.exclude() != null && nz.exclude().contains(name)) continue;
                    Path out = nativesDir.resolve(Path.of(name).getFileName());
                    Files.copy(zf.getInputStream(ze), out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** True when the version needs (re)installing: client jar or any library missing. */
    public static boolean needsInstall(VersionJson v) {
        try {
            Path jar = AppPaths.clientJar(v.baseId != null ? v.baseId : v.id);
            if (!Files.isRegularFile(jar)) return true;
            if (v.libraries != null) {
                for (VersionJson.Library lib : v.libraries) {
                    if (!VersionJson.rulesAllow(lib.rules)) continue;
                    String path = VersionJson.libraryPath(lib);
                    if (path == null) continue;
                    if (!Files.isRegularFile(AppPaths.librariesDir().resolve(path))) return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private static String shortName(String mavenName) {
        if (mavenName == null) return "library";
        String[] parts = mavenName.split(":");
        return parts.length >= 2 ? parts[1] : mavenName;
    }

    private static String describe(List<DownloadManager.Task> failed) {
        int n = Math.min(5, failed.size());
        StringBuilder sb = new StringBuilder(failed.size() + " files failed to download:");
        for (int i = 0; i < n; i++) {
            DownloadManager.Task t = failed.get(i);
            sb.append(" ").append(t.label());
            if (t.url() != null) {
                sb.append(" [").append(t.url().length() > 90 ? t.url().substring(0, 87) + "…" : t.url()).append("]");
            }
            sb.append(",");
        }
        sb.setLength(sb.length() - 1);
        if (failed.size() > n) sb.append(" …");
        return sb.toString();
    }

    private static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }
}
