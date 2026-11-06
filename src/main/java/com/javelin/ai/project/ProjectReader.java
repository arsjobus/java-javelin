package com.javelin.ai.project;

import com.javelin.io.FileIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

/**
 * Reads project files and nothing else. Every path goes through {@link #resolve}, which refuses
 * anything that is not inside the project, so neither a scan result nor a reply from a model can
 * make the editor read or write outside it. Text that was read is kept while the file's size and
 * time are unchanged, so asking again does not read the whole project again. JavaFX-free.
 */
public final class ProjectReader {
  /** A path that leaves the project, or goes through a link. */
  public static final class OutsideProjectException extends IOException {
    private static final long serialVersionUID = 1L;

    OutsideProjectException(String message) {
      super(message);
    }
  }

  static final long MAX_CACHE_BYTES = 64L << 20;

  private record Cached(long size, long modified, String text) {}

  private final Path root;
  private final Map<String, Cached> cache = new HashMap<>();
  private long cachedChars;

  public ProjectReader(Path root) throws IOException {
    this.root = root.toRealPath();
  }

  public Path root() {
    return root;
  }

  /**
   * The file a project-relative path names, checked: it must be relative, free of {@code ..}, stay
   * inside the project and pass through no symbolic link.
   */
  public Path resolve(String relative) throws IOException {
    return resolve(root, relative);
  }

  public static Path resolve(Path realRoot, String relative) throws IOException {
    if (relative == null || relative.isBlank())
      throw new OutsideProjectException("An empty path does not name a file of the project.");
    if (relative.indexOf('\0') >= 0)
      throw new OutsideProjectException("The path contains a null character.");
    String path = relative.replace('\\', '/');
    if (path.startsWith("/") || path.matches("^[A-Za-z]:.*"))
      throw new OutsideProjectException(
          "'" + relative + "' is an absolute path, not a path inside the project.");
    Path current = realRoot;
    for (String part : path.split("/")) {
      if (part.isEmpty() || part.equals(".")) continue;
      if (part.equals(".."))
        throw new OutsideProjectException("'" + relative + "' leaves the project folder.");
      current = current.resolve(part);
      if (Files.isSymbolicLink(current))
        throw new OutsideProjectException("'" + relative + "' goes through a symbolic link.");
    }
    if (current.equals(realRoot))
      throw new OutsideProjectException("'" + relative + "' is the project folder, not a file.");
    if (!current.normalize().startsWith(realRoot))
      throw new OutsideProjectException("'" + relative + "' is outside the project folder.");
    return current;
  }

  /**
   * The text of a file, from the cache when its size and time are still those of {@code file}.
   *
   * @throws IOException when it is gone, larger than {@code maxBytes}, or not UTF-8 text
   */
  public synchronized String read(ProjectFile file, long maxBytes) throws IOException {
    Cached hit = cache.get(file.path());
    if (hit != null && hit.size() == file.size() && hit.modified() == file.modified())
      return hit.text();
    Path path = resolve(file.path());
    String text = FileIO.readText(path, maxBytes);
    if (cachedChars + text.length() <= MAX_CACHE_BYTES) {
      Cached old = cache.put(file.path(), new Cached(file.size(), file.modified(), text));
      cachedChars += text.length() - (old == null ? 0 : old.text().length());
    }
    return text;
  }

  /**
   * The current text of any project file by path, never cached (for checks just before applying).
   */
  public String readNow(String relative, long maxBytes) throws IOException {
    return FileIO.readText(resolve(relative), maxBytes);
  }

  /** Forgets what was read, for a project that changed a lot. */
  public synchronized void clear() {
    cache.clear();
    cachedChars = 0;
  }

  public synchronized int cachedFiles() {
    return cache.size();
  }

  /** A fingerprint of a text (SHA-256, hex), to tell whether a file is still what the model saw. */
  public static String hash(String text) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 is part of every Java runtime", ex);
    }
  }
}
