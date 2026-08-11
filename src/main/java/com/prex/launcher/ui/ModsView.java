package com.prex.launcher.ui;

import com.prex.launcher.core.AppPaths;
import com.prex.launcher.core.DesktopUtil;
import com.prex.launcher.core.LauncherConfig;
import com.prex.launcher.core.LauncherLog;
import com.prex.launcher.core.ModManager;
import com.prex.launcher.core.ModrinthService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mods page: search Modrinth, download mods into the game's mods/ folder,
 * and toggle installed mods on/off with one click.
 */
public class ModsView extends VBox {

    private static final String[] LOADERS = {"fabric", "forge", "quilt"};

    private final LauncherConfig config;
    private final ModrinthService modrinth = new ModrinthService();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "prex-mods");
        t.setDaemon(true);
        return t;
    });

    // search controls
    private final TextField searchField = new TextField();
    private final TextField versionField = new TextField();
    private final Map<String, ToggleButton> loaderChips = new HashMap<>();
    private final VBox resultsBox = new VBox(8);
    private final VBox installedBox = new VBox(8);

    // download progress
    private final VBox progressCard = new VBox(6);
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label fileLabel = new Label();
    private final Label bytesLabel = new Label();

    private final Label statusLabel = new Label();
    private final Set<String> installedNames = new HashSet<>();
    private volatile boolean cancelled;
    private long lastDone = -1;
    private long lastTime = 0;

    public ModsView(LauncherConfig config) {
        this.config = config;
        getStyleClass().add("page");
        setSpacing(10);

        Label title = new Label("Mods");
        title.getStyleClass().add("label-h1");
        Label sub = new Label("Search Modrinth, download mods, and toggle them on/off — all from here.");
        sub.getStyleClass().add("label-dim");

        // ---------------- search card ----------------
        VBox searchCard = new VBox(10);
        searchCard.getStyleClass().add("card");

        searchField.setPromptText("Search mods\u2026 e.g. sodium, jei, shaders");
        searchField.getStyleClass().add("field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        Button searchBtn = new Button("\u2315  Search");
        searchBtn.getStyleClass().add("btn-primary");
        searchBtn.setOnAction(e -> doSearch());
        UiAnim.buttonHover(searchBtn, 1.03);
        Button openBtn = new Button("\ud83d\udcc1 Mods folder");
        openBtn.getStyleClass().add("btn-ghost");
        openBtn.setOnAction(e -> openModsFolder());
        UiAnim.buttonHover(openBtn, 1.02);
        HBox searchRow = new HBox(8, searchField, searchBtn, openBtn);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        Label optLbl = new Label("FOR GAME VERSION \u2022 LOADER");
        optLbl.getStyleClass().add("label-field");
        versionField.getStyleClass().add("field");
        versionField.setPrefColumnCount(8);
        versionField.setText(config.lastVersion);
        versionField.setPromptText("1.20.1");
        HBox verRow = new HBox(8, new Label("Version"), versionField);

        ToggleGroup loaderGroup = new ToggleGroup();
        HBox loaderRow = new HBox(8);
        loaderRow.setAlignment(Pos.CENTER_LEFT);
        for (String l : LOADERS) {
            ToggleButton chip = new ToggleButton(cap(l));
            chip.getStyleClass().add("chip-btn");
            chip.setToggleGroup(loaderGroup);
            chip.setUserData(l);
            chip.setSelected(l.equals("fabric"));
            UiAnim.buttonHover(chip, 1.05);
            loaderChips.put(l, chip);
            loaderRow.getChildren().add(chip);
        }
        HBox filterRow = new HBox(16, verRow, loaderRow);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        searchCard.getChildren().addAll(searchRow, optLbl, filterRow);

        // ---------------- results ---------------
        resultsBox.getStyleClass().add("mod-list");
        ScrollPane resultsScroll = wrapScroll(resultsBox);

        Label resultsTitle = new Label("Search results");
        resultsTitle.getStyleClass().add("label-h2");

        VBox resultsCard = new VBox(8);
        resultsCard.getStyleClass().add("card");
        HBox.setHgrow(resultsCard, Priority.ALWAYS);
        resultsCard.getChildren().addAll(resultsTitle, resultsScroll);

        // ---------------- installed ---------------
        Label installedTitle = new Label("Installed mods");
        installedTitle.getStyleClass().add("label-h2");
        Button refreshBtn = new Button("\u21bb");
        refreshBtn.getStyleClass().add("btn-ghost");
        refreshBtn.setOnAction(e -> refreshInstalled());
        UiAnim.buttonHover(refreshBtn, 1.05);
        HBox installedHead = new HBox(8, installedTitle, ConsoleView.spacerNode(), refreshBtn);
        installedHead.setAlignment(Pos.CENTER_LEFT);

        installedBox.getStyleClass().add("mod-list");
        ScrollPane installedScroll = wrapScroll(installedBox);

        VBox installedCard = new VBox(8);
        installedCard.getStyleClass().add("card");
        installedCard.setPrefWidth(340);
        installedCard.setMinWidth(320);
        installedCard.getChildren().addAll(installedHead, installedScroll);

        // two-column body
        HBox columns = new HBox(10, resultsCard, installedCard);
        columns.setMaxWidth(Double.MAX_VALUE);

        // ---------------- progress ----------------
        progressCard.getStyleClass().add("card-inline");
        progressCard.setVisible(false);
        progressCard.setManaged(false);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        fileLabel.getStyleClass().add("label-mono");
        fileLabel.setWrapText(true);
        bytesLabel.getStyleClass().add("label-dim");
        Button cancelBtn = new Button("Cancel download");
        cancelBtn.getStyleClass().add("btn-ghost");
        cancelBtn.setOnAction(e -> cancelled = true);
        UiAnim.buttonHover(cancelBtn, 1.05);
        HBox progressHead = new HBox(10, bytesLabel, ConsoleView.spacerNode(), cancelBtn);
        progressCard.getChildren().addAll(progressBar, fileLabel, progressHead);

        statusLabel.getStyleClass().addAll("label-dim", "status-pill");
        statusLabel.setWrapText(true);

        getChildren().addAll(title, sub, searchCard, columns, progressCard, statusLabel);

        refreshInstalled();
    }

    // ---------------------------------------------------------------- helpers

    private static ScrollPane wrapScroll(VBox content) {
        ScrollPane sp = new ScrollPane(content);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sp.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sp.getStyleClass().add("mod-scroll");
        sp.setPrefHeight(430);
        return sp;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void openModsFolder() {
        try {
            Files.createDirectories(AppPaths.modsDir());
            DesktopUtil.open(AppPaths.modsDir());
            statusLabel.setText("Mods folder: " + AppPaths.modsDir());
        } catch (IOException e) {
            statusLabel.setText("Could not open mods folder: " + e.getMessage());
        }
    }

    private String selectedLoader() {
        for (Map.Entry<String, ToggleButton> e : loaderChips.entrySet()) {
            if (e.getValue().isSelected()) return e.getKey();
        }
        return "fabric";
    }

    // ---------------------------------------------------------------- search

    private void doSearch() {
        String q = searchField.getText().trim();
        if (q.isEmpty()) {
            statusLabel.setText("Type a mod name to search.");
            return;
        }
        String loader = selectedLoader();
        String version = versionField.getText().trim();
        setSearching(true);
        statusLabel.setText("Searching Modrinth for \u201c" + q + "\u201d\u2026");
        worker.execute(() -> {
            try {
                ModrinthService.SearchResult res = modrinth.search(q, loader, version, ModrinthService.DEFAULT_LIMIT);
                List<ModrinthService.SearchHit> hits = res.hits() == null ? List.of() : res.hits();
                Platform.runLater(() -> renderResults(hits, loader, version));
            } catch (Exception e) {
                LauncherLog.log("Modrinth search failed: " + e.getMessage());
                Platform.runLater(() -> {
                    statusLabel.setText("Search failed: " + e.getMessage());
                    setSearching(false);
                });
            }
        });
    }

    private void renderResults(List<ModrinthService.SearchHit> hits, String loader, String version) {
        resultsBox.getChildren().clear();
        if (hits.isEmpty()) {
            Label none = new Label("No mods found. Try a different name, version, or loader.");
            none.getStyleClass().add("label-dim");
            resultsBox.getChildren().add(none);
        } else {
            for (ModrinthService.SearchHit hit : hits) {
                resultsBox.getChildren().add(buildResultCard(hit, loader, version));
            }
        }
        statusLabel.setText(hits.size() + " result" + (hits.size() == 1 ? "" : "s")
                + " (loader: " + cap(loader) + (version.isBlank() ? "" : ", MC " + version) + ")");
        setSearching(false);
    }

    private Node buildResultCard(ModrinthService.SearchHit hit, String loader, String version) {
        HBox card = new HBox(12);
        card.getStyleClass().add("mod-card");
        card.setAlignment(Pos.CENTER_LEFT);

        ImageView icon = new ImageView();
        icon.setFitWidth(40);
        icon.setFitHeight(40);
        icon.getStyleClass().add("mod-icon");
        if (hit.icon_url() != null && !hit.icon_url().isBlank()) {
            icon.setImage(new Image(hit.icon_url(), 40, 40, true, true, true));
        }
        VBox info = new VBox(2);
        HBox.setHgrow(info, Priority.ALWAYS);
        Label name = new Label(hit.title());
        name.getStyleClass().add("mod-name");
        Label desc = new Label(trimTo(hit.description(), 120));
        desc.getStyleClass().add("label-dim");
        desc.setWrapText(true);
        Label meta = new Label(fmtDownloads(hit.downloads())
                + (hit.categories() == null || hit.categories().isEmpty() ? "" : "  •  " + cap(loader)));
        meta.getStyleClass().add("label-dim");
        info.getChildren().addAll(name, desc, meta);

        Button installBtn = new Button("Install");
        installBtn.getStyleClass().add("btn-ghost");
        installBtn.setPrefWidth(110);
        UiAnim.buttonHover(installBtn, 1.04);
        installBtn.setOnAction(e -> install(hit, loader, version, installBtn));

        card.getChildren().addAll(icon, info, installBtn);
        return card;
    }

    // ---------------------------------------------------------------- install

    private void install(ModrinthService.SearchHit hit, String loader, String version, Button btn) {
        btn.setDisable(true);
        btn.setText("…");
        showProgress(true);
        cancelled = false;
        statusLabel.setText("Fetching " + hit.title() + " versions\u2026");
        worker.execute(() -> {
            try {
                List<ModrinthService.ProjectVersion> versions =
                        modrinth.versions(hit.project_id(), version, loader);
                if (versions.isEmpty()) {
                    Platform.runLater(() -> {
                        statusLabel.setText("No " + cap(loader) + " version of \u201c" + hit.title()
                                + "\u201d for MC " + (version.isBlank() ? "?" : version));
                        btn.setDisable(false);
                        btn.setText("Install");
                        showProgress(false);
                    });
                    return;
                }
                // newest compatible version, preferring the primary file
                ModrinthService.ProjectVersion v = versions.get(0);
                ModrinthService.FileEntry file = v.primaryFile();
                if (file == null) {
                    throw new IOException("Version has no downloadable file");
                }
                long total = file.size();
                Platform.runLater(() -> {
                    fileLabel.setText(hit.title() + "  —  " + file.filename());
                    bytesLabel.setText(fmt(0) + " / " + fmt(total));
                });
                Path mods = AppPaths.modsDir();
                Path dest = modrinth.download(file, mods, this::onBytes, () -> cancelled);
                if (dest == null) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Download cancelled.");
                        btn.setDisable(false);
                        btn.setText("Install");
                        showProgress(false);
                    });
                    return;
                }
                Platform.runLater(() -> {
                    statusLabel.setText("Installed " + file.filename());
                    btn.setDisable(false);
                    btn.setText("\u2713 Installed");
                    showProgress(false);
                    refreshInstalled();
                });
            } catch (Exception e) {
                LauncherLog.log("Mod install failed: " + e.getMessage());
                Platform.runLater(() -> {
                    statusLabel.setText("Install failed: " + e.getMessage());
                    btn.setDisable(false);
                    btn.setText("Install");
                    showProgress(false);
                });
            }
        });
    }

    private void onBytes(long n) {
        lastDone += n;
        long now = System.currentTimeMillis();
        String speed = "";
        if (lastDone >= 0 && now > lastTime) {
            double mbps = n / 1048576.0 / ((now - lastTime) / 1000.0);
            if (mbps > 0.01) speed = String.format("  •  %.1f MB/s", mbps);
        }
        lastTime = now;
        String s = fmt(lastDone) + speed;
        Platform.runLater(() -> bytesLabel.setText(s));
    }

    // ---------------------------------------------------------------- installed

    private void refreshInstalled() {
        List<ModManager.ModEntry> mods = ModManager.list();
        installedNames.clear();
        for (ModManager.ModEntry m : mods) installedNames.add(m.displayName());
        Platform.runLater(() -> renderInstalled(mods));
    }

    private void renderInstalled(List<ModManager.ModEntry> mods) {
        installedBox.getChildren().clear();
        if (mods.isEmpty()) {
            Label none = new Label("No mods installed yet.\nDownload some from the search panel.");
            none.getStyleClass().add("label-dim");
            none.setWrapText(true);
            installedBox.getChildren().add(none);
            return;
        }
        for (ModManager.ModEntry m : mods) {
            installedBox.getChildren().add(buildInstalledRow(m));
        }
    }

    private Node buildInstalledRow(ModManager.ModEntry entry) {
        HBox row = new HBox(8);
        row.getStyleClass().add("mod-row");
        row.setAlignment(Pos.CENTER_LEFT);

        Label name = new Label(entry.displayName());
        name.getStyleClass().add("mod-name");
        name.setMaxWidth(170);
        HBox.setHgrow(name, Priority.ALWAYS);

        Label state = new Label(entry.enabled() ? "ON" : "OFF");
        state.getStyleClass().add(entry.enabled() ? "mod-on" : "mod-off");

        ToggleButton toggle = new ToggleButton(entry.enabled() ? "Disable" : "Enable");
        toggle.getStyleClass().add(entry.enabled() ? "btn-danger" : "btn-ghost");
        toggle.setOnAction(e -> toggleMod(entry, toggle, state));
        UiAnim.buttonHover(toggle, 1.05);

        row.getChildren().addAll(name, state, toggle);
        return row;
    }

    private void toggleMod(ModManager.ModEntry entry, ToggleButton toggle, Label state) {
        boolean enable = !entry.enabled();
        try {
            ModManager.ModEntry updated = ModManager.setEnabled(entry.path(), enable);
            if (updated == null) return;
            toggle.setText(enable ? "Disable" : "Enable");
            toggle.getStyleClass().clear();
            toggle.getStyleClass().add(enable ? "btn-danger" : "btn-ghost");
            state.setText(enable ? "ON" : "OFF");
            state.getStyleClass().clear();
            state.getStyleClass().add(enable ? "mod-on" : "mod-off");
            statusLabel.setText((enable ? "Enabled " : "Disabled ") + updated.displayName());
            refreshInstalled();
        } catch (IOException e) {
            statusLabel.setText("Could not toggle " + entry.displayName() + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- misc

    private void showProgress(boolean show) {
        progressCard.setVisible(show);
        progressCard.setManaged(show);
        if (show) {
            progressBar.setProgress(-1);
            fileLabel.setText("");
            bytesLabel.setText("");
            lastDone = 0;
            lastTime = System.currentTimeMillis();
        }
    }

    private void setSearching(boolean s) {
        for (ToggleButton c : loaderChips.values()) c.setDisable(s);
        searchField.setDisable(s);
        versionField.setDisable(s);
    }

    private static String trimTo(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max).trim() + "\u2026";
    }

    private static String fmtDownloads(long n) {
        if (n < 1000) return n + " downloads";
        if (n < 1_000_000) return String.format("%.1fk downloads", n / 1000.0);
        return String.format("%.1fM downloads", n / 1_000_000.0);
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
