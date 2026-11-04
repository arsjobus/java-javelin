package com.javelin.io;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.javelin.io.FileIO.FileTooLargeException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileIOTest {
  @TempDir Path dir;

  private static boolean posix() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }

  @Test
  void readsUtf8IncludingBomAndEmoji() throws IOException {
    Path f = dir.resolve("a.txt");
    Files.writeString(f, "\uFEFFh\u00e9llo \uD83D\uDE00\r\nline2");
    assertEquals("\uFEFFh\u00e9llo \uD83D\uDE00\r\nline2", FileIO.readText(f));
    Files.write(f, new byte[0]);
    assertEquals("", FileIO.readText(f));
  }

  @Test
  void refusesFilesOverTheLimitWithAHelpfulMessage() throws IOException {
    Path f = dir.resolve("big.txt");
    Files.write(f, new byte[2048]);
    FileTooLargeException ex =
        assertThrows(FileTooLargeException.class, () -> FileIO.readText(f, 1024));
    assertEquals(2048, ex.size());
    assertEquals(1024, ex.limit());
    assertTrue(
        ex.getMessage().contains("big.txt")
            && ex.getMessage().contains("2.0 KB")
            && ex.getMessage().contains("1.0 KB"),
        ex.getMessage());
    assertEquals(2048, FileIO.readText(f, 4096).length());
    assertTrue(FileIO.MAX_FILE_BYTES > FileIO.LARGE_FILE_BYTES);
  }

  @Test
  void binaryAndLatin1FilesGetAClearErrorNotAGarbledBuffer() throws IOException {
    Path f = dir.resolve("bin");
    Files.write(f, new byte[] {'o', 'k', (byte) 0xC3, 0x28});
    IOException ex = assertThrows(IOException.class, () -> FileIO.readText(f));
    assertTrue(ex.getMessage().contains("not valid UTF-8"), ex.getMessage());
    assertNotNull(ex.getCause());
  }

  @Test
  void missingFilesAndDirectoriesFailAsIoErrors() {
    assertThrows(IOException.class, () -> FileIO.readText(dir.resolve("missing")));
    assertThrows(IOException.class, () -> FileIO.readText(dir));
  }

  @Test
  void aMegabyteOfTextRoundTrips() throws IOException {
    Path f = dir.resolve("m.txt");
    String text = "line of source code;\n".repeat(60_000);
    FileIO.writeAtomic(f, text);
    assertEquals(text, FileIO.readText(f));
  }

  @Test
  void createsNewFilesAndMissingFolders() throws IOException {
    Path f = dir.resolve("new/deep/file.txt");
    FileIO.writeAtomic(f, "fresh");
    assertEquals("fresh", Files.readString(f));
    Path relative = Path.of("javelin-ide-test-" + System.nanoTime() + ".tmp");
    try {
      FileIO.writeAtomic(relative, "rel");
      assertEquals("rel", Files.readString(relative));
    } finally {
      Files.deleteIfExists(relative);
    }
  }

  @Test
  void replacesExistingContentAndLeavesNoTempFiles() throws IOException {
    Path f = dir.resolve("a.txt");
    Files.writeString(f, "old content that is longer than the new one");
    FileIO.writeAtomic(f, "new");
    assertEquals("new", Files.readString(f));
    try (Stream<Path> files = Files.list(dir)) {
      assertEquals(1, files.count(), "no temp file may be left behind");
    }
  }

  @Test
  void existingPermissionsSurvive() throws IOException {
    assumeTrue(posix());
    Path f = dir.resolve("script.sh");
    Files.writeString(f, "#!/bin/sh\n");
    Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr--"));
    FileIO.writeAtomic(f, "#!/bin/sh\necho hi\n");
    assertEquals("rwxr-xr--", PosixFilePermissions.toString(Files.getPosixFilePermissions(f)));
  }

  @Test
  void savingThroughASymlinkKeepsTheLinkAndUpdatesTheTarget() throws IOException {
    assumeTrue(posix());
    Path real = dir.resolve("real.txt"), link = dir.resolve("link.txt");
    Files.writeString(real, "one");
    Files.createSymbolicLink(link, real.getFileName());
    FileIO.writeAtomic(link, "two");
    assertTrue(Files.isSymbolicLink(link), "the link itself must survive a save");
    assertEquals("two", Files.readString(real));
  }

  @Test
  void aFailedSaveLeavesTheOriginalIntact() throws IOException {
    assumeTrue(posix());
    Path folder = dir.resolve("ro");
    Files.createDirectory(folder);
    Path f = folder.resolve("keep.txt");
    Files.writeString(f, "precious");
    Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      assumeTrue(!Files.isWritable(folder), "running as a user that ignores directory permissions");
      assertThrows(IOException.class, () -> FileIO.writeAtomic(f, "lost?"));
      assertEquals("precious", Files.readString(f));
    } finally {
      Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  @Test
  void writingOverADirectoryFails() throws IOException {
    Path sub = dir.resolve("sub");
    Files.createDirectory(sub);
    assertThrows(IOException.class, () -> FileIO.writeAtomic(sub, "x"));
    try (Stream<Path> files = Files.list(dir)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void fileSystemsWithoutAtomicMovesFallBackToAPlainReplace() throws IOException {
    Path f = dir.resolve("a.txt");
    Files.writeString(f, "old");
    java.util.List<java.util.List<java.nio.file.CopyOption>> attempts = new java.util.ArrayList<>();
    FileIO.writeAtomic(
        f,
        "new",
        (from, to, options) -> {
          attempts.add(java.util.List.of(options));
          if (options.length == 2)
            throw new java.nio.file.AtomicMoveNotSupportedException(
                from.toString(), to.toString(), "unsupported");
          Files.move(from, to, options);
        });
    assertEquals(2, attempts.size());
    assertEquals("new", Files.readString(f));
    try (Stream<Path> files = Files.list(dir)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void aFailingMoveCleansUpItsTempFileAndKeepsTheOriginal() throws IOException {
    Path f = dir.resolve("a.txt");
    Files.writeString(f, "old");
    assertThrows(
        IOException.class,
        () ->
            FileIO.writeAtomic(
                f,
                "new",
                (from, to, options) -> {
                  throw new IOException("disk full");
                }));
    assertEquals("old", Files.readString(f));
    try (Stream<Path> files = Files.list(dir)) {
      assertEquals(1, files.count());
    }
  }

  @Test
  void sizesAreDescribedForPeople() {
    assertEquals("0 B", FileIO.describe(0));
    assertEquals("1023 B", FileIO.describe(1023));
    assertEquals("1.0 KB", FileIO.describe(1024));
    assertEquals("12.5 KB", FileIO.describe(12_800));
    assertEquals("3.0 MB", FileIO.describe(3L << 20));
  }

  @Test
  void unicodeIsEncodedAsUtf8() throws IOException {
    Path f = dir.resolve("u.txt");
    FileIO.writeAtomic(f, "\u00e9");
    assertArrayEquals(new byte[] {(byte) 0xC3, (byte) 0xA9}, Files.readAllBytes(f));
    Files.writeString(f, "x", StandardCharsets.UTF_8);
    FileIO.writeAtomic(f, "\u00e9\u00e9");
    assertEquals(4, Files.size(f));
  }
}
