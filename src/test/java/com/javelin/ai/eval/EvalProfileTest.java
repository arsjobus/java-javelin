package com.javelin.ai.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.ai.AiConfig;
import com.javelin.ai.project.ContextMode;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class EvalProfileTest {
  @Test
  void theSmallProfileIsTheProductsOwnSettingsForTheNamedModel() {
    EvalProfile p = EvalProfile.resolve(Map.of("eval.model", " qwen3.5:9b ")::get);
    assertEquals("small", p.name());
    assertEquals("qwen3.5:9b", p.model());
    assertEquals("http://127.0.0.1:11434", p.endpoint());
    assertEquals(AiConfig.DEFAULT_THINK_LEVEL, p.thinkLevel());
    assertEquals(AiConfig.DEFAULT_MAX_CONTEXT_CHARS, p.maxContextChars());
    assertEquals(ContextMode.SMART, p.contextMode());
    AiConfig config = p.aiConfig();
    assertEquals("qwen3.5:9b", config.model());
    assertEquals("ollama", config.provider());
  }

  @Test
  void endpointThinkLevelAndTimeoutCanBeOverridden() {
    EvalProfile p =
        EvalProfile.resolve(
            Map.of(
                    "eval.model", "m",
                    "eval.profile", "small",
                    "eval.endpoint", "http://localhost:9999",
                    "eval.think", "none",
                    "eval.timeoutSeconds", "30")
                ::get);
    assertEquals("http://localhost:9999", p.endpoint());
    assertEquals("none", p.thinkLevel());
    assertEquals(Duration.ofSeconds(30), p.timeout());
  }

  @Test
  void aModelMustBeNamed() {
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> EvalProfile.resolve(k -> null));
    assertTrue(e.getMessage().contains("-Deval.model"));
    assertThrows(
        IllegalArgumentException.class, () -> EvalProfile.resolve(Map.of("eval.model", " ")::get));
  }

  @Test
  void thereIsNoFrontierProfileYetAndAskingForOneIsAnErrorNotAFallback() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () -> EvalProfile.resolve(Map.of("eval.model", "m", "eval.profile", "frontier")::get));
    assertTrue(e.getMessage().contains("only 'small'"));
  }

  @Test
  void editFormatsCanBeComparedWithoutChangingTheEditorsDefaults() {
    EvalProfile p = EvalProfile.resolve(Map.of("eval.model", "m")::get);
    assertFalse(p.structuredEdits());
    assertEquals(com.javelin.ai.AiConfig.DEFAULT_EDIT_BLOCK_MIN_LINES, p.editBlockMinLines());
    EvalProfile tuned =
        EvalProfile.resolve(
            Map.of("eval.model", "m", "eval.editBlockMinLines", "0", "eval.structured", "true")
                ::get);
    assertEquals(0, tuned.editBlockMinLines());
    assertTrue(tuned.structuredEdits());
    assertEquals(0, tuned.aiConfig().editBlockMinLines());
    assertTrue(tuned.aiConfig().structuredEdits());
  }
}
