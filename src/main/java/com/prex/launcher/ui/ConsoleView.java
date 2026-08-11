package com.prex.launcher.ui;

import com.prex.launcher.core.ConsoleBus;
import com.prex.launcher.core.LauncherLog;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.util.Duration;

/** Live console: launcher log + the running game's stdout, auto-scrolled. */
public class ConsoleView extends VBox {

    private final TextArea area = new TextArea();
    private final CheckBox autoScroll = new CheckBox("Auto-scroll");
    private final StringBuilder pending = new StringBuilder();

    public ConsoleView() {
        getStyleClass().add("console-view");
        setSpacing(10);
        setPadding(new Insets(6, 0, 0, 0));

        Label title = new Label("Console");
        title.getStyleClass().add("label-h2");

        Button clear = new Button("Clear");
        clear.getStyleClass().add("btn-ghost");
        clear.setOnAction(e -> area.clear());

        Button copy = new Button("Copy all");
        copy.getStyleClass().add("btn-ghost");
        copy.setOnAction(e -> {
            javafx.scene.input.Clipboard cb = javafx.scene.input.Clipboard.getSystemClipboard();
            cb.setContent(java.util.Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, area.getText()));
        });

        Button openLogs = new Button("Open logs folder");
        openLogs.getStyleClass().add("btn-ghost");
        openLogs.setOnAction(e -> com.prex.launcher.core.DesktopUtil.open(
                com.prex.launcher.core.AppPaths.logsDir()));

        autoScroll.setSelected(true);
        autoScroll.getStyleClass().add("field");

        HBox toolbar = new HBox(8, title, spacerNode(), clear, copy, openLogs, spacerNode(), autoScroll);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        area.setEditable(false);
        area.setWrapText(false);
        area.setFont(Font.font("monospace", 12));
        VBox.setVgrow(area, Priority.ALWAYS);

        getChildren().addAll(toolbar, area);
        area.appendText("Prex Launcher console — launcher log and game output appear here.\n\n");

        // Batch console updates onto the FX thread (200 ms) — keeps typing/UI smooth
        ConsoleBus.subscribe(line -> {
            synchronized (pending) {
                pending.append(line).append('\n');
                if (pending.length() > 400_000) {
                    pending.delete(0, 200_000);
                }
            }
        });
        Timeline flush = new Timeline(new KeyFrame(Duration.millis(200), e -> flushPending()));
        flush.setCycleCount(Timeline.INDEFINITE);
        flush.play();
    }

    private void flushPending() {
        String batch;
        synchronized (pending) {
            if (pending.length() == 0) return;
            batch = pending.toString();
            pending.setLength(0);
        }
        if (area.getLength() > 250_000) {
            area.deleteText(0, 150_000);
        }
        area.appendText(batch);
        if (autoScroll.isSelected()) area.setScrollTop(Double.MAX_VALUE);
    }

    public static javafx.scene.Node spacerNode() {
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
