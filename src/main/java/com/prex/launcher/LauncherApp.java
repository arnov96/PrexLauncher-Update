package com.prex.launcher;

import com.prex.launcher.core.LauncherConfig;
import com.prex.launcher.core.LauncherLog;
import com.prex.launcher.ui.MainView;
import com.prex.launcher.ui.ThemeManager;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Prex Launcher entry point.
 *
 * Run:            mvn javafx:run
 * Screenshot dev: mvn -q exec:java -Dexec.args="--screenshot /tmp/shots"   (headless theme preview)
 */
public class LauncherApp extends Application {

    private static String screenshotDir;

    public static void main(String[] args) {
        if (args.length >= 2 && "--screenshot".equals(args[0])) {
            screenshotDir = args[1];
        }
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        // log any error on the FX application thread (the source of "fatal exception")
        Thread.currentThread().setUncaughtExceptionHandler((t, ex) -> LauncherLog.crash(ex));
        try {
            startInner(stage);
        } catch (Throwable t) {
            LauncherLog.crash(t);
            try {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.ERROR);
                alert.setTitle("Prex Launcher");
                alert.setHeaderText("Something went wrong on startup");
                alert.setContentText(t + "\n\nDetails saved to logs/crash.log — send that file for help.");
                alert.showAndWait();
            } catch (Throwable ignored) {}
            Platform.exit();
        }
    }

    private void startInner(Stage stage) {
        LauncherConfig config = LauncherConfig.load();
        com.prex.launcher.ui.UiAnim.setEnabled(!config.lowFxMode);   // apply low-FX at startup
        MainView mainView = new MainView(config);
        Scene scene = new Scene(mainView, 1280, 800);
        mainView.setOnThemeChange(theme -> ThemeManager.apply(scene, theme));
        ThemeManager.apply(scene, config.theme);

        stage.setTitle("Prex Launcher");
        stage.setMinWidth(1080);
        stage.setMinHeight(660);
        stage.centerOnScreen();
        try (InputStream in = LauncherApp.class.getResourceAsStream("/images/icon.png")) {
            if (in != null) stage.getIcons().add(new Image(in));
        } catch (Exception ignored) {}

        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            mainView.shutdown();
            config.save();
        });
        stage.show();
        LauncherLog.log("Prex Launcher started (theme=" + config.theme + ")");

        if (screenshotDir != null) {
            PauseTransition first = new PauseTransition(Duration.seconds(2.2));
            first.setOnFinished(e -> {
                try {
                    capture(scene, Path.of(screenshotDir, "modern.png"));
                    ThemeManager.apply(scene, ThemeManager.CLASSIC);
                    PauseTransition second = new PauseTransition(Duration.seconds(1.2));
                    second.setOnFinished(e2 -> {
                        try {
                            capture(scene, Path.of(screenshotDir, "classic.png"));
                            ThemeManager.apply(scene, ThemeManager.MODERN);
                            mainView.navigate("settings");
                            PauseTransition third = new PauseTransition(Duration.seconds(1.4));
                            third.setOnFinished(e3 -> {
                                try {
                                    capture(scene, Path.of(screenshotDir, "settings.png"));
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                                Platform.exit();
                            });
                            third.play();
                        } catch (Exception ex) {
                            ex.printStackTrace();
                            Platform.exit();
                        }
                    });
                    second.play();
                } catch (Exception ex) {
                    ex.printStackTrace();
                    Platform.exit();
                }
            });
            first.play();
        }
    }

    private static void capture(Scene scene, Path out) throws Exception {
        WritableImage img = scene.snapshot(null);
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) java.nio.file.Files.createDirectories(parent);
        ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", out.toFile());
        System.out.println("Screenshot saved: " + out.toAbsolutePath());
    }
}
