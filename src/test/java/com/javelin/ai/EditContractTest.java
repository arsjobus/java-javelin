package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.language.Json;
import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The contract between the model and the editor for "change this code": what is understood, what is
 * refused with a reason, and that a refused edit writes nothing. The same cases run for edit blocks
 * and for JSON edits.
 */
class EditContractTest {
  @TempDir Path dir;
  private FakeAiProvider provider;
  private AiService service;

  private static final String DOC =
      "class Foo {\n"
          + "    int add(int a, int b) {\n"
          + "        return a - b;\n"
          + "    }\n"
          + "\n"
          + "    int sub(int a, int b) {\n"
          + "        return a - b;\n"
          + "    }\n"
          + "}\n";
  private static final int FROM = 0;
  private static final int TO = DOC.length();

  @BeforeEach
  void setUp() {
    provider = new FakeAiProvider();
    Settings settings = new Settings(dir.resolve("settings.conf"));
    settings.put(Scope.USER, "ai.model", "fake-model");
    service = new AiService(provider, AiConfig.from(settings));
  }

  private AiResult change(String reply) throws Exception {
    provider.answer = reply;
    AiPlan plan =
        service.prepare(
            AiAction.PROPOSE_CHANGE,
            new AiSource("src/Foo.java", "Foo.java", "java", DOC, FROM, TO),
            "make add add");
    return service.run(plan, s -> {}, new AiCancel());
  }

  private static String blocks(String search, String replace) {
    return "<<<<<<< SEARCH\n" + search + "\n=======\n" + replace + "\n>>>>>>> REPLACE";
  }

  private static String json(String file, String anchor, String old, String neu) {
    return Json.write(
        Json.obj(
            "explanation",
            "Fixed it.",
            "edits",
            List.of(Json.obj("file", file, "anchor", anchor, "old", old, "new", neu))));
  }

  // ---- understood ----

  @Test
  void editBlocksInsideACodeFenceAreUnderstood() throws Exception {
    AiResult r =
        change(
            "Sure.\n```\n"
                + blocks(
                    "        return a - b;\n    }\n\n    int sub(int a, int b) {",
                    "        return a + b;\n    }\n\n    int sub(int a, int b) {")
                + "\n```");
    assertNotNull(r.proposal(), r.note());
    assertTrue(r.proposal().replacement().contains("return a + b;"));
    assertEquals(1, r.proposal().replacement().split("return a - b;", -1).length - 1);
  }

  @Test
  void jsonEditsAreUnderstoodWhateverTheProviderWasAsked() throws Exception {
    AiResult r =
        change(
            json(
                "Foo.java",
                "int add(int a, int b) {",
                "        return a - b;",
                "        return a + b;"));
    assertNotNull(r.proposal(), r.note());
    assertEquals(
        DOC.replaceFirst("a - b", "a + b"),
        r.proposal().replacement(),
        "the anchor puts the edit in add, not in sub");
    assertEquals(List.of(new EditBlocks.Anchor(1, AnchorRung.EXACT)), r.proposal().anchors());
    assertEquals("Fixed it.", r.proposal().explanation());
  }

  @Test
  void jsonInsideAFenceIsUnderstood() throws Exception {
    AiResult r =
        change(
            "```json\n"
                + json(
                    "Foo.java",
                    "int sub(int a, int b) {",
                    "        return a - b;",
                    "        return 0;")
                + "\n```");
    assertNotNull(r.proposal(), r.note());
    assertTrue(
        r.proposal().replacement().indexOf("return 0;")
            > r.proposal().replacement().indexOf("int sub"));
  }

  // ---- refused, with a reason, and nothing is written ----

  private void assertRefused(AiResult r, FailureReason reason) {
    assertNull(r.proposal(), "a refused edit proposes nothing");
    assertEquals(reason, r.failure(), r.note());
    assertNotNull(r.note());
    assertFalse(r.note().isBlank());
  }

  @Test
  void anEditThatMatchesTwoPlacesIsRefused() throws Exception {
    AiResult text = change(blocks("        return a - b;", "        return a + b;"));
    assertRefused(text, FailureReason.ANCHOR_NOT_FOUND);
    assertTrue(text.note().contains("2 places"), text.note());
    assertRefused(
        change(json("Foo.java", "", "        return a - b;", "        return a + b;")),
        FailureReason.ANCHOR_NOT_FOUND);
  }

  @Test
  void anEditThatMatchesNoPlaceIsRefused() throws Exception {
    assertRefused(
        change(blocks("        return a * b;", "        return a + b;")),
        FailureReason.ANCHOR_NOT_FOUND);
    assertRefused(
        change(json("Foo.java", "", "        return a * b;", "        return a + b;")),
        FailureReason.ANCHOR_NOT_FOUND);
  }

  @Test
  void anAnchorThatIsNotInTheCodeOrIsNotUniqueIsRefused() throws Exception {
    AiResult missing =
        change(
            json(
                "Foo.java",
                "int mul(int a, int b) {",
                "        return a - b;",
                "        return 0;"));
    assertRefused(missing, FailureReason.ANCHOR_NOT_FOUND);
    assertTrue(missing.note().contains("anchor"), missing.note());
    AiResult twice =
        change(json("Foo.java", "return a - b;", "        return a - b;", "        return 0;"));
    assertRefused(twice, FailureReason.ANCHOR_NOT_FOUND);
    assertTrue(twice.note().contains("2 times"), twice.note());
  }

  @Test
  void anAnchorThatPointsAtOneMethodWhileTheLinesAreInTheOtherIsRefused() throws Exception {
    // the old lines exist, but not inside the method the anchor names: not guessed at
    String code = "class Foo {\n  void a() {\n    x();\n  }\n  void b() {\n    y();\n  }\n}\n";
    AiException ex =
        assertThrows(
            AiException.class,
            () ->
                EditBlocks.apply(
                    code, List.of(new EditBlocks.Edit("    y();", "    z();", "void a() {"))));
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, ex.reason());
  }

  @Test
  void jsonForAnotherFileIsRefused() throws Exception {
    AiResult r = change(json("Bar.java", "", "        return a - b;", "        return a + b;"));
    assertRefused(r, FailureReason.WRONG_FILE);
    assertTrue(r.note().contains("Bar.java"), r.note());
  }

  @Test
  void anEmptySearchIsRefused() throws Exception {
    assertRefused(change(blocks("", "int x;")), FailureReason.UNPARSEABLE);
    assertRefused(change(json("Foo.java", "", "", "int x;")), FailureReason.UNPARSEABLE);
  }

  @Test
  void aCutOffReplyProposesNothing() throws Exception {
    provider.truncated = true;
    AiResult text =
        change(
            blocks(
                "        return a - b;\n    }\n\n    int sub(int a, int b) {",
                "        return a +"));
    assertNull(text.proposal());
    assertEquals(FailureReason.TRUNCATED, text.failure());
    String whole =
        json(
            "Foo.java",
            "int add(int a, int b) {",
            "        return a - b;",
            "        return a + b;");
    provider.truncated = false;
    AiResult cut = change(whole.substring(0, whole.length() - 10));
    assertNull(cut.proposal());
    assertEquals(FailureReason.TRUNCATED, cut.failure());
  }

  @Test
  void aMarkerLeftOutOrMergedIntoCodeProposesNothing() throws Exception {
    AiResult noDivider = change("<<<<<<< SEARCH\n        return a - b;\n>>>>>>> REPLACE");
    assertNull(noDivider.proposal());
    assertNotNull(noDivider.failure());
    AiResult noEnd =
        change("<<<<<<< SEARCH\n        return a - b;\n=======\n        return a + b;\n");
    assertNull(noEnd.proposal());
    assertNotNull(noEnd.failure());
  }

  @Test
  void aReplyWithNoEditsIsAnAnswerNotAChange() throws Exception {
    AiResult r = change("{\"explanation\":\"Nothing to change.\",\"edits\":[]}");
    assertNull(r.proposal());
    assertEquals(FailureReason.NO_EDIT_BLOCK, r.failure());
  }

  @Test
  void aSecondEditThatFailsDiscardsTheFirst() throws Exception {
    String reply =
        blocks(
                "    int add(int a, int b) {\n        return a - b;",
                "    int add(int a, int b) {\n        return a + b;")
            + "\n"
            + blocks("        return a * b;", "        return 0;");
    AiResult r = change(reply);
    assertRefused(r, FailureReason.ANCHOR_NOT_FOUND);
    assertTrue(r.note().contains("Edit 2"), r.note());
  }
}
