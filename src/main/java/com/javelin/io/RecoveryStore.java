package com.javelin.io;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Crash recovery: keeps a copy of every unsaved buffer on the user's own disk so a crash, power cut
 * or kill does not lose their work. JavaFX-free, and local only: nothing here touches the network.
 *
 * <p>A snapshot exists only while its buffer has unsaved changes. Saving or deliberately discarding
 * a buffer deletes its snapshot, so any snapshot found at the next start belongs to a session that
 * did not end cleanly. The directory is created owner-only where the file system supports it,
 * because snapshots hold text the user has not chosen to save.
 *
 * <p>Several editor instances can run at once (one per project, see {@code
 * packaging/macos/Javelin}) and share this directory. An instance calls {@link #startSession()} to
 * lock a small file for as long as it lives; the snapshots it writes carry its session in their id.
 * Other instances leave those snapshots alone while the lock is held, so a second window never
 * offers (or deletes) a first window's live unsaved work. The operating system drops the lock when
 * the process ends, however it ends, which turns the snapshots into ordinary leftovers to offer at
 * the next start.
 */
public final class RecoveryStore {
  /**
   * One recovered buffer. {@code original} is null for a document that was never saved. {@code
   * caret} is the cursor offset into {@code text} (always within it; 0 when unknown) and {@code
   * tabIndex} the buffer's position among the editor tabs when it was written (-1 when unknown,
   * e.g. a snapshot written by an older version).
   */
  public record Snapshot(
      String id, Path original, Instant savedAt, String text, int caret, int tabIndex) {
    public Snapshot {
      caret = Math.max(0, Math.min(caret, text.length()));
      if (caret > 0 && caret < text.length() && Character.isLowSurrogate(text.charAt(caret)))
        caret--; // never inside an emoji
      tabIndex = Math.max(-1, tabIndex);
    }

    /** A snapshot with no known cursor position or tab position. */
    public Snapshot(String id, Path original, Instant savedAt, String text) {
      this(id, original, savedAt, text, 0, -1);
    }
  }

  /**
   * The order tabs were in when the snapshots were written; snapshots without a known position come
   * last, oldest first.
   */
  public static final Comparator<Snapshot> TAB_ORDER =
      Comparator.comparingInt((Snapshot s) -> s.tabIndex() < 0 ? Integer.MAX_VALUE : s.tabIndex())
          .thenComparing(Snapshot::savedAt)
          .thenComparing(Snapshot::id);

  /**
   * What was last written for an id, so unchanged buffers (text, cursor and tab position) are not
   * rewritten.
   */
  private record Written(String text, int caret, int tabIndex) {}

  private static final String EXTENSION = ".recovery";
  private static final String MAGIC = "javelin-ide-recovery 1";
  private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

  /**
   * Ids written inside a session start with {@code s}, twelve hex digits and a dash; older ids are
   * plain UUIDs.
   */
  private static final Pattern SESSION_ID = Pattern.compile("s([0-9a-f]{12})-.+");

  private static final String LOCK_PREFIX = ".session-", LOCK_SUFFIX = ".lock";

  private final Path directory;
  private final Map<String, Written> lastSaved = new HashMap<>();
  private String sessionToken; // null until startSession()
  private FileChannel sessionChannel;
  private FileLock sessionLock;

  /** Lists a directory; injectable so a folder that cannot be read can be simulated in tests. */
  interface Lister {
    Stream<Path> list(Path directory) throws IOException;
  }

  private final boolean posix;
  private final Lister lister;

  public RecoveryStore(Path directory) {
    this(
        directory,
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        Files::list);
  }

  RecoveryStore(Path directory, boolean posix, Lister lister) {
    this.directory = directory.toAbsolutePath().normalize();
    this.posix = posix;
    this.lister = lister;
  }

  public Path directory() {
    return directory;
  }

  public static String newId() {
    return UUID.randomUUID().toString();
  }

  /**
   * Claims this store for the running process: locks a file in the directory until {@link
   * #endSession()} or until the process ends. Ids from {@link #newSessionId()} are then protected
   * from every other instance sharing the directory. Calling it again while a session is active
   * does nothing.
   */
  public synchronized void startSession() throws IOException {
    if (sessionLock != null) return;
    ensureDirectory();
    String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    FileChannel channel =
        FileChannel.open(lockFor(token), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    FileLock lock = null;
    try {
      lock = channel.tryLock();
      if (lock == null) throw new IOException("Could not lock " + lockFor(token));
    } finally {
      if (lock == null) {
        channel.close();
        deleteQuietly(lockFor(token));
      }
    }
    sessionToken = token;
    sessionChannel = channel;
    sessionLock = lock;
  }

  /**
   * Ends the session: its snapshots (if any are left) become leftovers that any instance may offer.
   * Safe to call twice.
   */
  public synchronized void endSession() {
    if (sessionLock == null) return;
    try {
      sessionLock.release();
    } catch (IOException ignored) {
      /* closing the channel releases it anyway */
    }
    try {
      sessionChannel.close();
    } catch (IOException ignored) {
      /* nothing more to do */
    }
    deleteQuietly(lockFor(sessionToken));
    sessionToken = null;
    sessionChannel = null;
    sessionLock = null;
  }

  /**
   * A fresh id for a buffer of this instance; without {@link #startSession()} it is a plain {@link
   * #newId()}.
   */
  public synchronized String newSessionId() {
    return sessionToken == null ? newId() : "s" + sessionToken + "-" + UUID.randomUUID();
  }

  /**
   * Writes the buffer's current text unless it is identical to what was last written for this id.
   * Returns whether it wrote.
   */
  public boolean saveIfChanged(String id, Path original, String text) throws IOException {
    return saveIfChanged(id, original, text, 0, -1);
  }

  /**
   * Like {@link #saveIfChanged(String, Path, String)}, and also remembers the cursor offset and the
   * buffer's tab position so a restore can put the cursor and the tabs back where they were. Moving
   * the cursor or the tab counts as a change. Out-of-range values are clamped when read back, never
   * rejected: the text matters most.
   */
  public synchronized boolean saveIfChanged(
      String id, Path original, String text, int caret, int tabIndex) throws IOException {
    checkId(id);
    Written now = new Written(text, caret, tabIndex);
    if (now.equals(lastSaved.get(id)) && Files.exists(fileFor(id))) return false;
    ensureDirectory();
    byte[] body = text.getBytes(StandardCharsets.UTF_8);
    String header =
        MAGIC
            + "\noriginal="
            + (original == null ? "" : original.toAbsolutePath().toUri())
            + "\nsaved="
            + Instant.now().toEpochMilli()
            + "\ncaret="
            + caret
            + "\ntab="
            + tabIndex
            + "\nbytes="
            + body.length
            + "\n\n";
    FileIO.writeAtomic(fileFor(id), header + text);
    lastSaved.put(id, now);
    return true;
  }

  /**
   * Removes the snapshot for a buffer that was saved or deliberately discarded. Unknown ids are
   * ignored.
   */
  public synchronized void discard(String id) {
    checkId(id);
    lastSaved.remove(id);
    deleteQuietly(fileFor(id));
  }

  /**
   * A snapshot that cannot be deleted is simply offered again next time: never lost, never fatal.
   */
  private static boolean deleteQuietly(Path file) {
    try {
      return Files.deleteIfExists(file);
    } catch (IOException ignored) {
      return false;
    }
  }

  /** Removes every snapshot, e.g. after a clean exit in which the user decided what to keep. */
  public synchronized void clear() {
    lastSaved.clear();
    for (Path file : snapshotFiles()) deleteQuietly(file);
  }

  /**
   * Snapshots left by earlier sessions, oldest first. Damaged or unrecognized files are skipped,
   * never deleted.
   */
  public synchronized List<Snapshot> pending() {
    List<Snapshot> found = new ArrayList<>();
    for (Path file : snapshotFiles()) {
      Snapshot snapshot = read(file);
      if (snapshot != null) found.add(snapshot);
    }
    found.sort(Comparator.comparing(Snapshot::savedAt).thenComparing(Snapshot::id));
    return found;
  }

  /**
   * Deletes snapshots (including damaged ones) last written longer ago than {@code age}; returns
   * how many were removed.
   */
  public synchronized int purgeOlderThan(Duration age) {
    Instant cutoff = Instant.now().minus(age);
    int removed = 0;
    for (Path file : snapshotFiles()) {
      try {
        if (Files.getLastModifiedTime(file).toInstant().isBefore(cutoff) && deleteQuietly(file))
          removed++;
      } catch (IOException ignored) {
        /* vanished meanwhile */
      }
    }
    sweepEndedSessionLocks(cutoff);
    return removed;
  }

  /**
   * Removes lock files of sessions that ended (nobody holds them) and are older than {@code
   * cutoff}; a new session's file is never touched.
   */
  private void sweepEndedSessionLocks(Instant cutoff) {
    if (!Files.isDirectory(directory)) return;
    try (Stream<Path> files = lister.list(directory)) {
      for (Path file :
          files
              .filter(
                  p ->
                      p.getFileName().toString().startsWith(LOCK_PREFIX)
                          && p.getFileName().toString().endsWith(LOCK_SUFFIX))
              .toList()) {
        String name = file.getFileName().toString();
        String token = name.substring(LOCK_PREFIX.length(), name.length() - LOCK_SUFFIX.length());
        try {
          if (!isLive(token) && Files.getLastModifiedTime(file).toInstant().isBefore(cutoff))
            deleteQuietly(file);
        } catch (IOException ignored) {
          /* vanished meanwhile */
        }
      }
    } catch (IOException ignored) {
      /* cannot list: try again next start */
    }
  }

  private List<Path> snapshotFiles() {
    if (!Files.isDirectory(directory)) return List.of();
    try (Stream<Path> files = lister.list(directory)) {
      return files
          .filter(p -> p.getFileName().toString().endsWith(EXTENSION) && Files.isRegularFile(p))
          .filter(p -> !belongsToLiveSession(p))
          .sorted()
          .toList();
    } catch (IOException ex) {
      return List.of();
    }
  }

  /**
   * True for a snapshot written by an instance that is still running: it is that instance's, not a
   * leftover.
   */
  private boolean belongsToLiveSession(Path snapshot) {
    String name = snapshot.getFileName().toString();
    var match = SESSION_ID.matcher(name.substring(0, name.length() - EXTENSION.length()));
    return match.matches() && isLive(match.group(1));
  }

  /**
   * Whether some process (this one included) holds the session's lock. When in doubt the answer is
   * yes: someone else's unsaved work is never offered to a second window and never deleted by it.
   */
  private boolean isLive(String token) {
    if (token.equals(sessionToken))
      return true; // never open a second channel on our own lock: closing it could release the lock
    Path lockFile = lockFor(token);
    if (!Files.exists(lockFile)) return false;
    try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
      FileLock lock = channel.tryLock();
      if (lock == null) return true;
      lock.release();
      return false;
    } catch (OverlappingFileLockException held) {
      return true; // held by another store inside this JVM
    } catch (NoSuchFileException gone) {
      return false; // the session ended while we looked
    } catch (IOException unsure) {
      return true;
    }
  }

  private Path lockFor(String token) {
    return directory.resolve(LOCK_PREFIX + token + LOCK_SUFFIX);
  }

  private static Snapshot read(Path file) {
    try {
      String raw = FileIO.readText(file);
      int blank = raw.indexOf("\n\n");
      if (blank < 0) return null;
      String[] header = raw.substring(0, blank).split("\n");
      // magic, original, saved, then optional caret and tab (absent in snapshots from older
      // versions), then bytes
      if (header.length < 4 || header.length > 6 || !header[0].equals(MAGIC)) return null;
      String text = raw.substring(blank + 2);
      int last = header.length - 1;
      long expected = Long.parseLong(value(header[last], "bytes="));
      if (text.getBytes(StandardCharsets.UTF_8).length != expected)
        return null; // truncated or edited by hand
      String originalUri = value(header[1], "original=");
      Path original = originalUri.isEmpty() ? null : Path.of(URI.create(originalUri));
      Instant saved = Instant.ofEpochMilli(Long.parseLong(value(header[2], "saved=")));
      int caret = 0, tabIndex = -1, next = 3;
      if (next < last && header[next].startsWith("caret="))
        caret = intOr(value(header[next++], "caret="), 0);
      if (next < last && header[next].startsWith("tab="))
        tabIndex = intOr(value(header[next++], "tab="), -1);
      if (next != last) return null; // an unknown line where the byte count should be
      String name = file.getFileName().toString();
      return new Snapshot(
          name.substring(0, name.length() - EXTENSION.length()),
          original,
          saved,
          text,
          caret,
          tabIndex);
    } catch (IOException | RuntimeException ex) {
      return null;
    }
  }

  /**
   * A damaged cursor or tab position must never cost the user their text, so it falls back instead
   * of failing.
   */
  private static int intOr(String number, int fallback) {
    try {
      return Integer.parseInt(number.trim());
    } catch (NumberFormatException ex) {
      return fallback;
    }
  }

  private static String value(String line, String prefix) {
    if (!line.startsWith(prefix)) throw new IllegalArgumentException(line);
    return line.substring(prefix.length());
  }

  private void ensureDirectory() throws IOException {
    if (Files.isDirectory(directory)) return;
    if (posix)
      Files.createDirectories(
          directory,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    else Files.createDirectories(directory);
  }

  private Path fileFor(String id) {
    return directory.resolve(id + EXTENSION);
  }

  private static void checkId(String id) {
    if (id == null || !ID.matcher(id).matches())
      throw new IllegalArgumentException("Invalid recovery id: " + id);
  }
}
