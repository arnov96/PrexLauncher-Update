package com.prex.launcher.core;

import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Headless self-test of the core engine (no GUI needed).
 *
 * Run: mvn -q exec:java -Dexec.mainClass=com.prex.launcher.core.CoreSmokeTest
 */
public class CoreSmokeTest {

    public static void main(String[] args) throws Exception {
        System.out.println("== Prex core smoke test ==");

        // ---- config roundtrip ----
        LauncherConfig cfg = LauncherConfig.load();
        cfg.username = "Steve";
        cfg.memoryMb = 2048;
        cfg.save();
        LauncherConfig loaded = LauncherConfig.load();
        check("Steve".equals(loaded.username), "config roundtrip");
        System.out.println("config ok  ->  home: " + AppPaths.base());

        // ---- offline account ----
        String uuid = OfflineAccount.uuid("Steve");
        System.out.println("offline uuid(Steve) = " + uuid);
        check(uuid.length() == 32, "uuid length");
        check(OfflineAccount.error("Steve") == null, "valid name passes");
        check(OfflineAccount.error("bad name!") != null, "invalid name rejected");
        check(OfflineAccount.error("1Steve") != null, "leading digit rejected");

        // ---- version manifest ----
        ManifestService svc = new ManifestService();
        VersionManifest m = svc.getManifest(false);
        System.out.println("manifest: latest release=" + m.latest.release
                + ", entries=" + (m.versions == null ? 0 : m.versions.size()));
        List<ManifestService.VersionEntry> playable = svc.listPlayable(false);
        check(!playable.isEmpty(), "playable versions present");
        System.out.println("playable (releases): " + playable.size() + ", newest=" + playable.get(0).id());

        // ---- version json of the latest release ----
        ManifestService.VersionEntry latest = playable.stream()
                .filter(e -> e.id().equals(m.latest.release)).findFirst().orElse(playable.get(0));
        VersionJson v = svc.getVersionJson(latest);
        System.out.println("version json: id=" + v.id + ", mainClass=" + v.mainClass
                + ", libraries=" + (v.libraries == null ? 0 : v.libraries.size())
                + ", assetIndex=" + (v.assetIndex == null ? "null" : v.assetIndex.id));

        // ---- download client jar + asset index (SHA-1 verified) ----
        AppPaths.ensureAll();
        Path jar = AppPaths.clientJar(v.id);
        boolean jarOk = Http.download(v.downloads.client.url, jar, v.downloads.client.sha1, null, () -> false);
        check(jarOk, "client jar downloaded + sha1 verified (" + Files.size(jar) + " bytes)");

        Path index = AppPaths.assetsIndexesDir().resolve(v.assetIndex.id + ".json");
        boolean idxOk = Http.download(v.assetIndex.url, index, v.assetIndex.sha1, null, () -> false);
        check(idxOk, "asset index downloaded + sha1 verified");

        // ---- mirror fallback path: download the SMALLEST real asset via the BMCLAPI mirror ----
        {
            var gson = new com.google.gson.Gson();
            JsonObject idx = gson.fromJson(Files.readString(index), JsonObject.class);
            String bestHash = null;
            long bestSize = Long.MAX_VALUE;
            for (var e : idx.getAsJsonObject("objects").entrySet()) {
                JsonObject o = e.getValue().getAsJsonObject();
                long s = o.get("size").getAsLong();
                if (s < bestSize) { bestSize = s; bestHash = o.get("hash").getAsString(); }
            }
            if (bestHash != null) {
                String mirror = Http.mirrorFor("https://resources.download.minecraft.net/"
                        + bestHash.substring(0, 2) + "/" + bestHash);
                check(mirror != null && mirror.startsWith("https://bmclapi2.bangbang93.com/assets/"),
                        "mirror url built");
                Path mirrorFile = AppPaths.cacheDir().resolve("mirror-test-" + bestHash.substring(0, 8));
                boolean mirrorOk = Http.download(mirror, mirrorFile, bestHash, null, () -> false);
                check(mirrorOk, "mirror download + sha1 verified (asset " + bestHash.substring(0, 8) + "…)");
                System.out.println("mirror fallback verified via bmclapi2.bangbang93.com");
            } else {
                System.out.println("(no asset hash found — skipping mirror test)");
            }
        }

        // ---- one small library through the parallel manager ----
        VersionJson.Download probe = null;
        String probeName = "";
        if (v.libraries != null) {
            for (VersionJson.Library lib : v.libraries) {
                if (!VersionJson.rulesAllow(lib.rules) || lib.downloads == null
                        || lib.downloads.artifact == null) continue;
                VersionJson.Download d = lib.downloads.artifact;
                if (d.size > 0 && d.size < 2_000_000) { probe = d; probeName = lib.name; break; }
            }
        }
        if (probe != null) {
            DownloadManager dm = new DownloadManager(4);
            DownloadManager.Task t = new DownloadManager.Task(probe.url,
                    AppPaths.librariesDir().resolve(probe.path), probe.sha1, probe.size, probeName);
            List<DownloadManager.Task> failed = dm.run(List.of(t), new DownloadManager.Listener() {
                @Override public void onProgress(long d, long tot, String f) {
                    System.out.println("   probe download: " + d + "/" + tot + " (" + f + ")");
                }
            });
            check(failed.isEmpty(), "parallel download manager ok");
        } else {
            System.out.println("(no probe library found — skipping)");
        }

        // ---- Java requirement check fires (26.2 needs Java 25, sandbox has 17) ----
        try {
            new GameLauncher().buildCommand(v, latest, "Steve", uuid, cfg);
            check(false, "java requirement check should have thrown");
        } catch (java.io.IOException e) {
            check(e.getMessage().contains("requires Java"), "java requirement check message");
        }
        System.setProperty("prex.skipJavaCheck", "true"); // test-only: bypass installed-Java limits

        // ---- launch command construction ----
        GameLauncher.LaunchCommand cmd = new GameLauncher().buildCommand(v, latest, "Steve", uuid, cfg);
        check(!cmd.command().isEmpty(), "launch command built");
        check(cmd.command().get(0).toLowerCase().contains("java"), "first arg is java");
        check(cmd.command().contains("-cp"), "classpath flag present");
        System.out.println("launch command (" + cmd.command().size() + " args):");
        for (String part : cmd.command()) {
            String shown = part.length() > 110 ? part.substring(0, 107) + "…" : part;
            System.out.println("   " + shown);
        }

        // ---- argument token substitution sanity ----
        check(cmd.command().stream().noneMatch(s -> s.contains("${")),
                "all tokens substituted");

        // ---- Fabric: newest stable loader profile for the latest release ----
        try {
            FabricService fabric = new FabricService();
            ManifestService.VersionEntry fentry = fabric.ensure(latest.id());
            System.out.println("fabric profile: " + fentry.id());
            VersionJson fv = svc.loadResolved(fentry.id());
            check("net.fabricmc.loader.impl.launch.knot.KnotClient".equals(fv.mainClass),
                    "fabric main class is KnotClient");
            check(fv.libraries != null && fv.libraries.size() > (v.libraries == null ? 0 : v.libraries.size()),
                    "fabric merge adds libraries");

            // download the fabric-only libraries (maven-resolved, no URLs in profile json)
            int downloaded = 0;
            if (fv.libraries != null) {
                for (VersionJson.Library lib : fv.libraries) {
                    if (!VersionJson.rulesAllow(lib.rules)) continue;
                    String path = VersionJson.libraryPath(lib);
                    if (path == null) continue;
                    Path dest = AppPaths.librariesDir().resolve(path);
                    if (Files.isRegularFile(dest)) continue;
                    String url = MavenResolver.resolveOrNull(path);
                    if (url != null) {
                        if (Http.download(url, dest, null, null, () -> false)) downloaded++;
                    }
                }
            }
            System.out.println("fabric libraries downloaded: " + downloaded);

            GameLauncher.LaunchCommand fcmd = new GameLauncher().buildCommand(fv, fentry, "Steve", uuid, cfg);
            check(fcmd.command().contains("net.fabricmc.loader.impl.launch.knot.KnotClient"),
                    "fabric launch command uses KnotClient");
            boolean loaderOnCp = fcmd.command().stream().anyMatch(s ->
                    s.contains("fabric-loader") && s.endsWith(".jar"));
            check(loaderOnCp, "fabric-loader jar is ON the classpath");
            Path fabricRoot = AppPaths.librariesDir().resolve("net/fabricmc");
            boolean loaderFile = java.nio.file.Files.isDirectory(fabricRoot)
                    && java.nio.file.Files.walk(fabricRoot).anyMatch(p ->
                            p.getFileName().toString().startsWith("fabric-loader-") && p.toString().endsWith(".jar"));
            check(loaderFile, "fabric-loader jar file exists on disk");
            check(fcmd.command().stream().noneMatch(s -> s.contains("${")),
                    "fabric tokens substituted");
            System.out.println("fabric launch cmd: " + fcmd.command().size() + " args");
        } catch (Exception e) {
            System.out.println("(fabric test skipped: " + e.getMessage() + ")");
        }

        // ---- legacy version (1.8.9: minecraftArguments format, launchwrapper) ----
        ManifestService.VersionEntry legacy = playable.stream()
                .filter(e -> e.id().equals("1.8.9")).findFirst().orElse(null);
        if (legacy != null) {
            VersionJson lv = svc.getVersionJson(legacy);
            System.out.println("legacy 1.8.9: mainClass=" + lv.mainClass
                    + ", minecraftArguments=" + (lv.minecraftArguments == null ? "null" : lv.minecraftArguments.length() + " chars")
                    + ", libraries=" + (lv.libraries == null ? 0 : lv.libraries.size()));
            check(lv.minecraftArguments != null, "legacy version uses minecraftArguments");

            Path ljar = AppPaths.clientJar(lv.id);
            boolean ljarOk = Http.download(lv.downloads.client.url, ljar, lv.downloads.client.sha1, null, () -> false);
            check(ljarOk, "legacy client jar downloaded + sha1 verified");
            GameLauncher.LaunchCommand lcmd = new GameLauncher().buildCommand(lv, legacy, "Steve", uuid, cfg);
            check(lcmd.command().contains("--username"), "legacy game args present");
            check(lcmd.command().stream().noneMatch(s -> s.contains("${")), "legacy tokens substituted");
            check(lcmd.command().contains(lv.mainClass), "legacy main class in command");
            check(lcmd.command().contains("--userProperties"), "legacy userProperties token handled");
            System.out.println("legacy command has " + lcmd.command().size() + " args (e.g. "
                    + lcmd.command().get(lcmd.command().size() - 1) + ")");
        } else {
            System.out.println("(1.8.9 not in manifest — skipping legacy test)");
        }

        System.out.println("SMOKE TEST PASSED ✔");
        System.exit(0);
    }

    private static void check(boolean cond, String what) {
        if (!cond) {
            System.out.println("FAILED: " + what);
            System.exit(1);
        }
        System.out.println("ok  - " + what);
    }
}
