package com.prex.launcher.core;

import java.awt.Desktop;
import java.nio.file.Path;

/** Opens folders in the OS file manager. */
public final class DesktopUtil {

    private DesktopUtil() {}

    public static void open(Path dir) {
        if (dir == null) return;
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir.toFile());
                return;
            }
        } catch (Exception ignored) {}
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                new ProcessBuilder("explorer", dir.toString()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", dir.toString()).start();
            } else {
                new ProcessBuilder("xdg-open", dir.toString()).start();
            }
        } catch (Exception ignored) {}
    }
}
