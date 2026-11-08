package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.ai.AiException;
import com.javelin.ai.AnchorRung;
import com.javelin.ai.ChangeReply;
import com.javelin.ai.EditBlocks;
import com.javelin.ai.FailureReason;
import com.javelin.language.Json;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Project requests answered with JSON edits: each edit names its file. */
class MultiFileJsonReplyTest {
  @TempDir Path dir;
  ProjectWorkspace ws;
  ProjectSnapshot snapshot;

  static final String EDITOR = "src/main/java/demo/editor/Editor.java";
  static final String DOCUMENT = "src/main/java/demo/editor/Document.java";

  @BeforeEach
  void setUp() throws IOException, AiException {
    ProjectFixture.miniEditor(dir);
    ws = new ProjectWorkspace(dir, ScanOptions.defaults());
    snapshot = snapshotOf(FullProjectSelector.select(ws.index()));
  }

  private ProjectSnapshot snapshotOf(List<Pick> picks) throws AiException {
    return ContextBuilder.build(
            ws.index(),
            ws.reader(),
            picks.size() == ws.index().files().size()
                ? ContextMode.FULL_PROJECT
                : ContextMode.SMART,
            picks,
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

  private static Map<String, Object> edit(String file, String anchor, String old, String neu) {
    return Json.obj("file", file, "anchor", anchor, "old", old, "new", neu);
  }

  @SafeVarargs
  private static String reply(String explanation, Map<String, Object>... edits) {
    return Json.write(Json.obj("explanation", explanation, "edits", List.of(edits)));
  }

  @Test
  void jsonEditsForTwoFilesBecomeTwoModifications() {
    String text =
        reply(
            "Both files.",
            edit(
                EDITOR,
                "  public String text() {",
                "    return document.text();",
                "    return document.text().trim();"),
            edit(DOCUMENT, "", "    return text;", "    return text == null ? \"\" : text;"));
    assertTrue(MultiFileReply.looksLike(text));
    ProjectProposal p = parse(text);
    assertEquals("Both files.", p.explanation());
    assertTrue(p.problems().isEmpty(), p.problems().toString());
    assertEquals(2, p.changes().size());
    FileChange editor = p.changes().get(0);
    assertEquals(EDITOR, editor.path());
    assertEquals(FileChange.Kind.MODIFY, editor.kind());
    assertEquals(ChangeReply.Format.JSON_EDITS, editor.format());
    assertTrue(editor.inContext());
    assertTrue(editor.replacement().contains("document.text().trim()"));
    assertEquals(List.of(new EditBlocks.Anchor(1, AnchorRung.EXACT)), editor.anchors());
    assertTrue(p.changes().get(1).replacement().contains("text == null"));
  }

  @Test
  void severalEditsForOneFileAreAppliedInOrder() {
    ProjectProposal p =
        parse(
            reply(
                "",
                edit(
                    EDITOR,
                    "",
                    "    return document.text();",
                    "    return document.text().trim();"),
                edit(
                    EDITOR,
                    "",
                    "    return highlighter.apply(text());",
                    "    return highlighter.apply(text()).trim();")));
    assertEquals(1, p.changes().size());
    FileChange c = p.changes().get(0);
    assertEquals(2, c.anchors().size());
    assertTrue(c.replacement().contains("document.text().trim()"));
    assertTrue(c.replacement().contains("apply(text()).trim()"));
  }

  @Test
  void aLooseMatchIsFlaggedOnTheFileThatHasIt() {
    ProjectProposal p =
        parse(
            reply(
                "", edit(EDITOR, "", "return document.text();", "return document.text().trim();")));
    FileChange c = p.changes().get(0);
    assertEquals(AnchorRung.INDENTATION, c.anchors().get(0).rung());
    assertTrue(c.findings().stream().anyMatch(f -> f.message().contains("ignoring indentation")));
  }

  @Test
  void aNewFileIsOneEditWithAnEmptyOld() {
    String path = "src/main/java/demo/diagram/UmlGenerator.java";
    ProjectProposal p =
        parse(
            reply("", edit(path, "", "", "package demo.diagram;\n\npublic class UmlGenerator {}")));
    assertTrue(p.problems().isEmpty(), p.problems().toString());
    FileChange c = p.changes().get(0);
    assertEquals(FileChange.Kind.CREATE, c.kind());
    assertEquals("", c.original());
    assertEquals("package demo.diagram;\n\npublic class UmlGenerator {}\n", c.replacement());
    assertFalse(c.inContext());
  }

  @Test
  void anEditToAFileThatDoesNotExistIsNotACreation() {
    ProjectProposal p =
        parse(reply("", edit("src/main/java/demo/Nope.java", "", "int x;", "int y;")));
    assertTrue(p.changes().isEmpty());
    assertEquals(List.of(FailureReason.WRONG_FILE), p.failures());
    assertTrue(p.problems().get(0).contains("Nope.java"), p.problems().toString());
  }

  @Test
  void anEmptyOldForAFileThatExistsIsRefusedAndTheFileIsLeftAlone() {
    ProjectProposal p = parse(reply("", edit(EDITOR, "", "", "class Editor {}")));
    assertTrue(p.changes().isEmpty());
    assertEquals(List.of(FailureReason.UNPARSEABLE), p.failures());
  }

  @Test
  void aFileTheModelWasNotShownIsRefusedWhileTheOthersAreProposed() throws Exception {
    snapshot = snapshotOf(List.of(new Pick(EDITOR, "r", 1)));
    assertFalse(snapshot.sentHashes().containsKey(DOCUMENT));
    ProjectProposal p =
        parse(
            reply(
                "",
                edit(EDITOR, "", "    return document.text();", "    return \"\";"),
                edit(DOCUMENT, "", "    return text;", "    return \"\";")));
    assertEquals(1, p.changes().size());
    assertEquals(EDITOR, p.changes().get(0).path());
    assertEquals(List.of(FailureReason.WRONG_FILE), p.failures());
    assertTrue(p.problems().get(0).contains(DOCUMENT), p.problems().toString());
    assertTrue(p.problems().get(0).contains("not shown to the model"), p.problems().toString());
  }

  @Test
  void oneFilesBadAnchorDoesNotTakeTheOtherFileWithIt() {
    ProjectProposal p =
        parse(
            reply(
                "",
                edit(
                    EDITOR,
                    "  public String nothing() {",
                    "    return document.text();",
                    "    return \"\";"),
                edit(DOCUMENT, "", "    return text;", "    return \"x\";")));
    assertEquals(1, p.changes().size());
    assertEquals(DOCUMENT, p.changes().get(0).path());
    assertEquals(List.of(FailureReason.ANCHOR_NOT_FOUND), p.failures());
    assertTrue(p.problems().get(0).contains(EDITOR), p.problems().toString());
  }

  @Test
  void aCutOffReplyProposesNothingAndSaysSo() {
    String whole = reply("", edit(EDITOR, "", "    return document.text();", "    return \"\";"));
    ProjectProposal p = parse(whole.substring(0, whole.length() - 9));
    assertTrue(p.changes().isEmpty());
    assertEquals(List.of(FailureReason.TRUNCATED), p.failures());
  }

  @Test
  void theKeptSectionCanBeAppliedAgainToTheSameFile() throws Exception {
    ProjectProposal p =
        parse(
            reply(
                "",
                edit(
                    EDITOR,
                    "  public String text() {",
                    "    return document.text();",
                    "    return \"z\";")));
    FileChange c = p.changes().get(0);
    ChangeReply.Interpreted again = ChangeReply.interpret(c.rawSection(), c.original(), c.path());
    assertEquals(c.replacement(), again.code());
    assertEquals(ChangeReply.Format.JSON_EDITS, again.format());
  }

  @Test
  void textRepliesAreStillUnderstood() {
    ProjectProposal p =
        parse(
            "=== FILE: "
                + EDITOR
                + " ===\n"
                + "<<<<<<< SEARCH\n"
                + "    return document.text();\n"
                + "=======\n"
                + "    return \"\";\n"
                + ">>>>>>> REPLACE\n");
    assertEquals(1, p.changes().size());
    assertEquals(ChangeReply.Format.EDIT_BLOCKS, p.changes().get(0).format());
    assertEquals(List.of(new EditBlocks.Anchor(1, AnchorRung.EXACT)), p.changes().get(0).anchors());
  }
}
