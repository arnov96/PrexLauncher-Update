package com.prex.launcher.core;

import java.nio.file.Path;
import java.util.List;

/**
 * Dev tool: runs the real full installer for a version.
 *
 * Run: mvn -q exec:java -Dexec.mainClass=com.prex.launcher.core.FullInstallTest -Dexec.args="1.8.9"
 *      mvn -q exec:java -Dexec.mainClass=com.prex.launcher.core.FullInstallTest -Dexec.args="fabric:1.21.1"
 *      mvn -q exec:java -Dexec.mainClass=com.prex.launcher.core.FullInstallTest -Dexec.args="optifine:1.21.1"
 */
public class FullInstallTest {

    public static void main(String[] args) throws Exception {
        String arg = args.length > 0 ? args[0] : "1.8.9";
        System.out.println("== Full install test: " + arg + " ==");
        ManifestService svc = new ManifestService();
        FabricService fabric = new FabricService();
        OptiFineService optifine = new OptiFineService();

        ManifestService.VersionEntry entry;
        if (arg.startsWith("fabric:")) {
            entry = fabric.ensure(arg.substring(7));
        } else if (arg.startsWith("optifine:")) {
            String base = arg.substring(9);
            entry = svc.listPlayable(false).stream().filter(e -> e.id().equals(base))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Version not found: " + base));
            new GameInstaller().install(svc.loadResolved(base), entry, printListener());
            entry = optifine.ensure(base);
        } else {
            entry = svc.listPlayable(true).stream().filter(e -> e.id().equals(arg))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Version not found: " + arg));
        }

        VersionJson v = svc.loadResolved(entry.id());
        System.out.println("resolved: id=" + v.id + " mainClass=" + v.mainClass
                + " libs=" + (v.libraries == null ? 0 : v.libraries.size()));

        new GameInstaller().install(v, entry, printListener());
        System.out.println("== INSTALL OK ==");

        // launch command sanity
        LauncherConfig cfg = LauncherConfig.load();
        GameLauncher.LaunchCommand cmd = new GameLauncher().buildCommand(v, entry, "Steve",
                OfflineAccount.uuid("Steve"), cfg);
        System.out.println("launch cmd: " + cmd.command().get(0) + " " + cmd.command().get(cmd.command().size() - 1)
                + " (" + cmd.command().size() + " args)");
        if (cmd.command().stream().anyMatch(s -> s.contains("${"))) {
            throw new IllegalStateException("Unresolved tokens remain!");
        }
        System.out.println("== LAUNCH COMMAND OK ==");
    }

    private static GameInstaller.Listener printListener() {
        return new GameInstaller.Listener() {
            @Override public void onStatus(String s) { System.out.println("[status] " + s); }
            @Override public void onProgress(long d, long t, String f) {
                System.out.printf("[progress] %d / %d  %s%n", d, t, f == null ? "" : f);
            }
        };
    }
}
