package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.ai.AiException;
import com.javelin.ai.ChangeReply;
import com.javelin.ai.project.ProposalApplier.Check;
import com.javelin.ai.project.ProposalApplier.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProposalApplierTest {
  @TempDir Path dir;
  ProjectWorkspace ws;
  ProjectSnapshot snapshot;
  static final String EDITOR = "src/main/java/demo/editor/Editor.java";
  static final String DOC = "src/main/java/demo/editor/Document.java";
  static final String NEW = "src/main/java/demo/diagram/Uml.java";

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

  private static String edit(String search, String replace) {
    return "<<<<<<< SEARCH\n" + search + "\n=======\n" + replace + "\n>>>>>>> REPLACE\n";
  }

  private ProjectProposal proposal() {
    String reply =
        "=== FILE: "
            + EDITOR
            + " ===\n"
            + edit("  public String text() {", "  public String text() { // ai")
            + "=== FILE: "
            + DOC
            + " ===\n"
            + edit("  private String text = \"\";", "  private String text = \"\"; // ai")
            + "=== NEW FILE: "
            + NEW
            + " ===\n```java\npackage demo.diagram;\n\nclass Uml {}\n```\n";
    return MultiFileReply.parse(reply, ws.reader(), snapshot, ws.options());
  }

  private List<Check> check(ProjectProposal p, Set<String> unsaved) {
    return ProposalApplier.check(ws.reader(), p, unsaved, ws.options());
  }

  private static Check of(List<Check> checks, String path) {
    return checks.stream().filter(c -> c.change().path().equals(path)).findFirst().orElseThrow();
  }

  @Test
  void untouchedFilesAreOkAndANewFileIsOk() {
    List<Check> checks = check(proposal(), Set.of());
    assertEquals(3, checks.size());
    for (Check c : checks) assertEquals(Status.OK, c.status(), c.message());
    assertEquals("new file", of(checks, NEW).message());
    assertTrue(of(checks, EDITOR).diff().added() > 0);
  }

  @Test
  void applyingWritesOnlyApprovedFilesAtomicallyAndLeavesTheRest() throws IOException {
    ProjectProposal p = proposal();
    String docBefore = Files.readString(dir.resolve(DOC));
    AppliedChanges applied =
        ProposalApplier.apply(ws.reader(), check(p, Set.of()), Set.of(EDITOR, NEW));
    assertEquals(List.of(EDITOR, NEW), applied.paths());
    assertFalse(applied.isEmpty());
    assertTrue(Files.readString(dir.resolve(EDITOR)).contains("// ai"));
    assertTrue(Files.readString(dir.resolve(NEW)).contains("class Uml"));
    assertEquals(docBefore, Files.readString(dir.resolve(DOC)), "not approved, not written");
  }

  @Test
  void aFileChangedElsewhereIsRebasedByContent() throws IOException {
    ProjectProposal p = proposal();
    Files.writeString(
        dir.resolve(EDITOR),
        Files.readString(dir.resolve(EDITOR))
            .replace(
                "package demo.editor;", "package demo.editor;\n\n// header added by the user"));
    Check c = of(check(p, Set.of()), EDITOR);
    assertEquals(Status.REBASED, c.status(), c.message());
    assertTrue(c.newText().contains("header added by the user"));
    assertTrue(c.newText().contains("// ai"));
    ProposalApplier.apply(ws.reader(), List.of(c), Set.of(EDITOR));
    String now = Files.readString(dir.resolve(EDITOR));
    assertTrue(now.contains("header added by the user") && now.contains("// ai"));
  }

  @Test
  void aFileChangedWhereTheChangeWasMadeIsAConflictAndIsNotWritten() throws IOException {
    ProjectProposal p = proposal();
    String edited =
        Files.readString(dir.resolve(EDITOR))
            .replace("public String text() {", "public String content() {");
    Files.writeString(dir.resolve(EDITOR), edited);
    Check c = of(check(p, Set.of()), EDITOR);
    assertEquals(Status.CONFLICT, c.status());
    assertTrue(c.message().contains("no longer fits"), c.message());
    assertFalse(c.status().applicable());
    AppliedChanges none = ProposalApplier.apply(ws.reader(), List.of(c), Set.of(EDITOR));
    assertTrue(none.isEmpty());
    assertEquals(edited, Files.readString(dir.resolve(EDITOR)));
  }

  @Test
  void aWholeFileReplacementCannotBeMovedOntoChangedText() throws IOException {
    String reply =
        "=== FILE: "
            + DOC
            + " ===\n```java\npackage demo.editor;\n\npublic class Document {\n  int x;\n}\n```\n";
    ProjectProposal p = MultiFileReply.parse(reply, ws.reader(), snapshot, ws.options());
    assertEquals(ChangeReply.Format.WHOLE_BLOCK, p.changes().get(0).format());
    assertEquals(Status.OK, of(check(p, Set.of()), DOC).status());
    Files.writeString(dir.resolve(DOC), Files.readString(dir.resolve(DOC)) + "// user\n");
    Check c = of(check(p, Set.of()), DOC);
    assertEquals(Status.CONFLICT, c.status());
    assertTrue(c.message().contains("whole-file"), c.message());
  }

  @Test
  void aFileThatIsGoneIsMissingAndANewFileThatNowExistsIsNeverOverwritten() throws IOException {
    ProjectProposal p = proposal();
    Files.delete(dir.resolve(DOC));
    ProjectFixture.write(dir, NEW, "somebody wrote this\n");
    List<Check> checks = check(p, Set.of());
    assertEquals(Status.MISSING, of(checks, DOC).status());
    assertEquals(Status.ALREADY_EXISTS, of(checks, NEW).status());
    ProposalApplier.apply(ws.reader(), checks, Set.of(DOC, NEW, EDITOR));
    assertEquals("somebody wrote this\n", Files.readString(dir.resolve(NEW)));
    assertFalse(Files.exists(dir.resolve(DOC)));
  }

  @Test
  void aFileWithUnsavedEditsInTheEditorIsNeverTouched() throws IOException {
    ProjectProposal p = proposal();
    List<Check> checks = check(p, Set.of(EDITOR));
    assertEquals(Status.UNSAVED, of(checks, EDITOR).status());
    assertEquals(Status.OK, of(checks, DOC).status());
    String before = Files.readString(dir.resolve(EDITOR));
    ProposalApplier.apply(ws.reader(), checks, Set.of(EDITOR, DOC));
    assertEquals(before, Files.readString(dir.resolve(EDITOR)));
    assertTrue(Files.readString(dir.resolve(DOC)).contains("// ai"));
  }

  @Test
  void aTamperedPathIsRefusedAtApplyTimeToo() {
    FileChange evil =
        new FileChange(
            "../outside.txt",
            FileChange.Kind.CREATE,
            "",
            "x\n",
            "",
            ChangeReply.Format.WHOLE_BLOCK,
            false,
            List.of());
    FileChange secret =
        new FileChange(
            ".env",
            FileChange.Kind.CREATE,
            "",
            "x\n",
            "",
            ChangeReply.Format.WHOLE_BLOCK,
            false,
            List.of());
    List<Check> checks =
        check(new ProjectProposal("id", "", List.of(evil, secret), List.of()), Set.of());
    assertEquals(Status.REFUSED, checks.get(0).status());
    assertEquals(Status.REFUSED, checks.get(1).status());
    assertFalse(Files.exists(dir.getParent().resolve("outside.txt")));
  }

  @Test
  void aWriteThatFailsPutsEveryEarlierFileBack() throws IOException {
    ProjectProposal p = proposal();
    ProjectFixture.write(dir, "blocked", "i am a file, not a folder\n");
    FileChange blocked =
        new FileChange(
            "blocked/x.txt",
            FileChange.Kind.CREATE,
            "",
            "x\n",
            "",
            ChangeReply.Format.WHOLE_BLOCK,
            false,
            List.of());
    ProjectProposal withBlocked =
        new ProjectProposal(
            "id", "", List.of(p.changes().get(0), p.changes().get(2), blocked), List.of());
    String editorBefore = Files.readString(dir.resolve(EDITOR));
    List<Check> checks = check(withBlocked, Set.of());
    IOException ex =
        assertThrows(
            IOException.class,
            () -> ProposalApplier.apply(ws.reader(), checks, Set.of(EDITOR, NEW, "blocked/x.txt")));
    assertTrue(ex.getMessage().contains("nothing was changed"), ex.getMessage());
    assertEquals(editorBefore, Files.readString(dir.resolve(EDITOR)));
    assertFalse(Files.exists(dir.resolve(NEW)));
    assertFalse(Files.exists(dir.resolve("src/main/java/demo/diagram/Uml.java")));
  }

  @Test
  void rollbackRestoresModifiedFilesAndRemovesCreatedOnesAndTheFoldersItMade() throws IOException {
    ProjectProposal p = proposal();
    ProjectFixture.write(dir, "x/keep.txt", "keep\n");
    String editorBefore = Files.readString(dir.resolve(EDITOR));
    FileChange deepNew =
        new FileChange(
            "brand/new/dir/File.txt",
            FileChange.Kind.CREATE,
            "",
            "n\n",
            "",
            ChangeReply.Format.WHOLE_BLOCK,
            false,
            List.of());
    ProjectProposal all =
        new ProjectProposal("id", "", List.of(p.changes().get(0), deepNew), List.of());
    AppliedChanges applied =
        ProposalApplier.apply(
            ws.reader(), check(all, Set.of()), Set.of(EDITOR, "brand/new/dir/File.txt"));
    assertTrue(Files.exists(dir.resolve("brand/new/dir/File.txt")));
    AppliedChanges.Rollback r = applied.rollback(ws.reader());
    assertEquals(List.of("brand/new/dir/File.txt", EDITOR), r.restored());
    assertTrue(r.skipped().isEmpty());
    assertEquals(editorBefore, Files.readString(dir.resolve(EDITOR)));
    assertFalse(Files.exists(dir.resolve("brand")), "folders created by the apply are removed");
    assertTrue(Files.exists(dir.resolve("x/keep.txt")));
  }

  @Test
  void rollbackLeavesAFileTheUserEditedSinceAndSaysSo() throws IOException {
    ProjectProposal p = proposal();
    AppliedChanges applied =
        ProposalApplier.apply(ws.reader(), check(p, Set.of()), Set.of(EDITOR, DOC, NEW));
    Files.writeString(dir.resolve(EDITOR), "the user rewrote this\n");
    Files.delete(dir.resolve(NEW));
    AppliedChanges.Rollback r = applied.rollback(ws.reader());
    assertEquals(List.of(DOC), r.restored());
    assertEquals(2, r.skipped().size());
    assertTrue(
        r.skipped().stream().anyMatch(s -> s.startsWith(EDITOR) && s.contains("changed since")));
    assertEquals("the user rewrote this\n", Files.readString(dir.resolve(EDITOR)));
  }

  @Test
  void aFolderThatGotOtherFilesIsNotRemovedByRollback() throws IOException {
    FileChange fresh =
        new FileChange(
            "gen/A.txt",
            FileChange.Kind.CREATE,
            "",
            "a\n",
            "",
            ChangeReply.Format.WHOLE_BLOCK,
            false,
            List.of());
    AppliedChanges applied =
        ProposalApplier.apply(
            ws.reader(),
            check(new ProjectProposal("id", "", List.of(fresh), List.of()), Set.of()),
            Set.of("gen/A.txt"));
    ProjectFixture.write(dir, "gen/B.txt", "b\n");
    applied.rollback(ws.reader());
    assertFalse(Files.exists(dir.resolve("gen/A.txt")));
    assertTrue(Files.exists(dir.resolve("gen/B.txt")));
  }

  @Test
  void nothingApprovedWritesNothing() throws IOException {
    AppliedChanges none = ProposalApplier.apply(ws.reader(), check(proposal(), Set.of()), Set.of());
    assertTrue(none.isEmpty());
    assertTrue(none.rollback(ws.reader()).restored().isEmpty());
  }
}
