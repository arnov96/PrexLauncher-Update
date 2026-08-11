package com.prex.launcher.ui;

import com.prex.launcher.core.LauncherConfig;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/** Settings page: theme, memory (MB field + slider), Java runtime, snapshots, storage and about. */
public class SettingsView extends VBox {

    public static final int MEM_MIN_MB = 512;
    public static final int MEM_MAX_MB = 16384;

    private final LauncherConfig config;
    private final Consumer<String> themeCallback;
    private final Consumer<Integer> memoryCallback;
    private RadioButton modernR;
    private RadioButton classicR;

    // memory controls
    private final Slider memSlider = new Slider(MEM_MIN_MB, MEM_MAX_MB, 2048);
    private final TextField memField = new TextField();
    private boolean syncingMemory = false;

    public SettingsView(LauncherConfig config, Consumer<String> themeCallback, Consumer<Integer> memoryCallback) {
        this.config = config;
        this.themeCallback = themeCallback;
        this.memoryCallback = memoryCallback;
        getStyleClass().add("page");
        setSpacing(14);

        Label title = new Label("Settings");
        title.getStyleClass().add("label-h1");
        getChildren().add(title);

        // ---------------- Appearance ----------------
        VBox appearance = card("Appearance");
        modernR = new RadioButton("Modern — dark glass look");
        classicR = new RadioButton("Classic — light TLauncher-style");
        ToggleGroup themeGroup = new ToggleGroup();
        modernR.setToggleGroup(themeGroup);
        classicR.setToggleGroup(themeGroup);
        modernR.setSelected(ThemeManager.MODERN.equals(config.theme));
        classicR.setSelected(ThemeManager.CLASSIC.equals(config.theme));
        modernR.setOnAction(e -> themeCallback.accept(ThemeManager.MODERN));
        classicR.setOnAction(e -> themeCallback.accept(ThemeManager.CLASSIC));
        appearance.getChildren().addAll(modernR, classicR);

        // ---------------- Memory ----------------
        VBox memory = card("Memory");
        Label memHint = new Label("RAM allocated to Minecraft — type MB directly or drag the slider (512 – 16384 MB).");
        memHint.getStyleClass().add("label-dim");
        memHint.setWrapText(true);

        memSlider.setBlockIncrement(256);
        memSlider.setMajorTickUnit(4096);
        memSlider.setMinorTickCount(3);
        memSlider.setSnapToTicks(false);
        memSlider.setShowTickLabels(true);
        HBox.setHgrow(memSlider, Priority.ALWAYS);

        Label mbSuffix = new Label("MB");
        mbSuffix.getStyleClass().add("label-dim");
        memField.getStyleClass().add("field");
        memField.setPrefColumnCount(6);
        memField.setPromptText("2048");
        HBox mbRow = new HBox(8, new Label("Exact MB"), memField, mbSuffix);
        mbRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        // keep field and slider in sync (both directions)
        memSlider.valueProperty().addListener((o, a, b) -> {
            if (syncingMemory) return;
            int mb = (int) Math.round(b.doubleValue() / 256.0) * 256;
            syncingMemory = true;
            memField.setText(String.valueOf(mb));
            syncingMemory = false;
            config.memoryMb = mb;
            memoryCallback.accept(mb);
        });
        memField.textProperty().addListener((o, a, b) -> {
            if (syncingMemory) return;
            try {
                int mb = Integer.parseInt(b.trim());
                if (mb < MEM_MIN_MB) mb = MEM_MIN_MB;
                if (mb > MEM_MAX_MB) mb = MEM_MAX_MB;
                memField.getStyleClass().remove("field-invalid");
                syncingMemory = true;
                memSlider.setValue(mb);
                syncingMemory = false;
                config.memoryMb = mb;
                memoryCallback.accept(mb);
            } catch (NumberFormatException ex) {
                if (!b.isBlank()) memField.getStyleClass().add("field-invalid");
            }
        });

        // quick presets
        Label presetsLbl = new Label("Quick presets:");
        presetsLbl.getStyleClass().add("label-dim");
        HBox presets = new HBox(8);
        for (int gb : new int[]{1, 2, 4, 8, 16}) {
            Button chip = new Button(gb + " GB");
            chip.getStyleClass().add("btn-ghost");
            chip.setOnAction(e -> {
                int mb = gb * 1024;
                memField.setText(String.valueOf(mb));
                memSlider.setValue(mb);
                config.memoryMb = mb;
                memoryCallback.accept(mb);
            });
            UiAnim.buttonHover(chip, 1.05);
            presets.getChildren().add(chip);
        }
        Label memNote = new Label("More RAM than needed can slow the launcher. 2–4 GB is fine for most versions.");
        memNote.getStyleClass().add("label-dim");
        memNote.setWrapText(true);

        memory.getChildren().addAll(memHint, memSlider, mbRow, presetsLbl, presets, memNote);

        // ---------------- Performance ----------------
        VBox perf = card("Performance");

        CheckBox perfFlags = new CheckBox("Game performance mode (recommended)");
        perfFlags.setSelected(config.performanceMode);
        perfFlags.selectedProperty().addListener((o, a, b) -> {
            config.performanceMode = b;
            config.save();
        });
        Label perfHint = new Label("Adds GC tuning flags to Minecraft (Aikar-style) that cut stutter and "
                + "smooth out FPS. Applied automatically on Java 17+.");
        perfHint.getStyleClass().add("label-dim");
        perfHint.setWrapText(true);

        CheckBox lowFx = new CheckBox("Low-FX mode (for weak PCs)");
        lowFx.setSelected(config.lowFxMode);
        lowFx.selectedProperty().addListener((o, a, b) -> {
            config.lowFxMode = b;
            config.save();
            com.prex.launcher.ui.UiAnim.setEnabled(!b);
        });
        Label lowFxHint = new Label("Turns off UI animations (button effects, page transitions, ripple) "
                + "so the launcher itself uses almost no CPU/GPU.");
        lowFxHint.getStyleClass().add("label-dim");
        lowFxHint.setWrapText(true);

        perf.getChildren().addAll(perfFlags, perfHint, lowFx, lowFxHint);

        // ---------------- Game ----------------
        VBox game = card("Game");
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);

        Label javaLbl = new Label("Java path");
        javaLbl.getStyleClass().add("label-dim");
        TextField javaField = new TextField(config.javaPath);
        javaField.getStyleClass().add("field");
        javaField.setPromptText("(empty = use the launcher's Java)");
        javaField.textProperty().addListener((o, a, b) -> { config.javaPath = b.trim(); config.save(); });
        GridPane.setHgrow(javaField, Priority.ALWAYS);
        Button browseJava = new Button("Browse…");
        browseJava.getStyleClass().add("btn-ghost");
        browseJava.setOnAction(e -> {
            javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
            fc.setTitle("Select java executable");
            java.io.File f = fc.showOpenDialog(getScene().getWindow());
            if (f != null) javaField.setText(f.getAbsolutePath());
        });
        Button detectJava = new Button("Detect");
        detectJava.getStyleClass().add("btn-ghost");
        Label javaHint = new Label();
        javaHint.getStyleClass().add("label-dim");
        detectJava.setOnAction(e -> {
            String found = com.prex.launcher.core.JavaFinder.find();
            javaHint.setText("Detected: " + found + "  (" + com.prex.launcher.core.JavaFinder.versionOf(found) + ")");
        });
        HBox javaRow = new HBox(8, javaLbl, javaField, browseJava, detectJava);
        HBox.setHgrow(javaField, Priority.ALWAYS);

        CheckBox snapshots = new CheckBox("Show snapshot versions in the version list");
        snapshots.setSelected(config.showSnapshots);
        snapshots.selectedProperty().addListener((o, a, b) -> { config.showSnapshots = b; config.save(); });

        grid.add(javaRow, 0, 0);
        GridPane.setColumnSpan(javaRow, 2);
        grid.add(javaHint, 0, 1);
        GridPane.setColumnSpan(javaHint, 2);
        grid.add(snapshots, 0, 2);
        GridPane.setColumnSpan(snapshots, 2);
        game.getChildren().add(grid);

        // ---------------- Storage ----------------
        VBox storage = card("Storage");
        Label gameDirLbl = new Label("Game folder (acts as .minecraft):");
        gameDirLbl.getStyleClass().add("label-dim");
        Label gameDirVal = new Label(com.prex.launcher.core.AppPaths.gameDir().toString());
        gameDirVal.getStyleClass().add("label-mono");
        Button openGameDir = new Button("Open");
        openGameDir.getStyleClass().add("btn-ghost");
        openGameDir.setOnAction(e -> com.prex.launcher.core.DesktopUtil.open(com.prex.launcher.core.AppPaths.gameDir()));
        HBox dirRow = new HBox(8, gameDirVal, openGameDir);
        HBox.setHgrow(gameDirVal, Priority.ALWAYS);
        storage.getChildren().addAll(gameDirLbl, dirRow);

        // ---------------- About ----------------
        VBox about = card("About");
        Label aboutText = new Label("Prex Launcher 1.7.1 — Java 17+ / JavaFX 21.\n"
                + "Offline accounts: no Microsoft login, no passwords stored.\n"
                + "Vanilla, Fabric and OptiFine support. Built-in Mods page: search Modrinth,\n"
                + "download and toggle mods with one click. All downloads are HTTPS with SHA-1 verification.\n"
                + "Requires a purchased Minecraft copy.");
        aboutText.setWrapText(true);

        // dependency checker button
        Button checkDeps = new Button("✓ Check dependencies");
        checkDeps.getStyleClass().add("btn-ghost");
        checkDeps.setMaxWidth(Double.MAX_VALUE);
        UiAnim.buttonHover(checkDeps, 1.02);
        checkDeps.setOnAction(e -> {
            var items = com.prex.launcher.DependencyCheck.run();
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.INFORMATION);
            alert.setTitle("Prex Launcher — dependency check");
            alert.setHeaderText("Dependency check results");
            javafx.scene.control.TextArea ta = new javafx.scene.control.TextArea(
                    com.prex.launcher.DependencyCheck.report(items));
            ta.setEditable(false);
            ta.setPrefSize(560, 300);
            alert.getDialogPane().setContent(ta);
            alert.showAndWait();
        });
        about.getChildren().addAll(checkDeps);
        Button reset = new Button("Reset all launcher data (deletes the game folder)");
        reset.getStyleClass().add("btn-danger");
        reset.setOnAction(e -> confirmReset());
        about.getChildren().addAll(aboutText, reset);

        getChildren().addAll(appearance, memory, game, storage, about);

        // init controls from config
        syncingMemory = true;
        memSlider.setValue(config.memoryMb);
        memField.setText(String.valueOf(config.memoryMb));
        syncingMemory = false;
    }

    /** Called when memory changes elsewhere (Play page slider) — keeps this page in sync. */
    public void setMemoryMb(int mb) {
        mb = Math.max(MEM_MIN_MB, Math.min(MEM_MAX_MB, mb));
        syncingMemory = true;
        memSlider.setValue(mb);
        memField.setText(String.valueOf(mb));
        syncingMemory = false;
    }

    /** Called when the theme is changed from the sidebar — keeps the radios in sync. */
    public void setTheme(String theme) {
        if (modernR == null || classicR == null) return;
        modernR.setSelected(ThemeManager.MODERN.equals(theme));
        classicR.setSelected(ThemeManager.CLASSIC.equals(theme));
    }

    private VBox card(String titleText) {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        Label title = new Label(titleText);
        title.getStyleClass().add("label-h2");
        card.getChildren().add(title);
        return card;
    }

    private void confirmReset() {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.CONFIRMATION);
        alert.setTitle("Reset launcher data");
        alert.setHeaderText("Delete everything?");
        alert.setContentText("This permanently deletes " + com.prex.launcher.core.AppPaths.base()
                + "\nincluding all downloaded versions, libraries, assets, saves and settings.");
        alert.showAndWait().ifPresent(r -> {
            if (r == javafx.scene.control.ButtonType.OK) {
                try {
                    deleteRecursively(com.prex.launcher.core.AppPaths.base().toFile());
                } catch (Exception ignored) {}
                javafx.application.Platform.exit();
            }
        });
    }

    private static void deleteRecursively(java.io.File f) {
        if (f == null || !f.exists()) return;
        java.io.File[] children = f.listFiles();
        if (children != null) for (java.io.File c : children) deleteRecursively(c);
        f.delete();
    }
}
