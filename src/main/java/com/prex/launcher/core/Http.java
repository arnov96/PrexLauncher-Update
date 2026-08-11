package com.prex.launcher.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * HTTPS client for every network operation of the launcher.
 *
 * Hardened against flaky CDN edges (the "HTTP 400 for URL" class of errors):
 *  - a real browser User-Agent on EVERY request (some edge nodes reject the
 *    bare Java-http-client agent with HTTP 400)
 *  - automatic retry with backoff for JSON/manifest fetches
 *  - automatic mirror fallback for assets and libraries:
 *      resources.download.minecraft.net  -> bmclapi2.bangbang93.com/assets
 *      libraries.minecraft.net           -> bmclapi2.bangbang93.com/maven
 */
public final class Http {

    public static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 PrexLauncher/1.2.1";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private Http() {}

    /** GET a small document (manifest / version json / asset index), with retries. */
    public static byte[] get(String url) throws IOException {
        return get(url, Map.of());
    }

    public static byte[] get(String url, Map<String, String> headers) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return doGet(url, headers);
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(400L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last;
    }

    private static byte[] doGet(String url, Map<String, String> headers) throws IOException {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", UA)
                    .GET();
            headers.forEach(b::header);
            HttpResponse<byte[]> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + " for " + url);
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }

    /** GET a document as text (UTF-8). */
    public static String getText(String url, Map<String, String> headers) throws IOException {
        return new String(get(url, headers), java.nio.charset.StandardCharsets.UTF_8);
    }

    public static String getText(String url) throws IOException {
        return getText(url, Map.of());
    }

    /** Returns an alternate URL for the same file, or null if none exists. */
    public static String mirrorFor(String url) {
        final String assets = "https://resources.download.minecraft.net/";
        final String libs = "https://libraries.minecraft.net/";
        if (url.startsWith(assets)) {
            return "https://bmclapi2.bangbang93.com/assets/" + url.substring(assets.length());
        }
        if (url.startsWith(libs)) {
            return "https://bmclapi2.bangbang93.com/maven/" + url.substring(libs.length());
        }
        return null;
    }

    /**
     * Streams a file to disk through a temp ".part" file (atomic move at the end),
     * verifying the SHA-1 checksum while streaming. Returns false when the hash
     * does not match. Throws when the transfer fails or is cancelled.
     *
     * On transfer failure it automatically retries via the mirror host when one
     * exists (assets / libraries), then gives up with the original error.
     */
    public static boolean download(String url, Path dest, String expectedSha1,
                                   LongConsumer onBytes, BooleanSupplier cancelled) throws IOException {
        List<String> attempts = new ArrayList<>();
        attempts.add(url);
        String mirror = mirrorFor(url);
        if (mirror != null) attempts.add(mirror);

        IOException lastErr = null;
        for (String attempt : attempts) {
            try {
                return doDownload(attempt, dest, expectedSha1, onBytes, cancelled);
            } catch (IOException e) {
                lastErr = e;
                if (cancelled != null && cancelled.getAsBoolean()) throw e;
                LauncherLog.log("Download failed for " + attempt + ": " + e.getMessage()
                        + (mirror != null && attempt.equals(url) ? " — retrying via mirror…" : ""));
            }
        }
        throw lastErr;
    }

    private static boolean doDownload(String url, Path dest, String expectedSha1,
                                      LongConsumer onBytes, BooleanSupplier cancelled) throws IOException {
        Files.createDirectories(dest.getParent());
        Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
        try {
            InputStream in = openStream(url);
            try (in; OutputStream out = Files.newOutputStream(tmp)) {
                MessageDigest sha = null;
                if (expectedSha1 != null && !expectedSha1.isBlank()) {
                    try {
                        sha = MessageDigest.getInstance("SHA-1");
                    } catch (java.security.NoSuchAlgorithmException e) {
                        throw new IOException("SHA-1 not available", e);
                    }
                }
                byte[] buf = new byte[81920];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        throw new IOException("cancelled");
                    }
                    out.write(buf, 0, n);
                    if (sha != null) sha.update(buf, 0, n);
                    if (onBytes != null) onBytes.accept(n);
                }
                if (sha != null) {
                    String hex = HexFormat.of().formatHex(sha.digest());
                    if (!hex.equalsIgnoreCase(expectedSha1)) {
                        Files.deleteIfExists(tmp);
                        return false; // checksum mismatch
                    }
                }
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }

    public static String sha1Hex(Path file) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-1");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 not available", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[81920];
            int n;
            while ((n = in.read(buf)) > 0) sha.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static InputStream openStream(String url) throws IOException {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(30))
                    .header("User-Agent", UA)
                    .GET().build();
            HttpResponse<InputStream> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                resp.body().close();
                throw new IOException("HTTP " + resp.statusCode() + " for " + url);
            }
            return resp.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching " + url, e);
        }
    }
}
