package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.ai.AiUsage;
import org.junit.jupiter.api.Test;

class TokenCalibrationTest {
  private static final double STANDARD = ContextBudget.CHARS_PER_TOKEN;

  /** An answer for a request of {@code chars} characters whose text cost {@code textTokens}. */
  private static AiUsage answer(int textTokens) {
    return new AiUsage(textTokens + 2 * ContextBudget.MESSAGE_OVERHEAD_TOKENS, 10);
  }

  @Test
  void theStandardEstimateIsUsedUntilThereAreEnoughAnswers() {
    TokenCalibration c = new TokenCalibration();
    assertEquals(STANDARD, c.charsPerToken("m"));
    assertTrue(c.record("m", 4_000, 2, answer(1_000)));
    assertEquals(STANDARD, c.charsPerToken("m"), "one answer is not enough");
    assertEquals("", c.describe("m"));
    c.record("m", 4_000, 2, answer(1_000));
    assertEquals(4.0 * TokenCalibration.SAFETY, c.charsPerToken("m"), 1e-9);
  }

  @Test
  void theLowestRecentRatioWinsSoOnePoeticRequestDoesNotMakeCodeLookCheap() {
    TokenCalibration c = new TokenCalibration();
    c.record("m", 4_000, 2, answer(1_000)); // 4.0
    c.record("m", 3_600, 2, answer(1_200)); // 3.0: code
    c.record("m", 5_000, 2, answer(1_000)); // 5.0
    assertEquals(3.0 * TokenCalibration.SAFETY, c.charsPerToken("m"), 1e-9);
  }

  @Test
  void onlyTheLastEightAnswersCount() {
    TokenCalibration c = new TokenCalibration();
    c.record("m", 3_000, 2, answer(1_000)); // 3.0, will be forgotten
    for (int i = 0; i < TokenCalibration.WINDOW; i++) c.record("m", 4_000, 2, answer(1_000));
    assertEquals(TokenCalibration.WINDOW, c.samples("m"));
    assertEquals(4.0 * TokenCalibration.SAFETY, c.charsPerToken("m"), 1e-9);
  }

  @Test
  void aCountThatCannotBeAWholePromptIsIgnored() {
    TokenCalibration c = new TokenCalibration();
    assertFalse(
        c.record("m", 40_000, 2, answer(100)), "400 chars per token: a cached prompt start");
    assertFalse(c.record("m", 100, 2, answer(500)), "less than one char per token");
    assertFalse(c.record("m", 4_000, 2, new AiUsage(10, 10)), "fewer tokens than the framing");
    assertFalse(c.record("m", 4_000, 2, AiUsage.UNKNOWN));
    assertFalse(c.record("m", 0, 2, answer(10)));
    assertFalse(c.record(null, 4_000, 2, answer(1_000)));
    assertEquals(0, c.samples("m"));
  }

  @Test
  void theFigureStaysWithinHardLimits() {
    TokenCalibration low = new TokenCalibration();
    for (int i = 0; i < 3; i++) low.record("m", 1_100, 2, answer(1_000)); // 1.1
    assertEquals(TokenCalibration.LOWEST, low.charsPerToken("m"));
    TokenCalibration high = new TokenCalibration();
    for (int i = 0; i < 3; i++) high.record("m", 5_900, 2, answer(1_000)); // 5.9
    assertEquals(TokenCalibration.HIGHEST, high.charsPerToken("m"));
  }

  @Test
  void modelsAreCalibratedApart() {
    TokenCalibration c = new TokenCalibration();
    c.record("a", 4_000, 2, answer(1_000));
    c.record("a", 4_000, 2, answer(1_000));
    assertEquals(STANDARD, c.charsPerToken("b"));
    assertTrue(c.charsPerToken("a") > STANDARD);
  }

  @Test
  void aCalibratedBudgetEstimatesFewerTokensAndSaysSo() {
    TokenCalibration c = new TokenCalibration();
    c.record("m", 4_000, 2, answer(1_000));
    c.record("m", 4_000, 2, answer(1_000));
    ContextBudget plain = new ContextBudget(0, 32_768, 4_096);
    ContextBudget adjusted = c.apply(plain, "m");
    assertTrue(adjusted.isCalibrated());
    assertTrue(adjusted.estimate("x".repeat(3_800)) < plain.estimate("x".repeat(3_800)));
    assertTrue(
        adjusted
            .usage(100)
            .describe()
            .contains("adjusted to this model at 3.8 characters per token"));
    assertFalse(plain.usage(100).describe().contains("adjusted"));
    assertEquals(plain, c.apply(plain, "unknown-model"));
    assertTrue(c.describe("m").contains("3.8 characters per token from its last 2 answers"));
  }
}
