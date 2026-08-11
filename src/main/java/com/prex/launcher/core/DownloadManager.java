package com.prex.launcher.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fast parallel downloader with SHA-1 verification.
 *
 * Files that already exist with the right size and hash are skipped instantly,
 * so reinstalling or switching versions is nearly free. Each file downloads to
 * a ".part" temp file and is moved into place atomically, and every transfer is
 * verified against the SHA-1 from Mojang's manifests before it is accepted.
 */
public final class DownloadManager {

    public record Task(String url, Path dest, String sha1, long size, String label) {}

    public interface Listener {
        void onProgress(long doneBytes, long totalBytes, String currentFile);
        default void onFinished(int ok, int failed) {}
    }

    private final int threads;
    private final List<Task> failed = new CopyOnWriteArrayList<>();
    private volatile boolean cancelled;

    public DownloadManager(int threads) {
        this.threads = Math.max(1, threads);
    }

    public void cancel() { cancelled = true; }
    public boolean isCancelled() { return cancelled; }

    /** Downloads all tasks in parallel. Returns the tasks that could not be completed. */
    public List<Task> run(List<Task> tasks, Listener listener) {
        failed.clear();
        long total = tasks.stream().mapToLong(Task::size).sum();
        AtomicLong done = new AtomicLong();
        AtomicInteger okCount = new AtomicInteger();
        AtomicInteger failCount = new AtomicInteger();
        AtomicReference<String> current = new AtomicReference<>("");

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(threads, tasks.size())),
                r -> { Thread t = new Thread(r, "download-worker"); t.setDaemon(true); return t; });
        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor(
                r -> { Thread t = new Thread(r, "progress-reporter"); t.setDaemon(true); return t; });
        reporter.scheduleAtFixedRate(
                () -> listener.onProgress(done.get(), total, current.get()), 0, 200, TimeUnit.MILLISECONDS);

        CountDownLatch latch = new CountDownLatch(tasks.size());
        for (Task t : tasks) {
            pool.submit(() -> {
                try {
                    if (cancelled || fileAlreadyGood(t)) {
                        if (!cancelled) okCount.incrementAndGet();
                        else failCount.incrementAndGet();
                        done.addAndGet(t.size());
                        return;
                    }
                    current.set(t.label());
                    if (downloadWithRetry(t, done)) okCount.incrementAndGet();
                    else { failCount.incrementAndGet(); failed.add(t); }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    failed.add(t);
                } finally {
                    latch.countDown();
                }
            });
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();
        reporter.shutdownNow();
        listener.onProgress(done.get(), total, current.get());
        listener.onFinished(okCount.get(), failCount.get());
        return failed;
    }

    private boolean downloadWithRetry(Task t, AtomicLong done) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                AtomicLong bytes = new AtomicLong();
                boolean ok = Http.download(t.url(), t.dest(), t.sha1(), bytes::addAndGet, () -> cancelled);
                done.addAndGet(bytes.get());
                return ok;
            } catch (IOException e) {
                if (cancelled || "cancelled".equals(e.getMessage())) return false;
                try { Thread.sleep(400L * attempt); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private boolean fileAlreadyGood(Task t) {
        try {
            if (!Files.isRegularFile(t.dest())) return false;
            if (t.size() > 0 && Files.size(t.dest()) != t.size()) return false;
            if (t.sha1() != null && !t.sha1().isBlank()
                    && !t.sha1().equalsIgnoreCase(Http.sha1Hex(t.dest()))) return false;
            // no size/hash info at all: refuse empty files (failed downloads)
            if (t.size() <= 0 && (t.sha1() == null || t.sha1().isBlank()) && Files.size(t.dest()) == 0) return false;
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
