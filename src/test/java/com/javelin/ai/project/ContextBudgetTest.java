package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ContextBudgetTest {
  @Test
  void theWindowThatCountsIsTheSmallerOfRuntimeAndModelMaximum() {
    assertEquals(8_000, new ContextBudget(8_000, 32_000, 1_000).effectiveWindow());
    assertEquals(16_000, new ContextBudget(262_144, 16_000, 1_000).effectiveWindow());
    assertEquals(16_000, new ContextBudget(0, 16_000, 1_000).effectiveWindow());
  }

  @Test
  void roomForFilesIsWindowMinusReserveMinusFixedPartsAndNeverNegative() {
    ContextBudget b = new ContextBudget(0, 10_000, 2_000);
    assertEquals(10_000 - 2_000 - 500 - 32, b.availableForFiles(500));
    assertEquals(0, b.availableForFiles(50_000));
  }

  @Test
  void estimatesAreOnTheHighSideAndEmptyTextCostsNothing() {
    assertEquals(0, ContextBudget.estimateTokens(""));
    assertEquals(0, ContextBudget.estimateTokens(null));
    assertEquals(4, ContextBudget.estimateTokens("x".repeat(10)));
    assertEquals(1, ContextBudget.estimateTokens("x"));
  }

  @Test
  void aRuntimeWindowAboveTheModelMaximumIsReported() {
    List<String> w = new ContextBudget(8_192, 32_768, 1_000).warnings();
    assertEquals(1, w.size());
    assertTrue(w.get(0).contains("32,768") && w.get(0).contains("8,192"), w.get(0));
    assertTrue(new ContextBudget(0, 32_768, 1_000).warnings().isEmpty());
  }

  @Test
  void aReserveThatFillsTheWindowIsReported() {
    assertEquals(1, new ContextBudget(0, 4_000, 4_000).warnings().size());
  }

  @Test
  void usageSaysHowMuchIsUsedAndHowMuchIsFree() {
    ContextBudget.Usage u = new ContextBudget(0, 10_000, 2_000).usage(4_000);
    assertEquals(4_000, u.freeTokens());
    assertEquals(50, u.percent());
    assertTrue(u.describe().contains("50%") && u.describe().contains("estimates"), u.describe());
    assertEquals(100, new ContextBudget(0, 10_000, 2_000).usage(99_000).percent());
    assertEquals(0, new ContextBudget(0, 10_000, 2_000).usage(99_000).freeTokens());
  }

  @Test
  void impossibleNumbersAreRefused() {
    assertThrows(IllegalArgumentException.class, () -> new ContextBudget(0, 0, 0));
    assertThrows(IllegalArgumentException.class, () -> new ContextBudget(0, 100, -1));
  }

  @Test
  void theEstimateFollowsTheCharactersPerTokenOfTheBudget() {
    ContextBudget standard = new ContextBudget(0, 10_000, 1_000);
    assertEquals(ContextBudget.CHARS_PER_TOKEN, standard.charsPerToken());
    assertFalse(standard.isCalibrated());
    assertEquals(standard.estimate("x".repeat(30)), ContextBudget.estimateTokens("x".repeat(30)));
    ContextBudget adjusted = standard.withCharsPerToken(4.0);
    assertTrue(adjusted.isCalibrated());
    assertEquals(8, adjusted.estimate("x".repeat(30)));
    assertEquals(0, adjusted.estimate(""));
    assertEquals(0, adjusted.estimate(null));
    assertThrows(IllegalArgumentException.class, () -> standard.withCharsPerToken(0));
  }
}
