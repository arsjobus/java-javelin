package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * RANGE edit blocks: a run of lines is deleted or replaced by naming its first and last line, so a
 * small model never has to copy a hundred lines to remove them.
 */
class RangeEditsTest {
  private static final String METHODS =
      "    def handle_input(self):\n"
          + "        return True\n"
          + "\n"
          + "    def update(self):\n"
          + "        self.moved = True\n"
          + "\n"
          + "    def reset_game(self):\n"
          + "        self.score = 0\n"
          + "\n";

  /** A class whose methods were pasted in twice, as in the file the model could not fix. */
  private static final String DUPLICATED = "class Game:\n" + METHODS + METHODS;

  private static String range(String from, String to, String which, String replace) {
    return "Removed the copy.\n<<<<<<< RANGE\nFROM: "
        + from
        + "\nTO: "
        + to
        + "\n"
        + (which == null ? "" : "WHICH: " + which + "\n")
        + "=======\n"
        + replace
        + ">>>>>>> REPLACE\n";
  }

  private static String applied(String reply, String code) throws Exception {
    return EditBlocks.apply(code, EditBlocks.parse(reply, code).edits());
  }

  @Test
  void theSecondCopyIsDeletedByNamingOnlyItsFirstLineAndTheEndOfTheCode() throws Exception {
    String reply = range("    def handle_input(self):", "END OF CODE", "2", "");
    assertEquals("class Game:\n" + METHODS, applied(reply, DUPLICATED));
  }

  @Test
  void whichCanBeLastOrAWord() throws Exception {
    String expected = "class Game:\n" + METHODS;
    assertEquals(
        expected,
        applied(range("    def handle_input(self):", "END OF CODE", "last", ""), DUPLICATED));
    assertEquals(
        expected,
        applied(
            range("    def handle_input(self):", "END OF CODE", "the 2nd one", ""), DUPLICATED));
    assertEquals(
        expected,
        applied(range("    def handle_input(self):", "END OF CODE", "second", ""), DUPLICATED));
  }

  @Test
  void whichAlsoUnderstandsFirstAndThird() throws Exception {
    String thrice = "x\nm\nx\nm\nx\nm\n";
    assertEquals("m\nx\nm\nx\nm\n", applied(range("x", "x", "first", ""), thrice));
    assertEquals("x\nm\nx\nm\nm\n", applied(range("x", "x", "third", ""), thrice));
  }

  @Test
  void aFirstLineInSeveralPlacesWithoutWhichIsRefusedWithTheLinesAndTheFix() throws Exception {
    AiException ex =
        assertThrows(
            AiException.class,
            () ->
                applied(range("    def handle_input(self):", "END OF CODE", null, ""), DUPLICATED));
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, ex.reason());
    assertTrue(ex.getMessage().contains("2 places"), ex.getMessage());
    assertTrue(ex.getMessage().contains("lines 2, 11"), ex.getMessage());
    assertTrue(ex.getMessage().contains("WHICH: 2"), ex.getMessage());
  }

  @Test
  void aWhichThatIsOutOfRangeOrUnreadableIsRefused() {
    AiException far =
        assertThrows(
            AiException.class,
            () ->
                applied(range("    def handle_input(self):", "END OF CODE", "5", ""), DUPLICATED));
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, far.reason());
    AiException odd =
        assertThrows(
            AiException.class,
            () ->
                applied(
                    range("    def handle_input(self):", "END OF CODE", "dunno", ""), DUPLICATED));
    assertEquals(FailureReason.UNPARSEABLE, odd.reason());
  }

  @Test
  void theLastLineIsTheFirstOneAtOrAfterTheFirstLine() throws Exception {
    String reply =
        range(
            "    def update(self):",
            "        self.moved = True",
            null,
            "    def update(self):\n        pass\n");
    String code = "class Game:\n" + METHODS;
    assertEquals(code.replace("        self.moved = True", "        pass"), applied(reply, code));
  }

  @Test
  void aRangeCanReplaceALongRunWithNewLines() throws Exception {
    String reply =
        range(
            "    def update(self):",
            "        self.score = 0",
            null,
            "    def update(self):\n        self.score = 1\n");
    String code = "class Game:\n" + METHODS;
    assertEquals(
        "class Game:\n"
            + "    def handle_input(self):\n"
            + "        return True\n"
            + "\n"
            + "    def update(self):\n"
            + "        self.score = 1\n"
            + "\n",
        applied(reply, code));
  }

  @Test
  void startOfCodeAndEndOfCodeStandForTheEnds() throws Exception {
    assertEquals("x\n", applied(range("START OF CODE", "END OF CODE", null, "x\n"), "a\nb\nc\n"));
    assertEquals("c\n", applied(range("START OF CODE", "b", null, ""), "a\nb\nc\n"));
  }

  @Test
  void aSingleLineRangeIsAllowed() throws Exception {
    assertEquals("a\nB\nc\n", applied(range("b", "b", null, "B\n"), "a\nb\nc\n"));
  }

  @Test
  void aFirstLineThatIsNotInTheCodeIsRefused() {
    AiException ex =
        assertThrows(
            AiException.class,
            () -> applied(range("    def invented(self):", "END OF CODE", null, ""), DUPLICATED));
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, ex.reason());
    assertTrue(ex.getMessage().contains("invented"));
  }

  @Test
  void aLastLineThatIsNotAfterTheFirstLineIsRefused() {
    String code = "class Game:\n" + METHODS;
    AiException ex =
        assertThrows(
            AiException.class,
            () ->
                applied(range("    def reset_game(self):", "        return True", null, ""), code));
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, ex.reason());
    assertTrue(ex.getMessage().contains("not in the code after"), ex.getMessage());
  }

  @Test
  void lostIndentationOnTheFirstLineIsFoundAndTheReplacementIsIndented() throws Exception {
    String code = "class Game:\n" + METHODS;
    EditBlocks.Applied result =
        EditBlocks.applyWithAnchors(
            code,
            EditBlocks.parse(
                    range(
                        "def update(self):",
                        "self.moved = True",
                        null,
                        "def update(self):\npass\n"))
                .edits());
    assertEquals(AnchorRung.INDENTATION, result.anchors().get(0).rung());
    assertTrue(result.text().contains("    def update(self):\n    pass\n"), result.text());
  }

  @Test
  void aRangeAndASearchBlockInOneReplyBothApplyInOrder() throws Exception {
    String reply =
        "Two changes.\n"
            + "<<<<<<< RANGE\n"
            + "FROM: reset_game_marker\n"
            + "TO: reset_game_marker\n"
            + "=======\n"
            + ">>>>>>> REPLACE\n"
            + "<<<<<<< SEARCH\n"
            + "        return True\n"
            + "=======\n"
            + "        return False\n"
            + ">>>>>>> REPLACE\n";
    String code = "class Game:\n    def f(self):\n        return True\nreset_game_marker\n";
    assertEquals("class Game:\n    def f(self):\n        return False\n", applied(reply, code));
  }

  @Test
  void theSameRangeWrittenTwiceIsMadeOnce() throws Exception {
    String block = "<<<<<<< RANGE\nFROM: b\nTO: b\n=======\n>>>>>>> REPLACE\n";
    String code = "a\nb\nc\n";
    EditBlocks.Applied result =
        EditBlocks.applyWithAnchors(code, EditBlocks.parse(block + block).edits());
    assertEquals("a\nc\n", result.text());
    assertEquals(AnchorRung.ALREADY_APPLIED, result.anchors().get(1).rung());
  }

  @Test
  void aRangeBlockNeedsBothEnds() {
    AiException noTo =
        assertThrows(
            AiException.class,
            () -> EditBlocks.parse("<<<<<<< RANGE\nFROM: a\n=======\n>>>>>>> REPLACE\n"));
    assertEquals(FailureReason.UNPARSEABLE, noTo.reason());
    AiException stray =
        assertThrows(
            AiException.class,
            () ->
                EditBlocks.parse(
                    "<<<<<<< RANGE\nFROM: a\nTO: b\nsome code\n=======\n>>>>>>> REPLACE\n"));
    assertEquals(FailureReason.UNPARSEABLE, stray.reason());
  }

  @Test
  void aRangeBlockCutOffInTheMiddleProposesNothing() {
    AiException ex =
        assertThrows(
            AiException.class, () -> EditBlocks.parse("<<<<<<< RANGE\nFROM: a\nTO: b\n=====\n"));
    assertEquals(FailureReason.TRUNCATED, ex.reason());
  }

  @Test
  void aRangeReplyIsUnderstoodWhateverTheProposalFormatWas() throws Exception {
    ChangeReply.Interpreted got =
        ChangeReply.interpret(
            range("    def handle_input(self):", "END OF CODE", "2", ""),
            DUPLICATED,
            "game.py",
            "python");
    assertEquals(ChangeReply.Format.EDIT_BLOCKS, got.format());
    assertEquals("class Game:\n" + METHODS, got.code());
    assertTrue(EditBlocks.looksLike("<<<<<<< RANGE\n"));
  }

  @Test
  void aRangeThatCoversAllOfTheCodeIsFlaggedAsMuchShorter() throws Exception {
    StringBuilder code = new StringBuilder();
    for (int i = 0; i < 30; i++) code.append("line ").append(i).append('\n');
    ChangeReply.Interpreted got =
        ChangeReply.interpret(
            range("line 3", "END OF CODE", null, ""), code.toString(), "a.txt", null);
    List<Finding> checks = ChangeChecker.check(null, code.toString(), got.code());
    assertTrue(checks.stream().anyMatch(f -> f.message().contains("much shorter")));
  }
}
