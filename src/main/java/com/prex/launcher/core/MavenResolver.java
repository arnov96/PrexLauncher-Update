package com.prex.launcher.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Resolves Maven coordinates to download URLs for libraries that ship without
 * a direct URL in their manifest (Fabric profile libraries, OptiFine libraries).
 *
 * Probe order: Fabric's own maven, then Maven Central. The standard maven
 * layout is group/artifact/version/artifact-version.jar.
 */
public final class MavenResolver {

    private static final String[] REPOS = {
            "https://maven.fabricmc.net/",
            "https://repo1.maven.org/maven2/"
    };

    private MavenResolver() {}

    /** group:artifact:version -> group/artifact/version/artifact-version.jar */
    public static String pathFromName(String mavenName) {
        String[] parts = mavenName.split(":");
        if (parts.length < 3) throw new IllegalArgumentException("Bad maven name: " + mavenName);
        String group = parts[0];
        String artifact = parts[1];
        String version = parts[2];
        return group.replace('.', '/') + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + ".jar";
    }

    /** Probes the known repositories and returns the first URL that serves the file, or null. */
    public static String resolveOrNull(String relativePath) {
        for (String repo : REPOS) {
            String url = repo + relativePath;
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .header("User-Agent", Http.UA)
                        .build();
                HttpResponse<Void> resp = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build()
                        .send(req, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() == 200) return url;
            } catch (IOException | InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
