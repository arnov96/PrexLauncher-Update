package com.prex.launcher.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OptiFine support.
 *
 * Installs OptiFine by driving OptiFine's own headless installer class
 * (optifine.Installer --installClient), which patches everything correctly
 * for each Minecraft version — including old versions that need the
 * launchwrapper tweaker. The generated version JSON uses "inheritsFrom", so
 * VersionResolver merges it with the vanilla version at launch time.
 */
public final class OptiFineService {

    private static final String DOWNLOADS_URL = "https://optifine.net/downloads";
    private static final Pattern SECTION = Pattern.compile("<h2>Minecraft ([^<]+)</h2>(.*?)(?=<h2>|$)", Pattern.DOTALL);
    private static final Pattern MAIN_ROW = Pattern.compile(
            "downloadLine downloadLineMain.*?colDownload'><a href=\"([^\"]+)\"", Pattern.DOTALL);
    private static final Pattern ADLOADX = Pattern.compile("https?://optifine\\.net/adloadx\\?f=([^&\" ]+)&x=(\\d+)");
    private static final Pattern DOWNLOADX = Pattern.compile("downloadx\\?f=([^&\" ]+)&x=([0-9a-f]+)");

    private static final Map<String, String> BROWSER = Map.of(
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36",
            "Referer", DOWNLOADS_URL);

    /** Returns the local version id of an installed OptiFine build, or null. */
    public String findInstalled(String gameVersion) {
        try (var stream = Files.list(AppPaths.versionsDir())) {
            for (Path dir : (Iterable<Path>) stream::iterator) {
                String name = dir.getFileName().toString();
                if (name.startsWith(gameVersion + "-OptiFine")
                        && Files.isRegularFile(AppPaths.versionJsonFile(name))) {
                    return name;
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    /** Downloads and runs the OptiFine installer for the given Minecraft version. */
    public ManifestService.VersionEntry ensure(String gameVersion) throws IOException {
        String existing = findInstalled(gameVersion);
        if (existing != null) {
            return new ManifestService.VersionEntry(existing, "installed", "");
        }

        // ---- 1. parse the downloads page for the newest build of this MC version ----
        String page = Http.getText(DOWNLOADS_URL, BROWSER);
        Matcher section = SECTION.matcher(page);
        String adloadxUrl = null;
        String ofFile = null;
        while (section.find()) {
            if (!section.group(1).trim().equals(gameVersion)) continue;
            Matcher row = MAIN_ROW.matcher(section.group(2));
            if (row.find()) {
                Matcher m = ADLOADX.matcher(row.group(1).replace("&amp;", "&"));
                if (m.find()) {
                    ofFile = m.group(1);
                    adloadxUrl = "https://optifine.net/adloadx?f=" + m.group(1) + "&x=" + m.group(2);
                }
            }
            break;
        }
        if (adloadxUrl == null) {
            throw new IOException("No OptiFine build available for Minecraft " + gameVersion
                    + " (latest supported: see optifine.net/downloads).");
        }

        // ---- 2. adloadx page -> real download link ----
        String adPage = Http.getText(adloadxUrl, BROWSER);
        Matcher dl = DOWNLOADX.matcher(adPage);
        if (!dl.find()) throw new IOException("OptiFine download link could not be resolved.");
        String jarUrl = "https://optifine.net/" + dl.group(0);

        // ---- 3. download the installer jar ----
        Path cacheJar = AppPaths.cacheDir().resolve("optifine-" + ofFile);
        if (!Files.isRegularFile(cacheJar) || Files.size(cacheJar) == 0) {
            LauncherLog.log("Downloading OptiFine: " + ofFile);
            boolean ok = Http.download(jarUrl, cacheJar, null, null, () -> false);
            if (!ok) throw new IOException("OptiFine download failed (checksum unknown).");
        }

        // ---- 4. run the headless installer against a temp .minecraft ----
        Path baseJar = AppPaths.clientJar(gameVersion);
        Path baseJson = AppPaths.versionJsonFile(gameVersion);
        if (!Files.isRegularFile(baseJar)) {
            throw new IOException("Install Minecraft " + gameVersion + " first, then OptiFine.");
        }

        Path temp = Files.createTempDirectory("prex-optifine");
        try {
            Path tempVersions = temp.resolve(".minecraft/versions/" + gameVersion);
            Files.createDirectories(tempVersions);
            Files.copy(baseJar, tempVersions.resolve(gameVersion + ".jar"), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(baseJson, tempVersions.resolve(gameVersion + ".json"), StandardCopyOption.REPLACE_EXISTING);

            LauncherLog.log("Running OptiFine installer (headless)…");
            ProcessBuilder pb = new ProcessBuilder(
                    JavaFinder.find(),
                    "-Duser.home=" + temp,
                    "-Djava.awt.headless=true",
                    "-cp", cacheJar.toString(),
                    "optifine.Installer",
                    "--installClient");
            pb.directory(temp.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int code = p.waitFor();
            LauncherLog.log("OptiFine installer exited with code " + code);

            // The installer always exits 1 headless (its final "success" dialog
            // cannot render without a display) even when the install completed.
            // Success is therefore judged by the files it produced.
            Path ofVersions = temp.resolve(".minecraft/versions");
            List<Path> ofDirs = new ArrayList<>();
            try (var stream = Files.list(ofVersions)) {
                for (Path d : (Iterable<Path>) stream::iterator) {
                    String n = d.getFileName().toString();
                    if (!n.equals(gameVersion)) ofDirs.add(d);
                }
            }
            if (ofDirs.isEmpty()) {
                LauncherLog.log(output);
                throw new IOException("OptiFine installer failed (exit " + code + "). "
                        + firstLine(output));
            }
            if (code != 0) {
                LauncherLog.log("(exit code " + code + " is expected headless — install completed)");
            }

            // ---- 5. move the results into the launcher's game dir ----
            for (Path d : ofDirs) {
                Path dest = AppPaths.versionsDir().resolve(d.getFileName().toString());
                Files.createDirectories(dest);
                try (var stream = Files.list(d)) {
                    for (Path f : (Iterable<Path>) stream::iterator) {
                        Files.move(f, dest.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            Path ofLibs = temp.resolve(".minecraft/libraries/optifine");
            if (Files.isDirectory(ofLibs)) {
                Path dest = AppPaths.librariesDir().resolve("optifine");
                Files.createDirectories(dest);
                copyTree(ofLibs, dest);
            }

            String id = ofDirs.get(0).getFileName().toString();
            LauncherLog.log("OptiFine installed: " + id);
            return new ManifestService.VersionEntry(id, "installed", "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("OptiFine install interrupted", e);
        } finally {
            deleteRecursively(temp.toFile());
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (var stream = Files.walk(from)) {
            for (Path f : (Iterable<Path>) stream::iterator) {
                Path rel = from.relativize(f);
                Path dest = to.resolve(rel.toString());
                if (Files.isDirectory(f)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(f, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String firstLine(String s) {
        String t = s.trim();
        int i = t.indexOf('\n');
        return i > 0 ? t.substring(0, i) : t;
    }

    private static void deleteRecursively(java.io.File f) {
        java.io.File[] children = f.listFiles();
        if (children != null) for (java.io.File c : children) deleteRecursively(c);
        f.delete();
    }
}
