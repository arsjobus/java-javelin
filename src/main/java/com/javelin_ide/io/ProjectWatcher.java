package com.javelin_ide.io;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Notices when files or folders are added, removed, renamed or moved inside the project folder by something other
 * than the editor (a terminal, a build, git, a file manager), so the project tree can refresh itself. JavaFX-free, so
 * it is tested without a display.
 *
 * <p>It compares snapshots of the folder structure on a timer instead of using {@code WatchService}: that behaves the
 * same on Windows, macOS and Linux, needs no per-folder handles that can run out on large projects, and cannot miss an
 * event. Names and basic file metadata are compared, never file contents. Folders named in the exclude list (e.g. {@code .git}, {@code target})
 * are skipped, exactly as the tree skips them. Symbolic links are listed but not followed.
 */
public final class ProjectWatcher implements AutoCloseable {
    private final Supplier<Set<String>> excludes;
    private final Runnable onChange;
    private Path root;
    private Map<String, Fingerprint> known = Map.of();
    private ScheduledExecutorService timer;

    /**
     * @param excludes names of files and folders to ignore; asked again on every check so a settings change applies at once
     * @param onChange called (from the timer thread, never while a lock is held) after a check found a difference
     */
    public ProjectWatcher(Supplier<Set<String>> excludes, Runnable onChange) {
        this.excludes = excludes;
        this.onChange = onChange;
    }

    /** Starts watching {@code projectRoot} (or stops watching when it is {@code null}); what is there now is the baseline. */
    public synchronized void watch(Path projectRoot) {
        root = projectRoot;
        known = snapshot(projectRoot, excludes.get());
    }

    /** Takes what is on disk now as the baseline, e.g. after the editor itself changed the folder and rebuilt the tree. */
    public synchronized void acknowledge() {
        known = snapshot(root, excludes.get());
    }

    /** Compares the folder with the baseline; if it differs, adopts it as the new baseline and calls {@code onChange}. */
    public boolean poll() {
        synchronized (this) {
            Map<String, Fingerprint> now = snapshot(root, excludes.get());
            if (now.equals(known)) return false;
            known = now;
        }
        onChange.run();
        return true;
    }

    /** Checks every {@code intervalMillis} on a daemon thread until {@link #close()}. Calling it again restarts the timer. */
    public synchronized void start(long intervalMillis) {
        close();
        timer = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "project-watcher"); t.setDaemon(true); return t; });
        timer.scheduleWithFixedDelay(() -> {
            try { poll(); } catch (RuntimeException ex) { /* a failed check must not stop the ones after it */ }
        }, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** Stops the timer. Safe to call more than once, or before {@link #start}. */
    @Override public synchronized void close() {
        if (timer != null) timer.shutdownNow();
        timer = null;
    }

    /**
     * The structure below {@code projectRoot} as sorted relative paths, folders ending in {@code /}. Empty when there is
     * no root or it is not a folder (e.g. it was deleted). A folder that cannot be read is listed but not entered, and other unreadable entries are skipped, rather than failing the check.
     */
    private record Fingerprint(boolean directory, long size, FileTime modified, Object fileKey) {}

    static Map<String, Fingerprint> snapshot(Path projectRoot, Set<String> excluded) {
        Map<String, Fingerprint> found = new HashMap<>();
        if (projectRoot == null || !Files.isDirectory(projectRoot)) return found;
        FileVisitor<Path> visitor = new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(projectRoot)) return FileVisitResult.CONTINUE;
                if (excluded.contains(dir.getFileName().toString())) return FileVisitResult.SKIP_SUBTREE;
                found.put(projectRoot.relativize(dir) + "/", fingerprint(attrs, true));
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!excluded.contains(file.getFileName().toString())) found.put(projectRoot.relativize(file).toString(), fingerprint(attrs, attrs.isDirectory()));
                return FileVisitResult.CONTINUE;
            }
            // A folder that cannot be opened is reported here instead of to preVisitDirectory, but it still exists and the tree shows it.
            @Override public FileVisitResult visitFileFailed(Path file, IOException ex) {
                if (!file.equals(projectRoot) && !excluded.contains(file.getFileName().toString()) && Files.isDirectory(file)) {
                    try { found.put(projectRoot.relativize(file) + "/", fingerprint(Files.readAttributes(file, BasicFileAttributes.class), true)); }
                    catch (IOException ignored) { /* skip an entry that disappeared during the scan */ }
                }
                return FileVisitResult.CONTINUE;
            }
        };
        try {
            Files.walkFileTree(projectRoot, visitor);
        } catch (IOException ex) {
            // the walk is best effort; whatever was seen before the failure is compared
        }
        return found;
    }

    private static Fingerprint fingerprint(BasicFileAttributes attrs, boolean directory) {
        return new Fingerprint(directory, attrs.size(), attrs.lastModifiedTime(), attrs.fileKey());
    }
}
