package com.prex.launcher;

import com.prex.launcher.core.LauncherLog;

/**
 * Entry point for the no-Maven / no-compile package.
 *
 * A plain main class (NOT an Application subclass) so JavaFX can run from the
 * classpath via `java -jar prex-launcher.jar`. Preflights Java, captures any
 * startup failure (including FX-thread crashes) into logs/crash.log and shows
 * a clear dialog — no more silent "A fatal exception has occurred".
 */
public final class Main {

    private Main() {}

    public static void main(String[] args) {
        // 0. dependency check — clear report instead of cryptic crashes
        var deps = DependencyCheck.run();
        System.out.println("\n" + DependencyCheck.report(deps) + "\n");
        if (!DependencyCheck.allEssentialOk(deps)) {
            showError("Prex Launcher found a problem with your Java installation:\n\n"
                    + DependencyCheck.report(deps)
                    + "\n\nInstall the free JDK 17+ from https://adoptium.net");
            System.exit(1);
        }

        // 1. preflight: Java must be 17+
        String v = System.getProperty("java.version", "?");
        if (!isJava17Plus(v)) {
            showError("Prex Launcher needs Java 17 or newer.\n"
                    + "You are running: " + v + "\n\n"
                    + "Install the free JDK 17+ from https://adoptium.net");
            System.exit(1);
        }
        System.out.println("[prex] java " + v + " | os " + System.getProperty("os.name")
                + " " + System.getProperty("os.arch"));

        // 1. global uncaught handler — catches FX-thread crashes too
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            LauncherLog.crash(ex);
            showError("Prex Launcher hit a fatal error.\n\n" + ex
                    + "\n\nDetails saved to logs/crash.log — send that file for help.");
        });

        // 2. launch
        try {
            LauncherApp.main(args);
        } catch (Throwable t) {
            LauncherLog.crash(t);
            showError("Prex Launcher failed to start.\n\n" + t
                    + "\n\nDetails saved to logs/crash.log — send that file for help.");
            System.exit(1);
        }
    }

    private static boolean isJava17Plus(String v) {
        try {
            String[] parts = v.split("\\.");
            int major = Integer.parseInt(parts[0]);
            if (major == 1 && parts.length > 1) major = Integer.parseInt(parts[1]);
            return major >= 17;
        } catch (Exception e) {
            return false;
        }
    }

    private static void showError(String msg) {
        try {
            javax.swing.JOptionPane.showMessageDialog(null, msg,
                    "Prex Launcher — error", javax.swing.JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            System.err.println(msg);
        }
    }
}
