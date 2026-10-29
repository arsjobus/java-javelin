package com.javelin_ide.launch;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Knows which project folders are open in a running Javelin IDE window, so that opening the same folder a second time can
 * be noticed. Every window is its own process (see {@code packaging/macos/Javelin}), and Javelin IDE never opens a
 * network socket (see {@code OfflineGuardTest}), so the answer lives in the file system: a window that opens a project
 * locks a small file in this folder, named after the project's real path, for as long as it keeps the project open.
 * The operating system drops the lock when the process ends, however it ends, so a crash never leaves a project
 * "open"; stale files are harmless and {@link #prune(Duration)} removes old ones.
 *
 * <p>Two copies of the editor on one folder do not corrupt anything, but they race on the same files, so the window
 * asks before it does that. The check is advisory and covers Javelin IDE windows only (not a terminal or another IDE).
 */
public final class ProjectInstances {
    /** Name of the folder, inside the config directory, that holds the lock files. */
    public static final String DIRECTORY_NAME = "instances";
    private static final String SUFFIX = ".lock";

    /**
     * Lock files this process holds. On some systems closing any channel on a file releases every lock the process holds
     * on it, so a second channel is never opened on a file this process has locked: it is answered from here.
     */
    private static final Set<Path> HELD = ConcurrentHashMap.newKeySet();

    private final Path directory;

    public ProjectInstances(Path directory) { this.directory = directory; }

    /** A project this window has open. Closing it (or ending the process) lets the next window claim the folder. */
    public static final class Claim implements AutoCloseable {
        private final Path project, file;
        private final FileChannel channel;
        private final FileLock lock;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Claim(Path project, Path file, FileChannel channel, FileLock lock) {
            this.project = project; this.file = file; this.channel = channel; this.lock = lock;
        }

        /** The project's real path, which is what identifies it however it was spelled when opened. */
        public Path project() { return project; }

        /** Gives the project up. Safe to call twice. */
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try { lock.release(); } catch (IOException ignored) { /* closing the channel releases it anyway */ }
            try { channel.close(); } catch (IOException ignored) { /* nothing more to do */ }
            HELD.remove(file);
        }
    }

    /**
     * Claims {@code project} for the calling window. Empty means another window (or another claim in this process) already
     * has it. A folder reached through a symbolic link or a differently spelled path is the same project.
     */
    public Optional<Claim> claim(Path project) throws IOException {
        Path real = realPath(project);
        Path file = directory.resolve(UUID.nameUUIDFromBytes(real.toString().getBytes(StandardCharsets.UTF_8)) + SUFFIX);
        if (!HELD.add(file)) return Optional.empty();
        boolean claimed = false;
        FileChannel channel = null;
        try {
            Files.createDirectories(directory);
            channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) return Optional.empty();
            channel.truncate(0);
            channel.write(ByteBuffer.wrap((real + "\n").getBytes(StandardCharsets.UTF_8)), 0); // only so a person looking can tell which project it is
            claimed = true;
            return Optional.of(new Claim(real, file, channel, lock));
        } finally {
            if (!claimed) { HELD.remove(file); if (channel != null) channel.close(); }
        }
    }

    /**
     * Deletes lock files nobody holds that were last claimed before {@code maxAge} ago, and returns how many went. A file
     * that cannot be examined is left alone. (A claim racing with the deletion of a file that old is the one case this
     * cannot rule out; it costs a missed warning, never data.)
     */
    public int prune(Duration maxAge) {
        File[] files = directory.toFile().listFiles((dir, name) -> name.endsWith(SUFFIX)); // null: no such folder, or it cannot be read
        if (files == null) return 0;
        Instant cutoff = Instant.now().minus(maxAge);
        int removed = 0;
        for (File file : files) if (pruneIfUnused(file.toPath(), cutoff)) removed++;
        return removed;
    }

    private static boolean pruneIfUnused(Path file, Instant cutoff) {
        try {
            if (HELD.contains(file) || Files.getLastModifiedTime(file).toInstant().isAfter(cutoff)) return false;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                FileLock lock = channel.tryLock();
                if (lock == null) return false; // another window holds it
                lock.release();
            }
            return Files.deleteIfExists(file);
        } catch (IOException unsure) {
            return false;
        }
    }

    /**
     * The one spelling every window agrees on. When the folder (or a part of the spelling such as {@code other/..})
     * does not exist, {@code toRealPath} fails; the nearest existing parent is then resolved, so a symbolic link
     * above it still counts (macOS keeps temp folders under {@code /var}, a link to {@code /private/var}).
     */
    static Path realPath(Path project) {
        try { return project.toRealPath(); } catch (IOException missing) { /* resolve what exists below */ }
        Path absolute = project.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) return absolute;
        try { return existing.toRealPath().resolve(existing.relativize(absolute)).normalize(); }
        catch (IOException unreadable) { return absolute; }
    }
}
