package com.prex.launcher.ui;

import com.prex.launcher.core.LauncherConfig;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Application shell: dark/light sidebar with navigation (Play / Settings / Console)
 * and a quick Modern ⇄ Classic theme switch at the bottom.
 */
public class MainView extends BorderPane {

    private final LauncherConfig config;
    private final Map<String, Node> pages = new HashMap<>();
    private final Map<String, ToggleButton> navButtons = new HashMap<>();
    private Consumer<String> onThemeChange = theme -> {}; // replaced by LauncherApp

    private final PlayView playView;
    private final SettingsView settingsView;
    private final ConsoleView consoleView;
    private final ModsView modsView;
    private double lastNavY = -1;
    private ToggleButton modernToggle;
    private ToggleButton classicToggle;

    public MainView(LauncherConfig config) {
        this.config = config;
        getStyleClass().add("root");

        playView = new PlayView(config, this);
        settingsView = new SettingsView(config, this::changeTheme, this::onMemoryChanged);
        consoleView = new ConsoleView();
        modsView = new ModsView(config);
        pages.put("play", playView);
        pages.put("settings", settingsView);
        pages.put("console", consoleView);
        pages.put("mods", modsView);
        playView.setOnMemoryChange(this::onMemoryChanged);

        setTop(buildTopBar());
        setLeft(buildSidebar());
        setCenter(buildContent());
        show("play");
    }

    /** Slim top bar: brand left, animated status dot + version pill right. */
    private HBox buildTopBar() {
        HBox bar = new HBox(12);
        bar.getStyleClass().add("topbar");
        bar.setAlignment(Pos.CENTER_LEFT);

        ImageView logo = new ImageView();
        try {
            logo.setImage(new Image(MainView.class.getResource("/images/icon.png").toExternalForm()));
        } catch (Exception ignored) {}
        logo.setFitWidth(24);
        logo.setFitHeight(24);

        Label title = new Label("Prex Launcher");
        title.getStyleClass().add("topbar-title");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        // pulsing status dot — cheap opacity pulse, low-FX aware
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(4);
        dot.getStyleClass().add("status-dot");
        UiAnim.pulseOpacity(dot, 0.35, 1.0, Duration.seconds(1.4));

        Label pill = new Label("v1.7.1");
        pill.getStyleClass().add("topbar-pill");

        bar.getChildren().addAll(logo, title, spacer, dot, pill);
        return bar;
    }

    /** Memory is edited in Settings (MB field + slider) and Play (GB slider) — keep both in sync. */
    private void onMemoryChanged(int mb) {
        config.memoryMb = Math.max(512, Math.min(16384, mb));
        config.save();
        playView.setMemoryMb(config.memoryMb);
        settingsView.setMemoryMb(config.memoryMb);
    }

    /** Replaces the default no-op theme callback with the real scene applier. */
    public void setOnThemeChange(Consumer<String> callback) {
        onThemeChange = callback;
    }

    public void changeTheme(String theme) {
        config.theme = theme;
        config.save();
        onThemeChange.accept(theme);
        syncThemeControls(theme);
    }

    private void syncThemeControls(String theme) {
        boolean modern = ThemeManager.MODERN.equals(theme);
        if (modernToggle != null) modernToggle.setSelected(modern);
        if (classicToggle != null) classicToggle.setSelected(!modern);
        settingsView.setTheme(theme);
    }

    public void navigate(String page) { show(page); }

    public void shutdown() {
        playView.shutdown();
        modsView.shutdown();
        config.save();
    }

    // ---------------------------------------------------------------- sidebar

    private VBox buildSidebar() {
        VBox sidebar = new VBox(6);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(248);
        sidebar.setMinWidth(248);

        // Brand
        HBox brand = new HBox(10);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setPadding(new Insets(6, 8, 16, 8));
        ImageView logo = new ImageView();
        try {
            logo.setImage(new Image(MainView.class.getResource("/images/icon.png").toExternalForm()));
        } catch (Exception ignored) {}
        logo.setFitWidth(42);
        logo.setFitHeight(42);
        VBox brandText = new VBox(0);
        Label name = new Label("Prex");
        name.getStyleClass().add("brand-title");
        Label tag = new Label("Fast • Secure • Modded");
        tag.getStyleClass().add("brand-sub");
        brandText.getChildren().addAll(name, tag);
        brand.getChildren().addAll(logo, brandText);

        ToggleGroup group = new ToggleGroup();
        addNav("play", "▶  Play", group);
        addNav("mods", "▦  Mods", group);
        addNav("settings", "⚙  Settings", group);
        addNav("console", "▤  Console", group);

        sidebar.getChildren().addAll(brand, navButtons.get("play"), navButtons.get("mods"),
                navButtons.get("settings"), navButtons.get("console"));

        // Spacer
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        sidebar.getChildren().add(spacer);

        // Theme quick switch
        ToggleGroup themeGroup = new ToggleGroup();
        ToggleButton modern = themeToggle("Modern", ThemeManager.MODERN, themeGroup);
        ToggleButton classic = themeToggle("Classic", ThemeManager.CLASSIC, themeGroup);
        modernToggle = modern;
        classicToggle = classic;
        HBox seg = new HBox(modern, classic);
        seg.getStyleClass().add("theme-seg");
        sidebar.getChildren().add(seg);
        return sidebar;
    }

    private ToggleButton themeToggle(String label, String theme, ToggleGroup group) {
        ToggleButton b = new ToggleButton(label);
        b.getStyleClass().add("seg-btn");
        b.setToggleGroup(group);
        b.setUserData(theme);
        b.setSelected(theme.equals(config.theme));
        // guard: clicking the already-active theme must NOT deselect it (ToggleButton quirk)
        b.setOnAction(e -> {
            if (!b.isSelected()) b.setSelected(true);
            else changeTheme(theme);
        });
        return b;
    }

    private void addNav(String page, String text, ToggleGroup group) {
        ToggleButton b = new ToggleButton(text);
        b.getStyleClass().add("nav-btn");
        b.setToggleGroup(group);
        b.setUserData(page);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setOnAction(e -> show(page));

        // animated left indicator: slides in when selected
        javafx.scene.shape.Rectangle ind = new javafx.scene.shape.Rectangle(3, 18);
        ind.getStyleClass().add("nav-indicator");
        ind.setVisible(false);
        ind.setX(2);
        b.setGraphic(ind);
        b.selectedProperty().addListener((o, a, sel) -> {
            if (sel) {
                ind.setVisible(true);
                if (!UiAnim.enabled) { ind.setOpacity(1); ind.setScaleY(1); return; }
                ind.setOpacity(0);
                ind.setScaleY(0.4);
                Timeline tl = new Timeline(
                        new KeyFrame(Duration.millis(220),
                                new KeyValue(ind.opacityProperty(), 1.0, Interpolator.EASE_BOTH),
                                new KeyValue(ind.scaleYProperty(), 1.0, Interpolator.EASE_BOTH)));
                tl.play();
            } else {
                if (!UiAnim.enabled) { ind.setVisible(false); return; }
                FadeTransition f = new FadeTransition(Duration.millis(120), ind);
                f.setToValue(0);
                f.setOnFinished(e -> ind.setVisible(false));
                f.play();
            }
        });
        navButtons.put(page, b);
    }

    // ---------------------------------------------------------------- content

    private StackPane buildContent() {
        StackPane content = new StackPane();
        content.getStyleClass().add("content");
        UiAnim.staticGlow(content);   // soft ambient glow behind the pages
        UiAnim.auroraBackground(content);  // slow drifting aurora orbs

        // scrollable page area — fits any window size, nothing gets cut off
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane();
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("page-scroll");
        StackPane inner = new StackPane();
        inner.getStyleClass().add("page-stack");
        inner.getChildren().addAll(pages.values());
        scroll.setContent(inner);
        content.getChildren().add(scroll);

        // footer — "BY PREX" bottom right
        Label byPrex = new Label("BY PREX");
        byPrex.getStyleClass().add("footer-prex");
        byPrex.setMouseTransparent(true);
        StackPane.setAlignment(byPrex, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(byPrex, new Insets(0, 10, 6, 0));
        content.getChildren().add(byPrex);
        return content;
    }

    private void show(String page) {
        for (Map.Entry<String, Node> e : pages.entrySet()) {
            boolean active = e.getKey().equals(page);
            e.getValue().setVisible(active);
            e.getValue().setManaged(active);
            if (active && !"play".equals(page)) {
                UiAnim.enterPage(e.getValue());
            }
        }
        ToggleButton btn = navButtons.get(page);
        if (btn != null) btn.setSelected(true);
    }

    private static class Region extends javafx.scene.layout.Region {}
}
