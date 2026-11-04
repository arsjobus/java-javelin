package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EditBlocksTest {
  private static final String CODE =
      "class Foo {\n    int add(int a, int b) {\n        return a - b;\n    }\n\n    int twice(int a) {\n        return a * 2;\n    }\n}\n";

  private static String reply(String search, String replace) {
    return "Fixed.\n<<<<<<< SEARCH\n" + search + "\n=======\n" + replace + "\n>>>>>>> REPLACE\n";
  }

  private static String applied(String reply, String original) throws Exception {
    EditBlocks.Parsed parsed = EditBlocks.parse(reply);
    return EditBlocks.apply(original, parsed.edits());
  }

  @Test
  void aBlockReplacesExactlyTheLinesItNames() throws Exception {
    String result = applied(reply("        return a - b;", "        return a + b;"), CODE);
    assertEquals(CODE.replace("a - b", "a + b"), result);
  }

  @Test
  void theTextAroundTheBlocksIsTheExplanationAndFencesAreDropped() throws Exception {
    EditBlocks.Parsed p =
        EditBlocks.parse(
            "Changed the sign.\n```\n<<<<<<< SEARCH\nx\n=======\ny\n>>>>>>> REPLACE\n```\nDone.");
    assertEquals("Changed the sign.\nDone.", p.explanation());
    assertEquals(1, p.edits().size());
    assertEquals("x", p.edits().get(0).search());
    assertEquals("y", p.edits().get(0).replace());
  }

  @Test
  void severalBlocksApplyOneAfterTheOther() throws Exception {
    String two =
        reply("        return a - b;", "        return a + b;")
            + reply("        return a * 2;", "        return a << 1;");
    assertEquals(CODE.replace("a - b", "a + b").replace("a * 2", "a << 1"), applied(two, CODE));
  }

  @Test
  void lostIndentationInTheSearchStillFindsTheLinesAndTheReplacementIsIndentedToMatch()
      throws Exception {
    String result = applied(reply("return a - b;", "return a + b;\n// checked"), CODE);
    assertEquals(
        CODE.replace("        return a - b;", "        return a + b;\n        // checked"), result);
  }

  @Test
  void spacesAtTheEndOfLinesDoNotMatter() throws Exception {
    assertEquals(
        CODE.replace("a - b", "a + b"),
        applied(reply("        return a - b;   ", "        return a + b;"), CODE));
  }

  @Test
  void anEmptyReplacementDeletesTheLines() throws Exception {
    String result =
        applied(
            "<<<<<<< SEARCH\n\n    int twice(int a) {\n        return a * 2;\n    }\n=======\n>>>>>>> REPLACE\n",
            CODE);
    assertEquals(
        "class Foo {\n    int add(int a, int b) {\n        return a - b;\n    }\n}\n", result);
  }

  @Test
  void linesThatAreNotInTheCodeAreRefusedAndNothingIsApplied() {
    AiException ex =
        assertThrows(AiException.class, () -> applied(reply("        return a / b;", "x"), CODE));
    assertEquals(AiException.Kind.BAD_RESPONSE, ex.kind());
    assertTrue(ex.getMessage().contains("not in the code"));
  }

  @Test
  void aBlockThatMatchesTwoPlacesIsRefusedBecauseItIsNotClearWhereItBelongs() {
    String twice = "void a() {\n    run();\n}\nvoid b() {\n    run();\n}\n";
    AiException ex =
        assertThrows(AiException.class, () -> applied(reply("    run();", "    go();"), twice));
    assertTrue(ex.getMessage().contains("2 places"));
  }

  @Test
  void oneBadBlockStopsEverythingSoNothingIsHalfApplied() {
    String two =
        reply("        return a - b;", "        return a + b;") + reply("nothing like this", "x");
    assertThrows(AiException.class, () -> applied(two, CODE));
  }

  @Test
  void anEmptySearchIsRefused() {
    assertThrows(
        AiException.class, () -> applied("<<<<<<< SEARCH\n\n=======\nx\n>>>>>>> REPLACE\n", CODE));
  }

  @Test
  void aReplyCutOffInsideABlockProposesNothing() {
    AiException ex =
        assertThrows(AiException.class, () -> EditBlocks.parse("<<<<<<< SEARCH\nx\n=======\ny\n"));
    assertTrue(ex.getMessage().contains("cut off"));
  }

  @Test
  void markersAreRecognisedLooselyAndPlainCodeIsNotTakenForBlocks() throws Exception {
    assertTrue(EditBlocks.looksLike("<<<<<<<<< search \nx\n=========\ny\n>>>>>>>>> replace"));
    assertFalse(EditBlocks.looksLike("int a = 1;\n=======\n"));
    assertEquals(
        "y",
        EditBlocks.parse("<<<<<<< SEARCH\nx\n==========\ny\n>>>>>>>>>> REPLACE")
            .edits()
            .get(0)
            .replace());
  }

  @Test
  void theTextEndsAsItDidAndWindowsLineEndingsAreAccepted() throws Exception {
    String result =
        applied(
            reply("        return a - b;", "        return a + b;"), CODE.replace("\n", "\r\n"));
    assertEquals(CODE.replace("a - b", "a + b"), result);
    assertTrue(result.endsWith("}\n"));
  }
}
