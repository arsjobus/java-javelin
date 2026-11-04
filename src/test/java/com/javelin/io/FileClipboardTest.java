package com.javelin.io;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Copy, Cut and Paste in the project tree, as the sidebar uses them but without a window. */
class FileClipboardTest {
  @TempDir Path dir;
  Path project;
  Path target;
  final FileClipboard clipboard = new FileClipboard();

  @BeforeEach
  void setUp() throws IOException {
    project = Files.createDirectory(dir.resolve("project"));
    target = Files.createDirectory(project.resolve("target"));
  }

  private Path file(String relative, String text) throws IOException {
    Path path = project.resolve(relative);
    Files.createDirectories(path.getParent());
    return Files.writeString(path, text);
  }

  @Test
  void startsEmptyAndPastingNothingDoesNothing() {
    assertTrue(clipboard.isEmpty());
    FileClipboard.Result result = clipboard.paste(project, target);
    assertTrue(result.done().isEmpty());
    assertTrue(result.failures().isEmpty());
  }

  @Test
  void copyThenPasteMakesACopyAndKeepsTheOriginal() throws IOException {
    Path a = file("A.txt", "a");
    clipboard.copy(List.of(a));
    FileClipboard.Result result = clipboard.paste(project, target);
    assertEquals(
        List.of(new FileClipboard.Pasted(a, target.resolve("A.txt"), false)), result.done());
    assertEquals("a", Files.readString(a));
    assertEquals("a", Files.readString(target.resolve("A.txt")));
  }

  @Test
  void aCopyCanBePastedManyTimesAndEachPasteMakesNewFiles() throws IOException {
    Path a = file("A.txt", "a");
    clipboard.copy(List.of(a));
    clipboard.paste(project, target);
    clipboard.paste(project, target);
    clipboard.paste(project, target);
    assertFalse(clipboard.isEmpty(), "a copy stays on the clipboard");
    assertTrue(Files.exists(target.resolve("A.txt")));
    assertTrue(Files.exists(target.resolve("A copy.txt")));
    assertTrue(Files.exists(target.resolve("A copy 2.txt")));
  }

  @Test
  void cutThenPasteMovesTheFileAndEmptiesTheClipboard() throws IOException {
    Path a = file("A.txt", "a");
    clipboard.cut(List.of(a));
    assertTrue(Files.exists(a), "cutting alone removes nothing");
    FileClipboard.Result result = clipboard.paste(project, target);
    assertEquals(
        List.of(new FileClipboard.Pasted(a, target.resolve("A.txt"), true)), result.done());
    assertFalse(Files.exists(a));
    assertEquals("a", Files.readString(target.resolve("A.txt")));
    assertTrue(clipboard.isEmpty(), "a cut is pasted once");
  }

  @Test
  void cutMovesAFolderWithEverythingInIt() throws IOException {
    file("lib/deep/B.txt", "b");
    clipboard.cut(List.of(project.resolve("lib")));
    clipboard.paste(project, target);
    assertEquals("b", Files.readString(target.resolve("lib/deep/B.txt")));
    assertFalse(Files.exists(project.resolve("lib")));
  }

  @Test
  void cutAndPasteIntoTheSameFolderChangesNothing() throws IOException {
    Path a = file("target/A.txt", "a");
    clipboard.cut(List.of(a));
    FileClipboard.Result result = clipboard.paste(project, target);
    assertTrue(result.done().isEmpty());
    assertTrue(result.failures().isEmpty());
    assertEquals(1, result.unchanged());
    assertEquals("a", Files.readString(a));
  }

  @Test
  void aCutNeverOverwritesAndTheRefusedFileStaysForAnotherTry() throws IOException {
    Path a = file("A.txt", "mine");
    Path b = file("B.txt", "b");
    file("target/A.txt", "theirs");
    Path elsewhere = Files.createDirectory(project.resolve("elsewhere"));
    clipboard.cut(List.of(a, b));
    FileClipboard.Result result = clipboard.paste(project, target);
    assertEquals(1, result.done().size(), "B still moves");
    assertEquals(1, result.failures().size());
    assertTrue(result.failures().get(0).startsWith("A.txt: "), result.failures().get(0));
    assertEquals("theirs", Files.readString(target.resolve("A.txt")));
    assertEquals(
        List.of(a), clipboard.sources(), "only the refused file is left to place somewhere else");
    clipboard.paste(project, elsewhere);
    assertEquals("mine", Files.readString(elsewhere.resolve("A.txt")));
    assertTrue(clipboard.isEmpty());
  }

  @Test
  void aFolderCannotBePastedIntoItselfAndStaysOnTheClipboard() throws IOException {
    Path inner = Files.createDirectories(project.resolve("outer/inner"));
    Path outer = project.resolve("outer");
    for (Runnable choose :
        new Runnable[] {
          () -> clipboard.cut(List.of(outer)), () -> clipboard.copy(List.of(outer))
        }) {
      choose.run();
      FileClipboard.Result result = clipboard.paste(project, inner);
      assertTrue(result.done().isEmpty());
      assertEquals(1, result.failures().size());
      assertTrue(result.failures().get(0).contains("into itself"), result.failures().get(0));
      assertFalse(clipboard.isEmpty());
    }
  }

  @Test
  void aSelectedFolderCoversItsSelectedChildren() throws IOException {
    Path child = file("pkg/A.txt", "a");
    clipboard.copy(List.of(child, project.resolve("pkg")));
    assertEquals(List.of(project.resolve("pkg")), clipboard.sources());
    clipboard.paste(project, target);
    try (var listing = Files.list(target.resolve("pkg"))) {
      assertEquals(1, listing.count(), "A.txt is copied once, inside pkg");
    }
  }

  @Test
  void severalItemsAreHandledIndependentlyAndPartialFailureKeepsTheRest() throws IOException {
    Path a = file("A.txt", "a");
    Path gone = project.resolve("Gone.txt");
    Path c = file("C.txt", "c");
    clipboard.copy(List.of(a, gone, c));
    FileClipboard.Result result = clipboard.paste(project, target);
    assertEquals(2, result.done().size());
    assertEquals(1, result.failures().size());
    assertTrue(result.failures().get(0).startsWith("Gone.txt: "), result.failures().get(0));
  }

  @Test
  void aCutItemThatNoLongerExistsIsNotOfferedAgain() throws IOException {
    Path a = file("A.txt", "a");
    clipboard.cut(List.of(a));
    Files.delete(a);
    FileClipboard.Result result = clipboard.paste(project, target);
    assertEquals(1, result.failures().size());
    assertTrue(clipboard.isEmpty());
  }

  @Test
  void theLastCopyOrCutReplacesTheOneBefore() throws IOException {
    Path a = file("A.txt", "a");
    Path b = file("B.txt", "b");
    clipboard.cut(List.of(a));
    clipboard.copy(List.of(b));
    assertEquals(FileClipboard.Mode.COPY, clipboard.mode());
    assertEquals(List.of(b), clipboard.sources());
    clipboard.paste(project, target);
    assertTrue(Files.exists(a), "a was no longer cut");
    assertTrue(Files.exists(b));
    clipboard.clear();
    assertTrue(clipboard.isEmpty());
  }

  @Test
  void nothingLeavesTheProjectEitherWay() throws IOException {
    Path outside = Files.writeString(dir.resolve("outside.txt"), "o");
    Path inside = file("A.txt", "a");
    clipboard.copy(List.of(outside));
    assertEquals(1, clipboard.paste(project, target).failures().size());
    clipboard.cut(List.of(inside));
    assertEquals(
        1, clipboard.paste(project, dir).failures().size(), "a destination outside the project");
    assertTrue(Files.exists(inside));
    assertEquals("o", Files.readString(outside));
  }
}
