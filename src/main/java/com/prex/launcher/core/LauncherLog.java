package com.prex.launcher.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** File + console logging for launcher events and the game's stdout. */
public final class LauncherLog {

    private LauncherLog() {}

    /** Timestamped launcher-side message. */
    public static void log(String message) {
        String line = "[" + ts() + "] " + message;
        write(AppPaths.launcherLog(), line);
        ConsoleBus.post(line);
    }

    /** Raw line from the game's stdout. */
    public static void gameLine(String line) {
        write(AppPaths.gameLog(), line);
        ConsoleBus.post(line);
    }

    private static void write(java.nio.file.Path file, String line) {
        try {
            Files.createDirectories(AppPaths.logsDir());
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
    }

    /** Writes a full crash report (stack trace) to logs/crash.log. */
    public static void crash(Throwable t) {
        try {
            Files.createDirectories(AppPaths.logsDir());
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            String report = "[" + ts() + "] FATAL: " + t + System.lineSeparator() + sw
                    + System.lineSeparator() + "java: " + System.getProperty("java.version")
                    + " | os: " + System.getProperty("os.name") + " " + System.getProperty("os.arch");
            Files.writeString(AppPaths.logsDir().resolve("crash.log"), report,
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {}
        log("FATAL: " + t);
    }

    private static String ts() {
        return Instant.now().toString().substring(0, 23).replace('T', ' ');
    }
}
