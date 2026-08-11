package com.prex.launcher;

import com.prex.launcher.core.LauncherConfig;
import com.prex.launcher.ui.MainView;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Headless UI test: constructs the ENTIRE UI (Play + Settings + Console),
 * navigates every page, and reports any construction crash
 * (e.g. JavaFX "duplicate children").
 */
public class UiCrashTest {

    public static void main(String[] args) throws Exception {
        Platform.startup(() -> {
            try {
                LauncherConfig config = LauncherConfig.load();
                MainView mv = new MainView(config);
                Scene scene = new Scene(mv, 1280, 800);
                Stage stage = new Stage();
                stage.setScene(scene);
                stage.show();

                // visit every page
                mv.navigate("settings");
                mv.navigate("console");
                mv.navigate("play");

                PauseTransition wait = new PauseTransition(Duration.seconds(3));
                wait.setOnFinished(e -> {
                    System.out.println("UI_TEST_PASSED — all pages constructed without errors");
                    Platform.exit();
                });
                wait.play();
            } catch (Throwable t) {
                t.printStackTrace();
                System.out.println("UI_TEST_FAILED: " + t);
                Platform.exit();
            }
        });
    }
}
