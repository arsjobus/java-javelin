package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A reply that continues work that is not on disk: the overlay of the steps of a plan. */
class MultiFileReplyOverlayTest {
  @TempDir Path dir;
  ProjectWorkspace ws;

  static final String A = "src/A.java";
  static final String ON_DISK = "class A {\n  int f() {\n    return 1;\n  }\n}\n";
  static final String LATER = "class A {\n  int f() {\n    return 2;\n  }\n}\n";

  @BeforeEach
  void setUp() throws IOException {
    Files.createDirectories(dir.resolve("src"));
    Files.writeString(dir.resolve(A), ON_DISK);
    ws = new ProjectWorkspace(dir, ScanOptions.defaults());
  }

  private ProjectSnapshot snapshot(String sentText) {
    return new ProjectSnapshot("s", 0L, Map.of(), Map.of(A, ProjectReader.hash(sentText)));
  }

  private static String edit(String path, String search, String replace) {
    return "=== FILE: "
        + path
        + " ===\n<<<<<<< SEARCH\n"
        + search
        + "\n=======\n"
        + replace
        + "\n>>>>>>> REPLACE\n";
  }

  @Test
  void aFileIsChangedFromTheTextOfTheEarlierStepNotFromTheDisk() {
    ProjectProposal p =
        MultiFileReply.parse(
            edit(A, "    return 2;", "    return 3;"),
            ws.reader(),
            snapshot(LATER),
            ws.options(),
            Map.of(A, LATER));
    assertTrue(p.problems().isEmpty(), p.problems().toString());
    FileChange c = p.changes().get(0);
    assertEquals(LATER, c.original());
    assertTrue(c.replacement().contains("return 3;"));
    assertTrue(c.findings().stream().noneMatch(f -> f.message().contains("changed after")));
  }

  @Test
  void withoutTheOverlayTheSameEditDoesNotFitTheDisk() {
    ProjectProposal p =
        MultiFileReply.parse(
            edit(A, "    return 2;", "    return 3;"),
            ws.reader(),
            snapshot(ON_DISK),
            ws.options());
    assertTrue(p.changes().isEmpty());
    assertFalse(p.problems().isEmpty());
  }

  @Test
  void aFileThatExistsOnlyInTheOverlayCanBeChangedButNotCreatedAgain() throws IOException {
    String fresh = "src/Fresh.java";
    String text = "class Fresh {\n  int g() {\n    return 1;\n  }\n}\n";
    ProjectSnapshot snap =
        new ProjectSnapshot("s", 0L, Map.of(), Map.of(fresh, ProjectReader.hash(text)));
    ProjectProposal changed =
        MultiFileReply.parse(
            edit(fresh, "    return 1;", "    return 9;"),
            ws.reader(),
            snap,
            ws.options(),
            Map.of(fresh, text));
    assertEquals(FileChange.Kind.MODIFY, changed.changes().get(0).kind());
    ProjectProposal again =
        MultiFileReply.parse(
            "=== NEW FILE: " + fresh + " ===\n```java\nclass Fresh {}\n```\n",
            ws.reader(),
            snap,
            ws.options(),
            Map.of(fresh, text));
    assertTrue(again.changes().isEmpty(), "an existing file is not replaced by a new one");
  }

  @Test
  void theDefaultOverlayIsEmptyAndChangesNothing() {
    ProjectProposal p =
        MultiFileReply.parse(
            edit(A, "    return 1;", "    return 5;"),
            ws.reader(),
            snapshot(ON_DISK),
            ws.options());
    assertEquals(1, p.changes().size());
    assertEquals(ON_DISK, p.changes().get(0).original());
  }
}
