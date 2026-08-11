package com.prex.launcher.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Tiny pub/sub bus that forwards log lines to the console view. */
public final class ConsoleBus {

    private static final List<Consumer<String>> SUBSCRIBERS = new CopyOnWriteArrayList<>();

    private ConsoleBus() {}

    public static void subscribe(Consumer<String> sink) { SUBSCRIBERS.add(sink); }
    public static void unsubscribe(Consumer<String> sink) { SUBSCRIBERS.remove(sink); }

    public static void post(String line) {
        for (Consumer<String> sink : SUBSCRIBERS) {
            try { sink.accept(line); } catch (Exception ignored) {}
        }
    }
}
