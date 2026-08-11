package com.prex.launcher.core;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Offline (cracked-style) accounts.
 *
 * The UUID is derived deterministically from the player name using the same
 * "OfflinePlayer:<name>" scheme that offline-mode servers (CraftBukkit/Paper,
 * Spigot, TLauncher-style setups) compute, so your UUID is stable and the
 * server sees you as the same player across launches.
 *
 * No credentials are ever stored — this launcher never touches passwords.
 */
public final class OfflineAccount {

    public static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private OfflineAccount() {}

    /** Returns null when the name is usable, otherwise a human-readable reason. */
    public static String error(String name) {
        if (name == null || name.isBlank()) return "Enter a player name first.";
        if (name.length() > 16) return "Max 16 characters.";
        if (!NAME_PATTERN.matcher(name).matches())
            return "Only letters, digits and underscores are allowed.";
        if (Character.isDigit(name.charAt(0)))
            return "Names may not start with a digit (servers will reject it).";
        return null;
    }

    /** Deterministic offline UUID (no dashes), like the one servers compute. */
    public static String uuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }
}
