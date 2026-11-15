package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A reply that runs into the fence of the sent text, or has no divider, is told apart from a cut-off one. */
class EditFenceTest {
  private static final String SEARCH_ONLY =
      "<<<<<<< SEARCH\n        pygame.display.flip()\n\n        self.clock.tick(FPS)\n"
          + "===== END FILE: src/snake/game.py =====";

  @Test
  void aBlockWithNoDividerThatRunsIntoTheFenceIsUnparseableNotCutOff() {
    AiException ex = assertThrows(AiException.class, () -> EditBlocks.parse(SEARCH_ONLY));
    assertEquals(FailureReason.UNPARSEABLE, ex.reason());
    assertTrue(ex.getMessage().contains("no ======= line"));
  }

  @Test
  void aFenceWrittenAboveTheDividerIsIgnored() throws AiException {
    String reply =
        "Add it.\n<<<<<<< SEARCH\n    def tick(self):\n        self.clock.tick(FPS)\n"
            + "===== END FILE: src/snake/game.py =====\n=======\n"
            + "    def tick(self):\n        self.clock.tick(FPS)\n\n    def reset_game(self):\n        pass\n"
            + "===== END FILE: src/snake/game.py =====\n";
    EditBlocks.Parsed p = EditBlocks.parse(reply);
    assertEquals(1, p.edits().size());
    assertEquals("    def tick(self):\n        self.clock.tick(FPS)", p.edits().get(0).search());
    assertTrue(p.edits().get(0).replace().contains("def reset_game"));
    assertFalse(p.edits().get(0).replace().contains("END FILE"));
    assertEquals("def tick(self):\n        self.clock.tick(FPS)\n\n    def reset_game(self):\n        pass",
        EditBlocks.apply("    def tick(self):\n        self.clock.tick(FPS)", p.edits()).strip());
  }

  @Test
  void aBlockWithADividerIsClosedByTheFence() throws AiException {
    String reply =
        "<<<<<<< SEARCH\na\n=======\nb\n===== END FILE: x/Y.py =====\n";
    EditBlocks.Parsed p = EditBlocks.parse(reply);
    assertEquals(1, p.edits().size());
    assertEquals("a", p.edits().get(0).search());
    assertEquals("b", p.edits().get(0).replace());
  }

  @Test
  void aBlockWithADividerIsClosedByTheNextBlock() throws AiException {
    String reply =
        "<<<<<<< SEARCH\na\n=======\nb\n<<<<<<< SEARCH\nc\n=======\nd\n>>>>>>> REPLACE\n";
    assertEquals(2, EditBlocks.parse(reply).edits().size());
  }

  @Test
  void aNextBlockBeforeTheDividerIsRefused() {
    AiException ex =
        assertThrows(
            AiException.class,
            () -> EditBlocks.parse("<<<<<<< SEARCH\na\n<<<<<<< SEARCH\nc\n=======\nd\n>>>>>>> REPLACE"));
    assertEquals(FailureReason.UNPARSEABLE, ex.reason());
  }

  @Test
  void aReplyThatEndsInsideABlockIsStillTruncated() {
    AiException ex =
        assertThrows(AiException.class, () -> EditBlocks.parse("<<<<<<< SEARCH\na\n=======\nb"));
    assertEquals(FailureReason.TRUNCATED, ex.reason());
  }

  @Test
  void fenceLinesOutsideBlocksAreNotExplanation() throws AiException {
    String reply =
        "Done.\n===== BEGIN CODE =====\n<<<<<<< SEARCH\na\n=======\nb\n>>>>>>> REPLACE\n===== END CODE =====";
    assertEquals("Done.", EditBlocks.parse(reply).explanation());
  }

  @Test
  void repairIsWorthAskingOnlyForBrokenEditBlocks() {
    assertTrue(EditRepair.worthAsking(FailureReason.UNPARSEABLE, SEARCH_ONLY));
    assertTrue(EditRepair.worthAsking(FailureReason.ANCHOR_NOT_FOUND, SEARCH_ONLY));
    assertFalse(EditRepair.worthAsking(FailureReason.WRONG_FILE, SEARCH_ONLY));
    assertFalse(EditRepair.worthAsking(FailureReason.UNPARSEABLE, "just words"));
    assertFalse(EditRepair.worthAsking(null, SEARCH_ONLY));
  }

  @Test
  void theSecondRequestQuotesTheAnswerAndGrowsAChosenWindow() {
    AiRequest first = new AiRequest("m", "sys", "do it", 8192);
    AiRequest again =
        EditRepair.again(first, SEARCH_ONLY, "An edit block has no ======= line. Its text is in the AI panel.");
    assertTrue(again.prompt().startsWith("do it"));
    assertTrue(again.prompt().contains("self.clock.tick(FPS)"));
    assertTrue(again.prompt().contains("has no ======= line."));
    assertFalse(again.prompt().contains("AI panel"));
    assertTrue(again.contextTokens() > 8192);
    assertEquals(0, EditRepair.again(new AiRequest("m", "s", "p"), SEARCH_ONLY, "x").contextTokens());
    assertTrue(EditRepair.clip("x".repeat(20000)).length() < 6100);
    assertEquals(List.of(), List.of());
  }
}
