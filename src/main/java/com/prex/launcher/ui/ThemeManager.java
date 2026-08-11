package com.prex.launcher.ui;

import javafx.scene.Scene;
import javafx.scene.paint.Color;

/** Swaps the active CSS theme ("modern" dark glass / "classic" light TLauncher-style). */
public final class ThemeManager {

    public static final String MODERN = "modern";
    public static final String CLASSIC = "classic";

    private ThemeManager() {}

    public static void apply(Scene scene, String theme) {
        String css = "/css/" + (CLASSIC.equals(theme) ? CLASSIC : MODERN) + ".css";
        java.net.URL url = ThemeManager.class.getResource(css);
        if (url != null) {
            scene.getStylesheets().setAll(url.toExternalForm());
        }
    }

}
