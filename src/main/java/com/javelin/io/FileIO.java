package com.javelin.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;

/**
 * Reading and writing the user's text files without ever putting their data at risk. JavaFX-free.
 *
 * <p>{@link #writeAtomic} never leaves a half-written file behind: a crash, a full disk or a power
 * cut during a save leaves either the old content or the new content, not a truncated mix. {@link
 * #readText} refuses files that are too large to edit comfortably and files that are not UTF-8
 * text, with messages a person can act on.
 */
public final class FileIO {
  /** Above this size the editor stays responsive by switching syntax highlighting off. */
  public static final long LARGE_FILE_BYTES = 1L << 20;

  /** Above this size a file is not loaded into the editor at all. */
  public static final long MAX_FILE_BYTES = 32L << 20;

  private FileIO() {}

  /** The file is bigger than the editor will load. */
  public static final class FileTooLargeException extends IOException {
    private static final long serialVersionUID = 1L;
    private final long size, limit;

    public FileTooLargeException(Path file, long size, long limit) {
      super(
          file.getFileName()
              + " is "
              + describe(size)
              + ", which is more than the "
              + describe(limit)
              + " the editor opens. Use a pager or a command-line tool for files this large.");
      this.size = size;
      this.limit = limit;
    }

    public long size() {
      return size;
    }

    public long limit() {
      return limit;
    }
  }

  public static String readText(Path file) throws IOException {
    return readText(file, MAX_FILE_BYTES);
  }

  public static String readText(Path file, long maxBytes) throws IOException {
    long size = Files.size(file);
    if (size > maxBytes) throw new FileTooLargeException(file, size, maxBytes);
    byte[] bytes = Files.readAllBytes(file);
    if (bytes.length > maxBytes)
      throw new FileTooLargeException(
          file, bytes.length, maxBytes); // it grew while we were reading
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException ex) {
      throw new IOException(
          file.getFileName()
              + " is not valid UTF-8 text. It may be a binary file or use another encoding.",
          ex);
    }
  }

  /**
   * Replaces the file's content in one step: the text is written and flushed to a temporary file
   * next to it, which then atomically takes its place. Symbolic links are followed (the link itself
   * is kept) and an existing file's permissions are preserved. A file that does not exist yet has
   * nothing to lose, so it is simply created.
   */
  public static void writeAtomic(Path file, String text) throws IOException {
    writeAtomic(file, text, Files::move);
  }

  /**
   * Moves a file; injectable so the fallback for file systems without atomic moves can be tested.
   */
  interface Mover {
    void move(Path from, Path to, java.nio.file.CopyOption... options) throws IOException;
  }

  static void writeAtomic(Path file, String text, Mover mover) throws IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    if (!Files.exists(file)) {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) Files.createDirectories(parent);
      Files.write(file, bytes);
      return;
    }
    Path target = file.toRealPath();
    Path temp = Files.createTempFile(target.getParent(), "." + target.getFileName() + ".", ".tmp");
    try {
      try (FileChannel channel =
          FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
      }
      copyPermissions(target, temp);
      try {
        mover.move(
            temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ex) {
        mover.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private static void copyPermissions(Path from, Path to) throws IOException {
    PosixFileAttributeView view =
        Files.getFileAttributeView(from, PosixFileAttributeView.class); // null on e.g. Windows
    if (view != null) Files.setPosixFilePermissions(to, view.readAttributes().permissions());
  }

  /** A size for people: {@code 340 B}, {@code 12.5 KB}, {@code 3.0 MB}. */
  public static String describe(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
    return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
  }
}
