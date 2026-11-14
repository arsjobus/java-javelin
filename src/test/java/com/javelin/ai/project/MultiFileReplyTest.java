package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.ai.AiException;
import com.javelin.ai.ChangeReply;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MultiFileReplyTest {
  @TempDir Path dir;
  ProjectWorkspace ws;
  ProjectSnapshot snapshot;

  static final String EDITOR = "src/main/java/demo/editor/Editor.java";

  @BeforeEach
  void setUp() throws IOException, AiException {
    ProjectFixture.miniEditor(dir);
    ws = new ProjectWorkspace(dir, ScanOptions.defaults());
    snapshot =
        ContextBuilder.build(
                ws.index(),
                ws.reader(),
                ContextMode.FULL_PROJECT,
                FullProjectSelector.select(ws.index()),
                Adjustments.NONE,
                new ContextBudget(0, 200_000, 4_000),
                100,
                ws.options(),
                1)
            .snapshot();
  }

  private ProjectProposal parse(String reply) {
    return MultiFileReply.parse(reply, ws.reader(), snapshot, ws.options());
  }

  private static String edit(String search, String replace) {
    return "<<<<<<< SEARCH\n" + search + "\n=======\n" + replace + "\n>>>>>>> REPLACE\n";
  }

  @Test
  void anExistingFileWithEditBlocksBecomesAModification() {
    ProjectProposal p =
        parse(
            "I add a method.\n\n=== FILE: "
                + EDITOR
                + " ===\n"
                + edit(
                    "  public String text() {",
                    "  public String all() {\n"
                        + "    return text();\n"
                        + "  }\n\n"
                        + "  public String text() {"));
    assertEquals("I add a method.", p.explanation());
    assertEquals(1, p.changes().size());
    FileChange c = p.changes().get(0);
    assertEquals(FileChange.Kind.MODIFY, c.kind());
    assertEquals(EDITOR, c.path());
    assertTrue(c.inContext());
    assertEquals(ChangeReply.Format.EDIT_BLOCKS, c.format());
    assertTrue(c.replacement().contains("public String all()"));
    assertTrue(c.original().contains("public String text()"));
    assertTrue(p.problems().isEmpty());
    assertEquals(snapshot.id(), p.snapshotId());
    assertTrue(c.summary().startsWith(EDITOR + ": +"));
    assertFalse(c.hasErrors());
    assertEquals("1 file", p.summary());
    assertTrue(c.diff().added() > 0);
  }

  @Test
  void aNewFileComesFromOneCodeBlock() {
    ProjectProposal p =
        parse(
            "=== NEW FILE: src/main/java/demo/diagram/UmlGenerator.java ===\n"
                + "```java\n"
                + "package demo.diagram;\n\n"
                + "public class UmlGenerator {}\n"
                + "```\n");
    FileChange c = p.changes().get(0);
    assertEquals(FileChange.Kind.CREATE, c.kind());
    assertEquals("", c.original());
    assertTrue(c.replacement().startsWith("package demo.diagram;"));
    assertTrue(c.replacement().endsWith("}\n"));
    assertFalse(c.inContext());
    assertEquals("1 file (1 new)", p.summary());
    assertTrue(c.summary().startsWith("new "));
  }

  @Test
  void severalFilesInOneReplyAreSeparatedByTheirHeadings() {
    ProjectProposal p =
        parse(
            "Plan.\n=== FILE: "
                + EDITOR
                + " ===\n"
                + edit("  public String text() {", "  public String text() { // changed")
                + "\n"
                + "=== NEW FILE: src/test/java/demo/diagram/UmlTest.java ===\n"
                + "```java\n"
                + "class UmlTest {}\n"
                + "```\n\n"
                + "### FILE: `src/main/java/demo/ui/MainWindow.java`\n"
                + edit(
                    "    System.out.println(editor.text());",
                    "    System.out.println(editor.text().trim());"));
    assertEquals(
        List.of(
            EDITOR,
            "src/test/java/demo/diagram/UmlTest.java",
            "src/main/java/demo/ui/MainWindow.java"),
        p.changes().stream().map(FileChange::path).toList());
    assertEquals("Plan.", p.explanation());
  }

  @Test
  void headingsInTheWildAreAccepted() {
    for (String heading :
        new String[] {
          "=== FILE: " + EDITOR + " ===",
          "FILE: " + EDITOR,
          "**FILE: " + EDITOR + "**",
          "## File: `" + EDITOR + "`",
          "### FILE: ./" + EDITOR,
          "=== file: \"" + EDITOR + "\" ==="
        }) {
      ProjectProposal p =
          parse(heading + "\n" + edit("  public String text() {", "  public String text() { // x"));
      assertEquals(1, p.changes().size(), heading + " -> " + p.problems());
      assertEquals(EDITOR, p.changes().get(0).path());
    }
  }

  @Test
  void aFileThatDoesNotExistButIsCalledExistingBecomesANewFileWithAWarning() {
    ProjectProposal p =
        parse("=== FILE: src/main/java/demo/Fresh.java ===\n```java\nclass Fresh {}\n```\n");
    FileChange c = p.changes().get(0);
    assertEquals(FileChange.Kind.CREATE, c.kind());
    assertTrue(c.findings().stream().anyMatch(f -> f.message().contains("no such file")));
  }

  @Test
  void editsToAFileThatIsNotThereAreAProblem() {
    ProjectProposal p = parse("=== FILE: src/Nope.java ===\n" + edit("a", "b"));
    assertTrue(p.isEmpty());
    assertEquals(1, p.problems().size());
    assertTrue(p.problems().get(0).startsWith("src/Nope.java: "));
  }

  @Test
  void aNewFileHeadingForAnExistingFileIsRefusedNotOverwritten() {
    ProjectProposal p = parse("=== NEW FILE: " + EDITOR + " ===\n```java\nclass X {}\n```\n");
    assertTrue(p.isEmpty());
    assertTrue(p.problems().get(0).contains("exists already"), p.problems().toString());
    assertEquals(java.util.List.of(com.javelin.ai.FailureReason.WRONG_FILE), p.failures());
  }

  @Test
  void pathsOutsideTheProjectAreRefusedWhateverTheirSpelling() {
    for (String bad :
        new String[] {
          "../outside.txt",
          "src/../../outside.txt",
          "/etc/passwd",
          "C:/Windows/x.txt",
          "src/main/../../../x.txt"
        }) {
      ProjectProposal p = parse("=== NEW FILE: " + bad + " ===\n```\nx\n```\n");
      assertTrue(p.isEmpty(), bad);
      assertEquals(1, p.problems().size(), bad);
    }
  }

  @Test
  void credentialsToolFoldersBuildOutputAndEditorSettingsCannotBeChanged() {
    for (String bad :
        new String[] {
          ".env",
          "config/.env.production",
          "keys/server.pem",
          ".git/config",
          "target/classes/A.java",
          "node_modules/x/index.js",
          ".javelin-ide/settings.conf",
          ".ssh/authorized_keys",
          ".idea/workspace.xml"
        }) {
      ProjectProposal p = parse("=== NEW FILE: " + bad + " ===\n```\nx\n```\n");
      assertTrue(p.isEmpty(), bad);
      assertTrue(p.problems().get(0).contains("may not change"), bad + ": " + p.problems());
    }
  }

  @Test
  void aSymbolicLinkInThePathIsRefused() throws IOException {
    Path outside = Files.createTempDirectory("outside");
    try {
      Files.createSymbolicLink(dir.resolve("linked"), outside);
    } catch (UnsupportedOperationException | IOException ex) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false, "no symbolic links here");
    }
    ProjectProposal p = parse("=== NEW FILE: linked/x.txt ===\n```\nx\n```\n");
    assertTrue(p.isEmpty());
    assertTrue(p.problems().get(0).contains("symbolic link"), p.problems().toString());
  }

  @Test
  void editsThatDoNotMatchTheFileAreAProblemAndTheOtherFilesStillCount() {
    ProjectProposal p =
        parse(
            "=== FILE: "
                + EDITOR
                + " ===\n"
                + edit("this line is not in the file", "x")
                + "\n=== NEW FILE: src/main/java/demo/Ok.java ===\n```java\nclass Ok {}\n```\n");
    assertEquals(1, p.changes().size());
    assertEquals("src/main/java/demo/Ok.java", p.changes().get(0).path());
    assertEquals(1, p.problems().size());
    assertTrue(p.problems().get(0).startsWith(EDITOR + ": "));
  }

  @Test
  void aFileHeadingRepeatedForEachBlockKeepsEveryBlock() {
    ProjectProposal p =
        parse(
            "=== FILE: "
                + EDITOR
                + " ===\n"
                + edit("  public String text() {", "  public String text() { // 1")
                + "=== FILE: "
                + EDITOR
                + " ===\n"
                + edit("  public String highlight() {", "  public String highlight() { // 2"));
    assertEquals(1, p.changes().size());
    String text = p.changes().get(0).replacement();
    assertTrue(text.contains("// 1"));
    assertTrue(text.contains("// 2"));
    assertTrue(p.problems().isEmpty());
  }

  @Test
  void aRepeatedFileThatCannotBeMergedIsUsedOnlyOnce() {
    ProjectProposal p =
        parse(
            "=== FILE: "
                + EDITOR
                + " ===\n"
                + edit("  public String text() {", "  public String text() { // 1")
                + "=== FILE: "
                + EDITOR
                + " ===\n```java\nclass Other {}\n```\n");
    assertEquals(1, p.changes().size());
    assertTrue(p.changes().get(0).replacement().contains("// 1"));
    assertTrue(p.problems().get(0).contains("more than once"));
  }

  @Test
  void aChangeThatChangesNothingIsDropped() {
    ProjectProposal p =
        parse(
            "=== FILE: "
                + EDITOR
                + " ===\n"
                + edit("  public String text() {", "  public String text() {"));
    assertTrue(p.isEmpty());
  }

  @Test
  void aFileTheModelWasNotShownIsRefusedAndNothingIsProposedForIt()
      throws AiException, IOException {
    ProjectSnapshot partial =
        ContextBuilder.build(
                ws.index(),
                ws.reader(),
                ContextMode.SMART,
                List.of(new Pick("README.md", "r", 1)),
                Adjustments.NONE,
                new ContextBudget(0, 200_000, 4_000),
                100,
                ws.options(),
                1)
            .snapshot();
    ProjectProposal p =
        MultiFileReply.parse(
            "=== FILE: "
                + EDITOR
                + " ===\n"
                + edit("  public String text() {", "  public String text() { // x"),
            ws.reader(),
            partial,
            ws.options());
    assertTrue(p.changes().isEmpty(), "a change to a file the model never saw is not proposed");
    assertTrue(p.isEmpty());
    assertTrue(p.problems().get(0).contains("not shown to the model"), p.problems().toString());
    assertEquals(
        List.of(com.javelin.ai.FailureReason.WRONG_FILE),
        p.failures(),
        "counted as an edit to a file outside the context");
  }

  @Test
  void aFileChangedAfterTheContextWasBuiltGetsAWarning() throws IOException {
    Files.writeString(
        dir.resolve(EDITOR),
        Files.readString(dir.resolve(EDITOR))
            .replace("private final Highlighter", "private final Highlighter /* edited */"));
    ProjectProposal p =
        parse(
            "=== FILE: "
                + EDITOR
                + " ===\n"
                + edit("  public String text() {", "  public String text() { // x"));
    assertTrue(
        p.changes().get(0).findings().stream()
            .anyMatch(f -> f.message().contains("changed after the project was sent")));
  }

  @Test
  void lineEndingsAndTheFinalNewlineOfTheOriginalAreKept() throws IOException {
    String crlf = Files.readString(dir.resolve(EDITOR)).replace("\n", "\r\n");
    Files.writeString(dir.resolve(EDITOR), crlf);
    ProjectProposal p =
        parse(
            "=== FILE: "
                + EDITOR
                + " ===\n"
                + edit("  public String text() {", "  public String text() { // x"));
    String out = p.changes().get(0).replacement();
    assertTrue(out.contains("\r\n"));
    assertFalse(out.replace("\r\n", "").contains("\n"), "no bare line feeds");
  }

  @Test
  void aWholeFileReplacementIsAcceptedAndMarkedAsSuch() {
    ProjectProposal p =
        parse(
            "=== FILE: src/main/java/demo/editor/Document.java ===\n"
                + "```java\n"
                + "package demo.editor;\n\n"
                + "public class Document {\n"
                + "  private String text = \"\";\n\n"
                + "  public String text() {\n"
                + "    return text;\n"
                + "  }\n\n"
                + "  public int length() {\n"
                + "    return text.length();\n"
                + "  }\n"
                + "}\n"
                + "```\n");
    FileChange c = p.changes().get(0);
    assertEquals(ChangeReply.Format.WHOLE_BLOCK, c.format());
    assertTrue(c.replacement().contains("public int length()"));
  }

  @Test
  void aGitStyleDiffOverSeveralFilesIsUnderstood() {
    String reply =
        "Here you go.\n\n"
            + "```diff\n"
            + "diff --git a/src/main/java/demo/editor/Document.java"
            + " b/src/main/java/demo/editor/Document.java\n"
            + "--- a/src/main/java/demo/editor/Document.java\n"
            + "+++ b/src/main/java/demo/editor/Document.java\n"
            + "@@ -4,5 +4,9 @@\n"
            + "   private String text = \"\";\n"
            + " \n"
            + "   public String text() {\n"
            + "     return text;\n"
            + "   }\n"
            + "+\n"
            + "+  public int length() {\n"
            + "+    return text.length();\n"
            + "+  }\n"
            + " }\n"
            + "--- /dev/null\n"
            + "+++ b/src/main/java/demo/NewThing.java\n"
            + "@@ -0,0 +1,3 @@\n"
            + "+package demo;\n"
            + "+\n"
            + "+class NewThing {}\n"
            + "```\n";
    ProjectProposal p = parse(reply);
    assertEquals("Here you go.", p.explanation());
    assertEquals(2, p.changes().size(), p.problems().toString());
    assertEquals(ChangeReply.Format.DIFF, p.changes().get(0).format());
    assertTrue(p.changes().get(0).replacement().contains("public int length()"));
    assertEquals(FileChange.Kind.CREATE, p.changes().get(1).kind());
    assertEquals("package demo;\n\nclass NewThing {}\n", p.changes().get(1).replacement());
  }

  @Test
  void aReplyInWordsIsNotAChange() {
    assertFalse(MultiFileReply.looksLike("The editor holds a document and a highlighter."));
    assertFalse(MultiFileReply.looksLike("Edit the file: Editor.java is where it lives."));
    assertTrue(MultiFileReply.looksLike("=== FILE: " + EDITOR + " ===\nx"));
    assertTrue(MultiFileReply.looksLike("--- a/x.txt\n+++ b/x.txt\n@@ -1 +1 @@\n-a\n+b\n"));
    assertFalse(MultiFileReply.looksLike("--- a/x.txt\n+++ b/x.txt\nno hunk here\n"));
  }

  @Test
  void reasoningBeforeTheAnswerIsIgnored() {
    ProjectProposal p =
        parse(
            "<think>\n"
                + "=== FILE: src/Fake.java ===\n"
                + "not this\n"
                + "</think>\n"
                + "Done.\n"
                + "=== NEW FILE: src/main/java/demo/Real.java ===\n"
                + "```java\n"
                + "class Real {}\n"
                + "```\n");
    assertEquals(1, p.changes().size());
    assertEquals("src/main/java/demo/Real.java", p.changes().get(0).path());
  }

  @Test
  void tooManyFilesAreLimited() {
    StringBuilder reply = new StringBuilder();
    for (int i = 0; i < MultiFileReply.MAX_FILES + 5; i++)
      reply
          .append("=== NEW FILE: gen/F")
          .append(i)
          .append(".java ===\n```java\nclass F")
          .append(i)
          .append(" {}\n```\n");
    ProjectProposal p = parse(reply.toString());
    assertEquals(MultiFileReply.MAX_FILES, p.changes().size());
    assertEquals(5, p.problems().size());
  }

  @Test
  void aBrokenNewFileIsFlagged() {
    ProjectProposal p =
        parse(
            "=== NEW FILE: src/main/java/demo/Broken.java ===\n"
                + "```java\n"
                + "public class Broken {\n"
                + "  void f() {\n"
                + "```\n");
    assertTrue(p.changes().get(0).hasErrors(), p.changes().get(0).findings().toString());
  }
}
