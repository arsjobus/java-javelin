package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.javelin.ai.project.ProjectIndex.Reason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectScannerTest {
  @TempDir Path dir;

  private static List<String> paths(ProjectIndex index) {
    return index.files().stream().map(ProjectFile::path).toList();
  }

  private static Reason reasonFor(ProjectIndex index, String path) {
    return index.excluded().stream()
        .filter(e -> e.path().equals(path))
        .map(ProjectIndex.Excluded::reason)
        .findFirst()
        .orElse(null);
  }

  @Test
  void findsEveryTextFileInPathOrderWithLanguageAndKind() throws IOException {
    ProjectFixture.miniEditor(dir);
    ProjectIndex index = ProjectScanner.scan(dir, ScanOptions.defaults());
    List<String> paths = paths(index);
    assertEquals(paths.stream().sorted().toList(), paths);
    assertTrue(paths.contains("src/main/java/demo/editor/Editor.java"));
    assertTrue(paths.contains("README.md"));
    assertTrue(paths.contains(".gitignore"));
    ProjectFile editor = index.find("src/main/java/demo/editor/Editor.java");
    assertEquals("java", editor.language());
    assertEquals(ProjectFile.Kind.SOURCE, editor.kind());
    assertEquals("Editor.java", editor.name());
    assertEquals("src/main/java/demo/editor", editor.directory());
    assertEquals(
        ProjectFile.Kind.TEST, index.find("src/test/java/demo/editor/EditorTest.java").kind());
    assertEquals(ProjectFile.Kind.BUILD, index.find("pom.xml").kind());
    assertEquals("", index.find("README.md").directory());
    assertNull(index.find("nope.txt"));
    assertEquals(dir.getFileName().toString(), index.name());
    assertTrue(index.languageCounts().get("java") > 5);
  }

  @Test
  void leavesOutWhatGitignoreIgnoresBuildOutputBinariesAndSecretsAndSaysWhy() throws IOException {
    ProjectFixture.miniEditor(dir);
    ProjectIndex index = ProjectScanner.scan(dir, ScanOptions.defaults());
    assertFalse(paths(index).contains(".env"));
    assertFalse(paths(index).contains("debug.log"));
    assertFalse(paths(index).contains("secret-notes.txt"));
    assertEquals(Reason.SENSITIVE, reasonFor(index, ".env"));
    assertEquals(Reason.GENERATED, reasonFor(index, "debug.log"));
    assertEquals(Reason.GITIGNORE, reasonFor(index, "secret-notes.txt"));
    assertEquals(Reason.GENERATED, reasonFor(index, "target/"));
    assertTrue(paths(index).stream().noneMatch(p -> p.startsWith("target/")));
    assertTrue(index.excludedTotal() >= 4);
    assertTrue(index.excludedByReason().containsKey(Reason.SENSITIVE));
    assertEquals("may hold credentials", Reason.SENSITIVE.label());
  }

  @Test
  void gitignoreCanBeSwitchedOff() throws IOException {
    ProjectFixture.write(dir, ".gitignore", "ignored.txt\n");
    ProjectFixture.write(dir, "ignored.txt", "x\n");
    assertFalse(paths(ProjectScanner.scan(dir, ScanOptions.defaults())).contains("ignored.txt"));
    assertTrue(
        paths(ProjectScanner.scan(dir, ScanOptions.defaults().withGitignore(false)))
            .contains("ignored.txt"));
  }

  @Test
  void nestedGitignoreFilesApplyBelowTheirFolderAndDeeperOnesWin() throws IOException {
    ProjectFixture.write(dir, ".gitignore", "*.gen\n");
    ProjectFixture.write(dir, "a/.gitignore", "!keep.gen\nonly-in-a.txt\n");
    ProjectFixture.write(dir, "a/keep.gen", "k\n");
    ProjectFixture.write(dir, "a/drop.gen", "d\n");
    ProjectFixture.write(dir, "a/only-in-a.txt", "o\n");
    ProjectFixture.write(dir, "b/only-in-a.txt", "o\n");
    ProjectFixture.write(dir, "top.gen", "t\n");
    List<String> paths = paths(ProjectScanner.scan(dir, ScanOptions.defaults()));
    assertTrue(paths.contains("a/keep.gen"));
    assertFalse(paths.contains("a/drop.gen"));
    assertFalse(paths.contains("a/only-in-a.txt"));
    assertTrue(paths.contains("b/only-in-a.txt"));
    assertFalse(paths.contains("top.gen"));
  }

  @Test
  void aFolderIgnoredByGitignoreIsNeverEnteredSoItsFilesCannotComeBack() throws IOException {
    ProjectFixture.write(dir, ".gitignore", "vendor/\n!vendor/keep.txt\n");
    ProjectFixture.write(dir, "vendor/keep.txt", "k\n");
    ProjectIndex index = ProjectScanner.scan(dir, ScanOptions.defaults());
    assertFalse(paths(index).contains("vendor/keep.txt"));
    assertEquals(Reason.GITIGNORE, reasonFor(index, "vendor/"));
  }

  @Test
  void userExclusionsUseGitignoreSyntaxForFilesAndFolders() throws IOException {
    ProjectFixture.write(dir, "src/A.java", "class A {}\n");
    ProjectFixture.write(dir, "sql/schema.sql", "create table t(x int);\n");
    ProjectFixture.write(dir, "docs/old/a.md", "old\n");
    ProjectFixture.write(dir, "docs/new.md", "new\n");
    ScanOptions options = ScanOptions.defaults().withExclude(List.of("*.sql", "docs/old/"));
    ProjectIndex index = ProjectScanner.scan(dir, options);
    assertEquals(List.of("docs/new.md", "src/A.java"), paths(index));
    assertEquals(Reason.EXCLUDED, reasonFor(index, "sql/schema.sql"));
    assertEquals(Reason.EXCLUDED, reasonFor(index, "docs/old/"));
    assertEquals(List.of("*.sql", "docs/old/"), options.exclude());
  }

  @Test
  void sensitiveFilesAndFoldersAreAlwaysLeftOutEvenWhenIgnoredByNothing() throws IOException {
    ProjectFixture.write(dir, "config/.env.production", "TOKEN=1\n");
    ProjectFixture.write(dir, "config/.env.example", "TOKEN=\n");
    ProjectFixture.write(dir, "keys/server.pem", "-----BEGIN PRIVATE KEY-----\n");
    ProjectFixture.write(dir, ".ssh/config", "Host x\n");
    ProjectFixture.write(dir, "ok.txt", "fine\n");
    ProjectIndex index = ProjectScanner.scan(dir, ScanOptions.defaults());
    assertEquals(List.of("config/.env.example", "ok.txt"), paths(index));
    assertEquals(Reason.SENSITIVE, reasonFor(index, "config/.env.production"));
    assertEquals(Reason.SENSITIVE, reasonFor(index, "keys/server.pem"));
    assertEquals(Reason.SENSITIVE, reasonFor(index, ".ssh/"));
  }

  @Test
  void binaryFilesAreRecognisedByExtensionByNulBytesAndByInvalidUtf8() throws IOException {
    Files.write(dir.resolve("logo.png"), new byte[] {1, 2, 3});
    Files.write(dir.resolve("noext"), new byte[] {'a', 0, 'b'});
    Files.write(dir.resolve("latin1.txt"), new byte[] {(byte) 0xE9, (byte) 0xE8, 'x'});
    ProjectFixture.write(dir, "utf8.txt", "caf\u00e9 \u4e2d\u6587\n");
    ProjectIndex index = ProjectScanner.scan(dir, ScanOptions.defaults());
    assertEquals(List.of("utf8.txt"), paths(index));
    assertEquals(Reason.BINARY, reasonFor(index, "logo.png"));
    assertEquals(Reason.BINARY, reasonFor(index, "noext"));
    assertEquals(Reason.BINARY, reasonFor(index, "latin1.txt"));
  }

  @Test
  void aMultiByteCharacterCutByTheSniffWindowIsNotMistakenForBinary() throws IOException {
    String text = "x".repeat(8191) + "\u00e9" + "y".repeat(100);
    ProjectFixture.write(dir, "edge.txt", text);
    assertEquals(List.of("edge.txt"), paths(ProjectScanner.scan(dir, ScanOptions.defaults())));
  }

  @Test
  void largeFilesAndGeneratedFilesAreLeftOut() throws IOException {
    ProjectFixture.write(dir, "big.txt", "x".repeat(5_000));
    ProjectFixture.write(dir, "small.txt", "x");
    ProjectFixture.write(dir, "package-lock.json", "{}");
    ProjectFixture.write(dir, "app.min.js", "var a;");
    ProjectFixture.write(dir, "node_modules/lib/index.js", "x");
    ProjectIndex index = ProjectScanner.scan(dir, ScanOptions.defaults().withLimits(1_000, 100));
    assertEquals(List.of("small.txt"), paths(index));
    assertEquals(Reason.TOO_LARGE, reasonFor(index, "big.txt"));
    assertEquals(Reason.GENERATED, reasonFor(index, "package-lock.json"));
    assertEquals(Reason.GENERATED, reasonFor(index, "app.min.js"));
    assertEquals(Reason.GENERATED, reasonFor(index, "node_modules/"));
  }

  @Test
  void symbolicLinksAreNeverFollowedSoNothingOutsideTheProjectIsReached() throws IOException {
    Path outside = Files.createTempDirectory("outside");
    try {
      ProjectFixture.write(outside, "secret.txt", "outside secret\n");
      ProjectFixture.write(dir, "inside.txt", "ok\n");
      try {
        Files.createSymbolicLink(dir.resolve("link-to-file"), outside.resolve("secret.txt"));
        Files.createSymbolicLink(dir.resolve("link-to-dir"), outside);
        Files.createSymbolicLink(dir.resolve("loop"), dir);
      } catch (UnsupportedOperationException | IOException ex) {
        assumeTrue(false, "this system cannot create symbolic links");
      }
      ProjectIndex index = ProjectScanner.scan(dir, ScanOptions.defaults());
      assertEquals(List.of("inside.txt"), paths(index));
      assertEquals(Reason.SYMLINK, reasonFor(index, "link-to-file"));
      assertEquals(Reason.SYMLINK, reasonFor(index, "link-to-dir"));
      assertEquals(Reason.SYMLINK, reasonFor(index, "loop"));
    } finally {
      Files.deleteIfExists(dir.resolve("link-to-file"));
      Files.deleteIfExists(dir.resolve("link-to-dir"));
      Files.deleteIfExists(dir.resolve("loop"));
    }
  }

  @Test
  void aProjectGivenThroughALinkIsScannedAtItsRealLocation() throws IOException {
    ProjectFixture.write(dir, "a.txt", "x\n");
    Path link = Files.createTempDirectory("links").resolve("project-link");
    try {
      Files.createSymbolicLink(link, dir);
    } catch (UnsupportedOperationException | IOException ex) {
      assumeTrue(false, "this system cannot create symbolic links");
    }
    ProjectIndex index = ProjectScanner.scan(link, ScanOptions.defaults());
    assertEquals(dir.toRealPath(), index.root());
    assertEquals(List.of("a.txt"), paths(index));
  }

  @Test
  void theFileCountLimitIsAppliedInPathOrderAndReported() throws IOException {
    for (int i = 0; i < 25; i++) ProjectFixture.write(dir, String.format("f%02d.txt", i), "x");
    ProjectIndex index = ProjectScanner.scan(dir, ScanOptions.defaults().withLimits(1_000, 10));
    assertEquals(10, index.files().size());
    assertEquals("f00.txt", index.files().get(0).path());
    assertEquals("f09.txt", index.files().get(9).path());
    assertTrue(index.limited());
    assertEquals(15, index.excludedByReason().get(Reason.LIMIT));
    assertFalse(ProjectScanner.scan(dir, ScanOptions.defaults()).limited());
  }

  @Test
  void theListOfLeftOutThingsIsCappedButTheCountIsExact() throws IOException {
    for (int i = 0; i < ProjectScanner.MAX_EXCLUDED_LISTED + 50; i++)
      Files.write(dir.resolve("b" + i + ".png"), new byte[] {1});
    ProjectIndex index = ProjectScanner.scan(dir, ScanOptions.defaults());
    assertEquals(ProjectScanner.MAX_EXCLUDED_LISTED + 50, index.excludedTotal());
    assertEquals(ProjectScanner.MAX_EXCLUDED_LISTED, index.excluded().size());
  }

  @Test
  void aFolderThatIsNotAFolderIsAnError() throws IOException {
    Path file = ProjectFixture.write(dir, "plain.txt", "x");
    assertThrows(IOException.class, () -> ProjectScanner.scan(file, ScanOptions.defaults()));
    assertThrows(
        IOException.class,
        () -> ProjectScanner.scan(dir.resolve("missing"), ScanOptions.defaults()));
  }

  @Test
  void anEmptyProjectHasNoFiles() throws IOException {
    ProjectIndex index = ProjectScanner.scan(dir, ScanOptions.defaults());
    assertTrue(index.files().isEmpty());
    assertTrue(index.languageCounts().isEmpty());
    assertEquals(0, index.excludedTotal());
  }

  @Test
  void aVeryDeepTreeStopsAtTheDepthLimit() throws IOException {
    Path deep = dir;
    for (int i = 0; i < 70; i++) deep = deep.resolve("d");
    Files.createDirectories(deep);
    Files.writeString(deep.resolve("x.txt"), "x");
    ProjectFixture.write(dir, "top.txt", "x");
    ProjectIndex index = ProjectScanner.scan(dir, ScanOptions.defaults());
    assertEquals(List.of("top.txt"), paths(index));
    assertTrue(index.excludedByReason().containsKey(Reason.LIMIT));
  }

  @Test
  void aLargeRepositoryIsScannedQuicklyAndCompletely() throws IOException {
    for (int d = 0; d < 60; d++)
      for (int f = 0; f < 40; f++)
        ProjectFixture.write(
            dir, "pkg" + d + "/Class" + f + ".java", "package p;\nclass C" + f + " {}\n");
    long start = System.nanoTime();
    ProjectIndex index = ProjectScanner.scan(dir, ScanOptions.defaults());
    long millis = (System.nanoTime() - start) / 1_000_000;
    assertEquals(2_400, index.files().size());
    assertTrue(millis < 10_000, "scanning 2400 files took " + millis + " ms");
  }

  @Test
  void aRefreshGivesTheSameResultAsAFreshScanAfterAnyKindOfChange() throws IOException {
    ProjectFixture.miniEditor(dir);
    ScanOptions options = ScanOptions.defaults();
    ProjectIndex before = ProjectScanner.scan(dir, options);
    ProjectFixture.write(dir, "src/main/java/demo/New.java", "package demo;\nclass New {}\n");
    Files.delete(dir.resolve("src/main/java/demo/util/Strings.java"));
    Path edited = dir.resolve("README.md");
    Files.writeString(edited, "# Changed and longer\n");
    Files.setLastModifiedTime(edited, FileTime.fromMillis(1_000_000_000L));
    Files.write(dir.resolve("src/main/java/demo/ai/AiClient.java"), new byte[] {0, 1, 2});
    ProjectIndex refreshed = ProjectScanner.refresh(before, options);
    ProjectIndex fresh = ProjectScanner.scan(dir, options);
    assertEquals(fresh, refreshed);
    assertNotNull(refreshed.find("src/main/java/demo/New.java"));
    assertNull(refreshed.find("src/main/java/demo/util/Strings.java"));
    assertEquals(Reason.BINARY, reasonFor(refreshed, "src/main/java/demo/ai/AiClient.java"));
  }

  @Test
  void theSameProjectAlwaysGivesTheSameIndex() throws IOException {
    ProjectFixture.miniEditor(dir);
    assertEquals(
        ProjectScanner.scan(dir, ScanOptions.defaults()),
        ProjectScanner.scan(dir, ScanOptions.defaults()));
  }

  @Test
  void excludedFilesAreListedWithTheirReasonAndTheListsAreUnmodifiable() throws IOException {
    ProjectFixture.miniEditor(dir);
    ProjectIndex index = ProjectScanner.scan(dir, ScanOptions.defaults());
    String listed =
        index.excluded().stream()
            .map(e -> e.path() + "=" + e.reason())
            .collect(Collectors.joining(","));
    assertTrue(listed.contains(".env=SENSITIVE"), listed);
    assertThrows(UnsupportedOperationException.class, () -> index.files().add(null));
    assertThrows(UnsupportedOperationException.class, () -> index.excluded().add(null));
  }
}
