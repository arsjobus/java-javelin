package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * A model that thinks writes its reasoning into the same window as its answer. The window leaves
 * room for both, so an edit is not cut off in the middle of its last block.
 */
class OllamaThinkingRoomTest {
  private static final AiRequest REQUEST = new AiRequest("m", "s", "x".repeat(21_000));

  @Test
  void theReserveGrowsWithTheThinkLevelAndIsZeroWithoutThinking() {
    assertEquals(0, OllamaProvider.thinkingReserve(null));
    assertEquals(0, OllamaProvider.thinkingReserve("none"));
    assertEquals(1024, OllamaProvider.thinkingReserve("low"));
    assertEquals(2048, OllamaProvider.thinkingReserve("Medium"));
    assertEquals(4096, OllamaProvider.thinkingReserve("high"));
    assertEquals(8192, OllamaProvider.thinkingReserve("xhigh"));
  }

  @Test
  void theWindowHasRoomForTheThinkingAsWellAsTheAnswer() {
    int plain = OllamaProvider.contextTokens(REQUEST, "none");
    int medium = OllamaProvider.contextTokens(REQUEST, "medium");
    int high = OllamaProvider.contextTokens(REQUEST, "high");
    assertEquals(OllamaProvider.contextTokens(REQUEST), plain);
    assertTrue(medium >= plain + 2048 - 1024, plain + " -> " + medium);
    assertTrue(high > medium, medium + " -> " + high);
  }

  @Test
  void theWindowIsStillCappedAndAnExplicitWindowIsUntouched() {
    AiRequest huge = new AiRequest("m", "s", "x".repeat(500_000));
    assertEquals(32768, OllamaProvider.contextTokens(huge, "xhigh"));
    assertEquals(
        16_384, OllamaProvider.contextTokens(new AiRequest("m", "s", "p", 16_384), "xhigh"));
  }

  @Test
  void ollamaIsTheProviderThatSizesItsWindow() throws Exception {
    assertTrue(
        new OllamaProvider("http://127.0.0.1:11434", java.time.Duration.ofSeconds(5))
            .sizesContextWindow());
    assertFalse(new FakeAiProvider().sizesContextWindow());
  }
}
