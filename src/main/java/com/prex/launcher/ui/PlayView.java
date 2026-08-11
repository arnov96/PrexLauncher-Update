package com.prex.launcher.ui;

import com.prex.launcher.core.AppPaths;
import com.prex.launcher.core.DesktopUtil;
import com.prex.launcher.core.FabricService;
import com.prex.launcher.core.GameInstaller;
import com.prex.launcher.core.GameLauncher;
import com.prex.launcher.core.LauncherConfig;
import com.prex.launcher.core.LauncherLog;
import com.prex.launcher.core.ManifestService;
import com.prex.launcher.core.OfflineAccount;
import com.prex.launcher.core.OptiFineService;
import com.prex.launcher.core.VersionJson;
import com.prex.launcher.core.VersionManifest;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Main "Play" page: animated hero, account/version/memory/modloader pickers, play/stop. */
public class PlayView extends VBox {

    /** Modloader selection for new installs. */
    public record ModLoader(String id, String label) {
        @Override
        public String toString() { return label; }
    }

    private static final ModLoader[] MODLOADERS = {
            new ModLoader("vanilla", "Vanilla"),
            new ModLoader("fabric", "Fabric"),
            new ModLoader("optifine", "OptiFine")
    };

    private final LauncherConfig config;
    private final MainView parent;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "prex-worker");
        t.setDaemon(true);
        return t;
    });
    private final ManifestService manifestService = new ManifestService();
    private final FabricService fabricService = new FabricService();
    private final OptiFineService optifineService = new OptiFineService();

    // controls
    private final TextField nameField = new TextField();
    private final Label nameError = new Label();
    private final TextField versionSearch = new TextField();
    private final ComboBox<ManifestService.VersionEntry> versionBox = new ComboBox<>();
    private final Map<String, ToggleButton> modChips = new HashMap<>();
    private final CheckBox snapshotCk = new CheckBox("Show snapshots");
    private final Slider memSlider = new Slider(1, 16, 2);
    private final Label memLabel = new Label();
    private final Label statusLabel = new Label();
    private final Button playBtn = new Button("PLAY");

    // download progress
    private final VBox progressCard = new VBox(6);
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label fileLabel = new Label();
    private final Label bytesLabel = new Label();
    private final Button cancelBtn = new Button("Cancel download");

    // running state
    private final VBox runningCard = new VBox(10);
    private final Button stopBtn = new Button("Stop game");

    private volatile GameInstaller installer;
    private volatile Process gameProcess;
    private long lastDone = -1;
    private long lastTime = 0;
    private Consumer<Integer> onMemoryChange = mb -> {};
    private boolean updatingMemory = false;
    private List<ManifestService.VersionEntry> allVersions = new ArrayList<>();
    private Label modsHintLabel;

    /** Called when memory changes elsewhere (Settings page) — keeps this slider in sync. */
    public void setMemoryMb(int mb) {
        updatingMemory = true;
        memSlider.setValue(mb / 1024.0);
        memLabel.setText(String.format("%.1f GB", mb / 1024.0));
        updatingMemory = false;
    }

    public void setOnMemoryChange(Consumer<Integer> callback) {
        this.onMemoryChange = callback;
    }

    public PlayView(LauncherConfig config, MainView parent) {
        this.config = config;
        this.parent = parent;
        getStyleClass().add("page");
        setSpacing(10);

        // ------------------------------------------------ two-column layout (matches reference)
        // left card: version + account
        VBox leftCard = new VBox(10);
        leftCard.getStyleClass().addAll("card", "card-left");
        HBox.setHgrow(leftCard, Priority.ALWAYS);

        Label cardTitle = new Label("Play");
        cardTitle.getStyleClass().add("label-h1");
        Label cardSub = new Label("Pick a version, enter your name.");
        cardSub.getStyleClass().add("label-dim");

        // version label + selector
        Label verLbl = new Label("MINECRAFT VERSION");
        verLbl.getStyleClass().add("label-field");
        versionSearch.setPromptText("Search version\u2026 e.g. 1.16.5");
        versionSearch.getStyleClass().add("field");
        versionSearch.textProperty().addListener((o, a, b) -> applyVersionFilter(b));
        versionBox.getStyleClass().add("field");
        versionBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(versionBox, Priority.ALWAYS);
        Button refreshBtn = new Button("\u21bb");
        refreshBtn.getStyleClass().add("btn-ghost");
        refreshBtn.setOnAction(e -> refreshVersions(true));
        UiAnim.buttonHover(refreshBtn, 1.05);
        HBox versionRow = new HBox(8, versionBox, refreshBtn);
        HBox.setHgrow(versionBox, Priority.ALWAYS);

        // name label + row
        Label nameLbl = new Label("PLAYER NAME");
        nameLbl.getStyleClass().add("label-field");
        nameField.setPromptText("Offline account name");
        nameField.getStyleClass().add("field");
        nameField.setText(config.username);
        nameField.textProperty().addListener((o, a, b) -> { config.username = b.trim(); config.save(); });
        nameError.getStyleClass().add("label-err");
        nameError.setVisible(false);
        HBox nameRow = new HBox(8, nameField, nameError);
        HBox.setHgrow(nameField, Priority.ALWAYS);
        nameError.setAlignment(Pos.CENTER_LEFT);

        // modloader chips
        Label modloaderLbl = new Label("MODLOADER");
        modloaderLbl.getStyleClass().add("label-field");
        HBox modloaderRow = new HBox(8);
        modloaderRow.setAlignment(Pos.CENTER_LEFT);
        ToggleGroup modGroup = new ToggleGroup();
        for (ModLoader m : MODLOADERS) {
            ToggleButton chip = new ToggleButton(m.label());
            chip.getStyleClass().add("chip-btn");
            chip.setToggleGroup(modGroup);
            chip.setUserData(m.id());
            chip.setSelected(m.id().equals(config.modloader));
            UiAnim.buttonHover(chip, 1.05);
            chip.setOnAction(e -> {
                config.modloader = (String) chip.getUserData();
                config.save();
                updatePlayLabel();
                statusLabel.setText("Modloader: " + chip.getText());
                updateModsHint();
            });
            modChips.put(m.id(), chip);
            modloaderRow.getChildren().add(chip);
        }

        // light selector strip (like the reference image)
        VBox selectorStrip = new VBox(8);
        selectorStrip.getStyleClass().add("selector-strip");
        selectorStrip.getChildren().addAll(verLbl, versionRow, versionSearch);

        leftCard.getChildren().addAll(cardTitle, cardSub,
                selectorStrip,
                nameLbl, nameRow,
                modloaderLbl, modloaderRow);

        // right card: memory + options
        VBox rightCard = new VBox(10);
        rightCard.getStyleClass().addAll("card", "card-right");
        HBox.setHgrow(rightCard, Priority.ALWAYS);

        Label rightTitle = new Label("Options");
        rightTitle.getStyleClass().add("label-h1");

        // memory row
        Label memTitle = new Label("Memory");
        memTitle.getStyleClass().add("label-dim");
        memSlider.setBlockIncrement(0.5);
        memSlider.setValue(config.memoryMb / 1024.0);
        HBox.setHgrow(memSlider, Priority.ALWAYS);
        memSlider.valueProperty().addListener((o, a, b) -> {
            if (updatingMemory) return;
            int mb = (int) Math.round(b.doubleValue() * 1024);
            config.memoryMb = mb;
            memLabel.setText(String.format("%.1f GB", b.doubleValue()));
            onMemoryChange.accept(mb);
        });
        memLabel.getStyleClass().add("label-mono");
        memLabel.setMinWidth(60);
        VBox memV = new VBox(6, memTitle, new HBox(10, memSlider, memLabel));
        HBox.setHgrow(memSlider, Priority.ALWAYS);

        snapshotCk.setSelected(config.showSnapshots);
        snapshotCk.selectedProperty().addListener((o, a, b) -> {
            config.showSnapshots = b;
            config.save();
            refreshVersions(false);
        });

        Label javaInfo = new Label("Java: " + com.prex.launcher.core.JavaFinder.versionOf(
                com.prex.launcher.core.JavaFinder.find()));
        javaInfo.getStyleClass().add("label-dim");
        javaInfo.setWrapText(true);

        Hyperlink openDir = new Hyperlink("Open game folder");
        openDir.setOnAction(e -> DesktopUtil.open(AppPaths.gameDir()));

        // Mods folder shortcut — Fabric (and Forge-style) mods go here
        Button modsBtn = new Button("📁 Mods folder");
        modsBtn.getStyleClass().add("btn-ghost");
        modsBtn.setMaxWidth(Double.MAX_VALUE);
        modsBtn.setOnAction(e -> openModsFolder());
        UiAnim.buttonHover(modsBtn, 1.02);
        Label modsHint = new Label("Fabric loads .jar mods from here — just drag your mods in.");
        modsHint.getStyleClass().add("label-dim");
        modsHint.setWrapText(true);
        modsHint.setVisible(false);
        modsHint.setManaged(false);
        modsHintLabel = modsHint;

        rightCard.getChildren().addAll(rightTitle, memV, snapshotCk, modsBtn, modsHint, javaInfo, openDir);

        // two columns side by side
        HBox columns = new HBox(10, leftCard, rightCard);
        columns.setMaxWidth(Double.MAX_VALUE);

        // play button — premium: aurora gradient, ripple, hover lift + sheen sweep
        playBtn.getStyleClass().add("btn-primary");
        playBtn.setPrefHeight(46);
        playBtn.setOnAction(e -> onPlay());
        StackPane playWrap = UiAnim.rippleWrap(playBtn);
        playWrap.setMaxWidth(Double.MAX_VALUE);
        UiAnim.buttonHoverSheen(playBtn, 1.015);
        UiAnim.loopShine(playWrap, Duration.seconds(3.2));   // continuous glass shimmer
        UiAnim.pulseGlow(playBtn, javafx.scene.paint.Color.rgb(255, 78, 193));

        // status
        statusLabel.getStyleClass().addAll("label-dim", "status-pill");
        statusLabel.setWrapText(true);

        // progress card
        progressCard.getStyleClass().add("card-inline");
        progressCard.setVisible(false);
        progressCard.setManaged(false);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        fileLabel.getStyleClass().add("label-mono");
        fileLabel.setWrapText(true);
        bytesLabel.getStyleClass().add("label-dim");
        cancelBtn.getStyleClass().add("btn-ghost");
        cancelBtn.setOnAction(e -> { if (installer != null) installer.cancel(); });
        UiAnim.buttonHover(cancelBtn, 1.05);
        HBox progressHead = new HBox(10, bytesLabel, ConsoleView.spacerNode(), cancelBtn);
        progressCard.getChildren().addAll(progressBar, fileLabel, progressHead);

        // running card
        runningCard.getStyleClass().add("card-inline");
        runningCard.setVisible(false);
        runningCard.setManaged(false);
        Label runningTitle = new Label("Minecraft is running");
        runningTitle.getStyleClass().add("label-ok");
        Label runningHint = new Label("Output streams to the Console tab. Close the game window to stop.");
        runningHint.getStyleClass().add("label-dim");
        stopBtn.getStyleClass().add("btn-danger");
        stopBtn.setMaxWidth(140);
        stopBtn.setOnAction(e -> stopGame());
        UiAnim.buttonHover(stopBtn, 1.05);
        HBox stopRow = new HBox(10, stopBtn, runningHint);
        runningCard.getChildren().addAll(runningTitle, stopRow);

        getChildren().addAll(columns, playWrap, statusLabel, progressCard, runningCard);

        versionBox.valueProperty().addListener((o, a, b) -> {
            if (b != null) {
                config.lastVersion = b.id();
                config.save();
                updatePlayLabel();
            }
        });

        refreshVersions(false);
        updateModsHint();
    }

    /** Creates the mods folder (if needed) and opens it in the file manager. */
    private void openModsFolder() {
        try {
            java.nio.file.Files.createDirectories(AppPaths.modsDir());
            DesktopUtil.open(AppPaths.modsDir());
            statusLabel.setText("Mods folder: " + AppPaths.modsDir());
        } catch (IOException e) {
            statusLabel.setText("Could not open mods folder: " + e.getMessage());
        }
    }

    /** Shows a hint next to the Mods button when a mod loader is selected. */
    private void updateModsHint() {
        if (modsHintLabel == null) return;
        boolean modded = !"vanilla".equals(config.modloader);
        modsHintLabel.setVisible(modded);
        modsHintLabel.setManaged(modded);
        modsHintLabel.setText("fabric".equals(config.modloader)
                ? "Fabric loads .jar mods from here — drag your mods in, then press Play."
                : "OptiFine uses this folder for shader packs & configs.");
    }

    private ModLoader selectedModLoader() {
        for (ModLoader m : MODLOADERS) {
            if (m.id().equals(config.modloader)) return m;
        }
        return MODLOADERS[0];
    }

    /** Filters the version combo by the search box. */
    private void applyVersionFilter(String q) {
        if (allVersions.isEmpty()) return;
        String query = q == null ? "" : q.trim().toLowerCase();
        List<ManifestService.VersionEntry> shown = new ArrayList<>();
        for (ManifestService.VersionEntry e : allVersions) {
            if (e.id().toLowerCase().contains(query)) shown.add(e);
        }
        versionBox.getItems().setAll(shown);
        if (config.lastVersion != null) {
            versionBox.setValue(shown.stream()
                    .filter(e -> e.id().equals(config.lastVersion)).findFirst().orElse(null));
        }
    }

    // ---------------------------------------------------------------- actions

    private void refreshVersions(boolean force) {
        setBusy(true);
        statusLabel.setText(force ? "Refreshing version list…" : "Loading version list…");
        worker.execute(() -> {
            try {
                VersionManifest m = manifestService.getManifest(force);
                List<ManifestService.VersionEntry> official = manifestService.listPlayable(config.showSnapshots);
                List<ManifestService.VersionEntry> installed = manifestService.listLocalInstalled();
                Set<String> officialIds = new HashSet<>();
                for (ManifestService.VersionEntry e : official) officialIds.add(e.id());

                List<ManifestService.VersionEntry> all = new ArrayList<>();
                List<ManifestService.VersionEntry> shownInstalled = new ArrayList<>();
                for (ManifestService.VersionEntry e : installed) {
                    if (officialIds.contains(e.id())) continue;   // plain vanilla: keep official entry
                    shownInstalled.add(e);
                }
                all.addAll(shownInstalled);
                all.addAll(official);

                Platform.runLater(() -> {
                    allVersions = all;
                    versionBox.getItems().setAll(all);
                    if (versionSearch.getText() != null && !versionSearch.getText().isBlank()) {
                        applyVersionFilter(versionSearch.getText());
                    }
                    ManifestService.VersionEntry pick = null;
                    if (config.lastVersion != null) {
                        pick = all.stream().filter(e -> e.id().equals(config.lastVersion)).findFirst().orElse(null);
                    }
                    if (pick == null) {
                        pick = all.stream().filter(e -> e.id().equals(m.latest.release)).findFirst().orElse(null);
                    }
                    if (pick != null) versionBox.setValue(pick);
                    String latest = m.latest != null ? m.latest.release : "?";
                    statusLabel.setText("Latest release: " + latest + "  •  " + official.size()
                            + " official" + (shownInstalled.isEmpty() ? "" : "  •  " + shownInstalled.size() + " installed modded"));
                    setBusy(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Could not load versions: " + e.getMessage());
                    setBusy(false);
                });
            }
        });
    }

    private void onPlay() {
        String name = nameField.getText().trim();
        String err = OfflineAccount.error(name);
        if (err != null) {
            nameError.setText(err);
            nameError.setVisible(true);
            return;
        }
        nameError.setVisible(false);

        ManifestService.VersionEntry entry = versionBox.getValue();
        if (entry == null) {
            statusLabel.setText("Select a version first.");
            return;
        }
        config.username = name;
        config.save();

        setBusy(true);
        worker.execute(() -> {
            try {
                ModLoader mod = selectedModLoader();
                ManifestService.VersionEntry target = entry;
                String modSuffix = mod != null && !"vanilla".equals(mod.id()) ? " with " + mod.label() : "";

                // resolve the target version id (install modloader profile if needed)
                if ("installed".equals(entry.type())) {
                    modSuffix = "";
                } else if (mod != null && "fabric".equals(mod.id())) {
                    status("Preparing Fabric for " + entry.id() + "…");
                    target = fabricService.ensure(entry.id());
                } else if (mod != null && "optifine".equals(mod.id())) {
                    status("Preparing base " + entry.id() + "…");
                    ensureInstalled(entry);                       // OptiFine needs the vanilla client present
                    status("Preparing OptiFine for " + entry.id() + "…");
                    target = optifineService.ensure(entry.id());
                }

                VersionJson v = manifestService.loadResolved(target.id());
                if (GameInstaller.needsInstall(v)) {
                    status("Downloading " + v.id + modSuffix + "…");
                    installer = new GameInstaller();
                    Platform.runLater(() -> showProgressCard(true));
                    installer.install(v, target, listener());
                }
                if (installer != null && installer.wasCancelled()) {
                    Platform.runLater(() -> { showProgressCard(false); statusLabel.setText("Download cancelled."); setBusy(false); });
                    return;
                }

                final ManifestService.VersionEntry launchEntry = target;
                Platform.runLater(() -> showProgressCard(false));
                status("Starting " + launchEntry.id() + "…");
                String uuid = OfflineAccount.uuid(name);
                GameLauncher launcher = new GameLauncher();
                GameLauncher.LaunchCommand cmd = launcher.buildCommand(v, launchEntry, name, uuid, config);
                LauncherLog.log("Launch command: " + redact(String.join(" ", cmd.command())));
                Process p = launcher.launch(cmd, line -> LauncherLog.gameLine(line),
                        code -> Platform.runLater(() -> onGameExit(code)));
                gameProcess = p;
                Platform.runLater(() -> {
                    showRunningCard(true);
                    config.lastVersion = launchEntry.id();
                    config.save();
                    parent.navigate("console");
                });
                refreshVersionsAsync();
            } catch (IOException e) {
                if ("cancelled".equals(e.getMessage())) {
                    Platform.runLater(() -> { showProgressCard(false); statusLabel.setText("Download cancelled."); setBusy(false); });
                } else {
                    LauncherLog.log("ERROR: " + e.getMessage());
                    Platform.runLater(() -> {
                        showProgressCard(false);
                        statusLabel.setText("Failed: " + e.getMessage());
                        setBusy(false);
                        showErrorDialog("Could not start the game", e.getMessage());
                    });
                }
            } catch (Exception e) {
                LauncherLog.log("ERROR: " + e);
                Platform.runLater(() -> {
                    showProgressCard(false);
                    statusLabel.setText("Failed: " + e.getMessage());
                    setBusy(false);
                    showErrorDialog("Could not start the game", e.getMessage());
                });
            }
        });
    }

    /** Pops a clear error dialog (e.g. missing Java 21 for new versions). */
    private void showErrorDialog(String title, String message) {
        try {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR);
            alert.setTitle("Prex Launcher");
            alert.setHeaderText(title);
            alert.setContentText(message);
            alert.showAndWait();
        } catch (Exception ignored) {}
    }

    /** Installs a plain vanilla version (used as base for OptiFine). */
    private void ensureInstalled(ManifestService.VersionEntry entry) throws IOException {
        VersionJson v = manifestService.loadResolved(entry.id());
        if (!GameInstaller.needsInstall(v)) return;
        installer = new GameInstaller();
        Platform.runLater(() -> showProgressCard(true));
        installer.install(v, entry, listener());
        if (installer.wasCancelled()) throw new IOException("cancelled");
    }

    private GameInstaller.Listener listener() {
        return new GameInstaller.Listener() {
            @Override public void onStatus(String msg) { status(msg); }
            @Override public void onProgress(long done, long total, String file) {
                Platform.runLater(() -> updateProgress(done, total, file));
            }
        };
    }

    private void status(String msg) {
        Platform.runLater(() -> statusLabel.setText(msg));
    }

    private void refreshVersionsAsync() {
        // refresh the list in the background so installed modded versions appear
        worker.execute(() -> {
            try {
                VersionManifest m = manifestService.getManifest(false);
                List<ManifestService.VersionEntry> official = manifestService.listPlayable(config.showSnapshots);
                Set<String> officialIds = new HashSet<>();
                for (ManifestService.VersionEntry e : official) officialIds.add(e.id());
                List<ManifestService.VersionEntry> all = new ArrayList<>();
                List<ManifestService.VersionEntry> installed = new ArrayList<>();
                for (ManifestService.VersionEntry e : manifestService.listLocalInstalled()) {
                    if (!officialIds.contains(e.id())) installed.add(e);
                }
                all.addAll(installed);
                all.addAll(official);
                Platform.runLater(() -> {
                    String sel = versionBox.getValue() != null ? versionBox.getValue().id() : null;
                    versionBox.getItems().setAll(all);
                    if (sel != null) {
                        versionBox.setValue(all.stream().filter(e -> e.id().equals(sel)).findFirst().orElse(null));
                    }
                });
            } catch (Exception ignored) {}
        });
    }

    private void onGameExit(int code) {
        showRunningCard(false);
        setBusy(false);
        if (code == 0) statusLabel.setText("Game exited normally.");
        else statusLabel.setText("Game exited with code " + code
                + " — check crash-reports/ inside the game folder for details.");
    }

    private void stopGame() {
        Process p = gameProcess;
        if (p != null && p.isAlive()) {
            p.destroy();
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "stop-helper");
                t.setDaemon(true);
                return t;
            }).schedule(() -> {
                if (p.isAlive()) p.destroyForcibly();
            }, 6, java.util.concurrent.TimeUnit.SECONDS);
            statusLabel.setText("Stopping the game…");
        }
    }

    // ---------------------------------------------------------------- helpers

    private void updatePlayLabel() {
        ManifestService.VersionEntry entry = versionBox.getValue();
        if (entry == null) return;
        ModLoader mod = selectedModLoader();
        boolean installed = isInstalled(entry.id());
        String base = installed ? "▶  PLAY" : "⬇  DOWNLOAD & PLAY";
        if (mod != null && !"vanilla".equals(mod.id()) && !"installed".equals(entry.type())) {
            base += "  " + mod.label();
        }
        playBtn.setText(base);
    }

    private void updateProgress(long done, long total, String file) {
        progressBar.setProgress(total > 0 ? Math.min(1.0, (double) done / total) : -1);
        if (file != null && !file.isBlank()) fileLabel.setText(file);
        long now = System.currentTimeMillis();
        String speed = "";
        if (lastDone >= 0 && now > lastTime) {
            double mbps = (done - lastDone) / 1048576.0 / ((now - lastTime) / 1000.0);
            if (mbps > 0.01) speed = String.format("  •  %.1f MB/s", mbps);
        }
        lastDone = done;
        lastTime = now;
        bytesLabel.setText(fmt(done) + " / " + fmt(total) + speed);
    }

    private void showProgressCard(boolean show) {
        progressCard.setVisible(show);
        progressCard.setManaged(show);
        if (show) {
            progressBar.setProgress(0);
            fileLabel.setText("");
            bytesLabel.setText("");
            lastDone = -1;
            lastTime = 0;
        }
    }

    private void showRunningCard(boolean show) {
        runningCard.setVisible(show);
        runningCard.setManaged(show);
        playBtn.setVisible(!show);
        playBtn.setManaged(!show);
    }

    private void setBusy(boolean busy) {
        playBtn.setDisable(busy);
        nameField.setDisable(busy);
        versionBox.setDisable(busy);
        for (ToggleButton c : modChips.values()) c.setDisable(busy);
        snapshotCk.setDisable(busy);
        memSlider.setDisable(busy);
    }

    private boolean isInstalled(String id) {
        try {
            VersionJson v = manifestService.loadResolved(id);
            return Files.isRegularFile(AppPaths.clientJar(v.baseId != null ? v.baseId : id));
        } catch (Exception e) {
            return false;
        }
    }

    /** Removes the offline access token from a printed command line. */
    private static String redact(String cmd) {
        return cmd.replaceAll("(?i)(accessToken|auth_access_token|--accessToken)\\s+\\S+", "$1 ***");
    }

    private static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format("%.0f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / 1048576.0);
    }

    public void shutdown() {
        worker.shutdownNow();
    }


}
