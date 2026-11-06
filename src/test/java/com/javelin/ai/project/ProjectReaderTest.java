package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.javelin.ai.project.ProjectReader.OutsideProjectException;
import com.javelin.io.FileIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectReaderTest {
  @TempDir Path dir;

  private ProjectFile file(String path) throws IOException {
    Path p = dir.resolve(path);
    return new ProjectFile(
        path,
        Files.size(p),
        Files.getLastModifiedTime(p).toMillis(),
        "text",
        ProjectFile.Kind.OTHER);
  }

  @Test
  void resolvesPathsInsideTheProject() throws IOException {
    ProjectFixture.write(dir, "a/b.txt", "x");
    ProjectReader reader = new ProjectReader(dir);
    assertEquals(dir.toRealPath().resolve("a/b.txt"), reader.resolve("a/b.txt"));
    assertEquals(dir.toRealPath().resolve("a/b.txt"), reader.resolve("./a//b.txt"));
    assertEquals(dir.toRealPath().resolve("a/b.txt"), reader.resolve("a\\b.txt"));
    assertEquals(dir.toRealPath().resolve("new/file.txt"), reader.resolve("new/file.txt"));
    assertEquals(dir.toRealPath(), reader.root());
  }

  @Test
  void refusesEveryWayOfLeavingTheProject() throws IOException {
    ProjectReader reader = new ProjectReader(dir);
    for (String bad :
        new String[] {
          "../x.txt",
          "a/../../x.txt",
          "a/..",
          "..",
          "/etc/passwd",
          "\\windows\\x",
          "C:/x.txt",
          "c:\\x.txt",
          "",
          "   ",
          ".",
          "./",
          "a\0b"
        }) assertThrows(OutsideProjectException.class, () -> reader.resolve(bad), "'" + bad + "'");
    assertThrows(OutsideProjectException.class, () -> reader.resolve(null));
  }

  @Test
  void refusesAPathThatGoesThroughASymbolicLink() throws IOException {
    Path outside = Files.createTempDirectory("outside");
    ProjectFixture.write(outside, "secret.txt", "s");
    try {
      Files.createSymbolicLink(dir.resolve("link"), outside);
    } catch (UnsupportedOperationException | IOException ex) {
      assumeTrue(false, "this system cannot create symbolic links");
    }
    ProjectReader reader = new ProjectReader(dir);
    assertThrows(OutsideProjectException.class, () -> reader.resolve("link/secret.txt"));
    assertThrows(OutsideProjectException.class, () -> reader.resolve("link"));
    assertThrows(OutsideProjectException.class, () -> reader.readNow("link/secret.txt", 1000));
  }

  @Test
  void readsTextAndKeepsItWhileTheFileIsUnchanged() throws IOException {
    ProjectFixture.write(dir, "a.txt", "hello");
    ProjectReader reader = new ProjectReader(dir);
    ProjectFile a = file("a.txt");
    String first = reader.read(a, 1000);
    assertEquals("hello", first);
    assertEquals(1, reader.cachedFiles());
    Files.writeString(dir.resolve("a.txt"), "HELLO"); // same size, same stamp in the index entry
    assertSame(first, reader.read(a, 1000));
    reader.clear();
    assertEquals(0, reader.cachedFiles());
    assertEquals("HELLO", reader.read(a, 1000));
  }

  @Test
  void aChangedSizeOrTimeReadsAgain() throws IOException {
    ProjectFixture.write(dir, "a.txt", "one");
    ProjectReader reader = new ProjectReader(dir);
    assertEquals("one", reader.read(file("a.txt"), 1000));
    Files.writeString(dir.resolve("a.txt"), "three");
    assertEquals("three", reader.read(file("a.txt"), 1000));
    Files.writeString(dir.resolve("a.txt"), "THREE");
    Files.setLastModifiedTime(dir.resolve("a.txt"), FileTime.fromMillis(5_000_000_000L));
    assertEquals("THREE", reader.read(file("a.txt"), 1000));
  }

  @Test
  void readNowAlwaysReadsTheDisk() throws IOException {
    ProjectFixture.write(dir, "a.txt", "one");
    ProjectReader reader = new ProjectReader(dir);
    assertEquals("one", reader.readNow("a.txt", 1000));
    Files.writeString(dir.resolve("a.txt"), "two");
    assertEquals("two", reader.readNow("a.txt", 1000));
  }

  @Test
  void aFileThatIsGoneTooLargeOrNotTextIsAnIoError() throws IOException {
    ProjectFixture.write(dir, "big.txt", "x".repeat(100));
    Files.write(dir.resolve("bin.dat"), new byte[] {(byte) 0xff, (byte) 0xfe});
    ProjectReader reader = new ProjectReader(dir);
    assertThrows(FileIO.FileTooLargeException.class, () -> reader.readNow("big.txt", 10));
    assertThrows(IOException.class, () -> reader.readNow("bin.dat", 1000));
    assertThrows(IOException.class, () -> reader.readNow("missing.txt", 1000));
    assertThrows(
        IOException.class,
        () -> reader.read(new ProjectFile("gone.txt", 1, 1, null, ProjectFile.Kind.OTHER), 1000));
  }

  @Test
  void aFingerprintTellsDifferentTextsApart() {
    assertEquals(ProjectReader.hash("abc"), ProjectReader.hash("abc"));
    assertNotEquals(ProjectReader.hash("abc"), ProjectReader.hash("abd"));
    assertEquals(64, ProjectReader.hash("").length());
    assertTrue(ProjectReader.hash("x").matches("[0-9a-f]{64}"));
  }

  @Test
  void aReaderForAFolderThatIsNotThereFails() {
    assertThrows(IOException.class, () -> new ProjectReader(dir.resolve("missing")));
  }
}
