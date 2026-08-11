package com.prex.launcher.ui;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Small motion toolkit for the liquid-glass UI:
 *  - hover/press scale on buttons
 *  - breathing glow
 *  - periodic diagonal "shine sweep" across panels (glass reflection)
 *  - page enter transition
 */
public final class UiAnim {

    /** Set from config.lowFxMode — when false, every animation becomes a no-op (zero cost). */
    public static boolean enabled = true;

    /** Active indefinite animations, so the low-FX toggle can pause them live. */
    private static final java.util.List<Animation> tracked = new ArrayList<>();

    private UiAnim() {}

    /** Toggles animations on/off and pauses/resumes the live ones (low-FX switch). */
    public static void setEnabled(boolean on) {
        if (on == enabled) { enabled = on; return; }
        enabled = on;
        for (Animation a : tracked) {
            if (on) a.play();
            else a.pause();
        }
    }

    private static void track(Animation a) {
        if (a != null) {
            tracked.add(a);
            if (!enabled) a.pause();
        }
    }

    /** Gentle lift on hover, squish on press. */
    public static void buttonHover(Button b, double scale) {
        b.setOnMouseEntered(e -> animateScale(b, scale));
        b.setOnMouseExited(e -> animateScale(b, 1.0));
        b.setOnMousePressed(e -> animateScale(b, 0.96));
        b.setOnMouseReleased(e -> animateScale(b, scale));
    }

    /** Hover lift + a one-shot diagonal sheen sweep across the button (premium feel). */
    public static void buttonHoverSheen(Button b, double scale) {
        StackPane wrap = (StackPane) b.getParent();
        if (!enabled) return;   // low-FX: plain button, no sheen
        buttonHover(b, scale);
        b.setOnMouseEntered(e -> {
            animateScale(b, scale);
            if (wrap != null && !b.isDisabled()) {
                Pane band = shineBand();
                band.setOpacity(0.5);
                wrap.getChildren().add(band);
                Timeline tl = new Timeline(
                        new KeyFrame(Duration.ZERO, new KeyValue(band.translateXProperty(), -260)),
                        new KeyFrame(Duration.millis(600),
                                new KeyValue(band.translateXProperty(), wrap.getWidth() + 260, Interpolator.EASE_OUT)),
                        new KeyFrame(Duration.millis(700), e2 -> wrap.getChildren().remove(band)));
                tl.play();
            }
        });
    }

    public static void buttonHover(Control c, double scale) {
        c.setOnMouseEntered(e -> animateScale(c, scale));
        c.setOnMouseExited(e -> animateScale(c, 1.0));
        c.setOnMousePressed(e -> animateScale(c, 0.96));
        c.setOnMouseReleased(e -> animateScale(c, scale));
    }

    private static void animateScale(Node n, double to) {
        if (!enabled) return;
        ScaleTransition st = new ScaleTransition(Duration.millis(150), n);
        st.setToX(to);
        st.setToY(to);
        st.setInterpolator(Interpolator.EASE_BOTH);
        st.play();
    }

    /**
     * Wraps a node in a StackPane with a diagonal glass shine that sweeps
     * across it periodically (liquid-glass reflection).
     */
    public static StackPane shine(Node content, Duration every) {
        StackPane wrap = new StackPane(content);
        Pane band = shineBand();
        wrap.getChildren().add(band);
        sweep(band, wrap, every);
        return wrap;
    }

    /** Adds a periodic diagonal shine band to any container (hero, cards…). */
    public static void attachShine(Pane container, Duration every) {
        Pane band = shineBand();
        container.getChildren().add(band);
        sweep(band, container, every);
    }

    private static Pane shineBand() {
        Pane band = new Pane();
        band.setMouseTransparent(true);
        band.getStyleClass().add("glass-shine");
        band.setPrefWidth(150);
        band.setPrefHeight(400);
        band.setRotate(20);
        band.setOpacity(0);
        return band;
    }

    /** Loops a shine sweep across the container, re-reading its width each cycle. */
    public static void sweep(Pane band, Pane container, Duration every) {
        runSweep(band, container, every);
    }

    private static void runSweep(Pane band, Pane container, Duration every) {
        Timeline inner = new Timeline(
                new KeyFrame(Duration.ZERO, e -> {
                    band.setOpacity(0.45);
                    band.setTranslateX(-250);
                }),
                new KeyFrame(Duration.millis(950),
                        new KeyValue(band.translateXProperty(), container.getWidth() + 250, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(1200), e -> band.setOpacity(0)));
        inner.setOnFinished(e -> {
            PauseTransition p = new PauseTransition(every);
            p.setOnFinished(e2 -> runSweep(band, container, every));
            p.play();
        });
        inner.play();
    }

    /** Fade + slide-in when a page becomes visible. */
    public static void enterPage(Node page) {
        if (!enabled) return;   // low-FX: pages just appear instantly
        page.setOpacity(0);
        page.setTranslateY(12);
        FadeTransition f = new FadeTransition(Duration.millis(240), page);
        f.setToValue(1);
        f.setInterpolator(Interpolator.EASE_BOTH);
        TranslateTransition t = new TranslateTransition(Duration.millis(240), page);
        t.setToY(0);
        t.setInterpolator(Interpolator.EASE_BOTH);
        f.play();
        t.play();
    }

    /**
     * Material-style ripple: wraps the control in a StackPane and spawns an
     * expanding, fading circle from the press point.
     */
    public static StackPane rippleWrap(Control c) {
        StackPane wrap = new StackPane(c);
        if (!enabled) return wrap;   // low-FX: no ripple listeners at all
        c.setMaxWidth(Double.MAX_VALUE);
        c.setMaxHeight(Double.MAX_VALUE);
        c.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> {
            if (!c.isDisabled()) spawnRipple(wrap, e.getX(), e.getY());
        });
        return wrap;
    }

    private static void spawnRipple(StackPane wrap, double x, double y) {
        double size = Math.max(wrap.getWidth(), wrap.getHeight()) * 2.2;
        javafx.scene.shape.Circle ripple = new javafx.scene.shape.Circle(x, y, 4,
                new javafx.scene.paint.Color(1, 1, 1, 0.25));
        ripple.setMouseTransparent(true);
        wrap.getChildren().add(ripple);
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(ripple.radiusProperty(), 4, Interpolator.EASE_OUT),
                        new KeyValue(ripple.opacityProperty(), 1.0)),
                new KeyFrame(Duration.millis(420),
                        new KeyValue(ripple.radiusProperty(), size, Interpolator.EASE_OUT),
                        new KeyValue(ripple.opacityProperty(), 0.0)));
        tl.setOnFinished(e -> wrap.getChildren().remove(ripple));
        tl.play();
    }

    /** Infinite soft pulsing glow (animated DropShadow). */
    public static DropShadow breathingGlow(Node n, Color color, double baseRadius, double range, Duration period) {
        DropShadow glow = new DropShadow(baseRadius, color);
        n.setEffect(glow);
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(glow.radiusProperty(), baseRadius, Interpolator.EASE_BOTH)),
                new KeyFrame(period, new KeyValue(glow.radiusProperty(), baseRadius + range, Interpolator.EASE_BOTH)),
                new KeyFrame(period.multiply(2), new KeyValue(glow.radiusProperty(), baseRadius, Interpolator.EASE_BOTH)));
        tl.setCycleCount(Animation.INDEFINITE);
        track(tl);
        tl.play();
        return glow;
    }

    /** Clips a pane to its own bounds (for shine bands inside rounded corners). */
    public static void clipToBounds(Region region) {
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(region.widthProperty());
        clip.heightProperty().bind(region.heightProperty());
        region.setClip(clip);
    }

    /**
     * Continuous diagonal shine sweep across a wrapped control (the premium
     * liquid-glass effect). Loops forever at the given interval. The wrap must
     * be a StackPane (as from {@link #rippleWrap} or {@link #shine}).
     */
    public static void loopShine(StackPane wrap, Duration every) {
        if (!enabled || wrap == null) return;
        Pane band = shineBand();
        band.setOpacity(0.35);
        band.setMouseTransparent(true);
        wrap.getChildren().add(band);
        sweep(band, wrap, every);
    }

    /** Adds a single static glow band behind content (cheap depth cue). */
    public static void staticGlow(Pane container) {
        Pane band = new Pane();
        band.setMouseTransparent(true);
        band.getStyleClass().add("bg-glow");
        band.prefWidthProperty().bind(container.widthProperty());
        band.prefHeightProperty().bind(container.heightProperty());
        container.getChildren().add(0, band);
    }

    // ------------------------------------------------------------- aurora bg

    /**
     * Minimal animated background: three huge soft aurora orbs that drift
     * very slowly (pink / violet / cyan). Cheap: pure CSS radial gradients on
     * transparent panes, translated at a low frequency — no per-frame
     * repaint of effects, respects low-FX mode.
     */
    public static void auroraBackground(Pane container) {
        if (!enabled) return;
        Pane layer = new Pane();
        layer.setMouseTransparent(true);
        layer.prefWidthProperty().bind(container.widthProperty());
        layer.prefHeightProperty().bind(container.heightProperty());
        layer.setPickOnBounds(false);
        List<double[]> orbs = new ArrayList<>();
        orbs.add(new double[]{0.06, 0.02, 520, 0.9, 90, 60, 42, 1});
        orbs.add(new double[]{0.66, 0.18, 640, 0.8, -110, 80, 55, 2});
        orbs.add(new double[]{0.30, 0.62, 560, 0.7, 70, -90, 48, 3});
        for (double[] o : orbs) {
            Pane orb = new Pane();
            orb.setMouseTransparent(true);
            orb.setPickOnBounds(false);
            orb.getStyleClass().add("aurora-orb" + (o[7] == 2 ? "-violet" : o[7] == 3 ? "-cyan" : ""));
            orb.setOpacity(o[3]);
            orb.setPrefWidth(o[2]);
            orb.setPrefHeight(o[2]);
            orb.layoutXProperty().bind(layer.widthProperty().multiply(o[0]).subtract(o[2] / 2));
            orb.layoutYProperty().bind(layer.heightProperty().multiply(o[1]).subtract(o[2] / 2));
            layer.getChildren().add(orb);
            drift(orb, o[4], o[5], o[6]);
        }
        container.getChildren().add(0, layer);
    }

    private static void drift(Node n, double dx, double dy, double seconds) {
        TranslateTransition t = new TranslateTransition(Duration.seconds(seconds), n);
        t.setByX(dx);
        t.setByY(dy);
        t.setAutoReverse(true);
        t.setCycleCount(Animation.INDEFINITE);
        t.setInterpolator(Interpolator.EASE_BOTH);
        track(t);
        t.play();
    }

    /** Gentle infinite glow pulse on a control (e.g. the Play button). */
    public static void pulseGlow(Node n, Color color) {
        if (!enabled) return;
        DropShadow glow = new DropShadow(18, color);
        n.setEffect(glow);
        Timeline tl = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(glow.radiusProperty(), 14, Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(1600),
                        new KeyValue(glow.radiusProperty(), 26, Interpolator.EASE_BOTH),
                        new KeyValue(glow.colorProperty(), color.deriveColor(0, 1.2, 1.3, 1), Interpolator.EASE_BOTH)),
                new KeyFrame(Duration.millis(3200), new KeyValue(glow.radiusProperty(), 14, Interpolator.EASE_BOTH)));
        tl.setCycleCount(Animation.INDEFINITE);
        track(tl);
        tl.play();
    }

    /** Infinite opacity fade pulse (status dots etc.), low-FX aware. */
    public static void pulseOpacity(Node n, double min, double max, Duration period) {
        if (!enabled) return;
        FadeTransition f = new FadeTransition(period, n);
        f.setFromValue(max);
        f.setToValue(min);
        f.setAutoReverse(true);
        f.setCycleCount(Animation.INDEFINITE);
        track(f);
        f.play();
    }
}
