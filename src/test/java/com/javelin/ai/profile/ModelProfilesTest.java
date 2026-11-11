package com.javelin.ai.profile;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.ai.AiConfig;
import com.javelin.ai.plan.PlanConfig;
import com.javelin.ai.symbols.SymbolBudget;
import com.javelin.ai.symbols.SymbolConfig;
import com.javelin.ai.verify.VerifyConfig;
import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelProfilesTest {
  @TempDir Path dir;
  private Settings settings;

  @BeforeEach
  void setUp() {
    settings = new Settings(dir.resolve("settings.conf"));
  }

  private void set(String key, String value) {
    settings.put(Scope.USER, key, value);
  }

  @Test
  void theSmallProfileIsExactlyWhatTheSettingsDefaultedToBefore() {
    ModelProfile small = ModelProfile.SMALL;
    assertEquals(VerifyConfig.defaults().retries(), small.retries());
    assertEquals(PlanConfig.defaults().maxSteps(), small.maxSteps());
    assertEquals(SymbolConfig.defaults().budget(), small.symbolBudget());
    assertEquals(AiConfig.DEFAULT_STRUCTURED_EDITS, small.prefersStructuredOutput());
    assertEquals(1, small.editsPerProposal());
    assertEquals(ModelProfile.EditFormat.SEARCH_REPLACE, small.editFormat());
    assertEquals(ModelProfile.Planning.EXTERNAL_OR_MANUAL, small.planning());
  }

  @Test
  void theFrontierProfileIsTheTableColumn() {
    ModelProfile f = ModelProfile.FRONTIER;
    assertEquals(1, f.retries());
    assertEquals(8, f.maxSteps());
    assertEquals(SymbolBudget.FRONTIER, f.symbolBudget());
    assertTrue(f.prefersStructuredOutput());
    assertEquals(ModelProfile.SEVERAL, f.editsPerProposal());
    assertEquals(ModelProfile.Planning.MODEL, f.planning());
  }

  @Test
  void nothingSetMeansTheSuggestionFromWhereTheModelRuns() {
    assertSame(ModelProfile.SMALL, ModelProfiles.resolve(settings, true));
    assertSame(ModelProfile.FRONTIER, ModelProfiles.resolve(settings, false));
  }

  @Test
  void theSettingOverridesTheSuggestionInBothDirections() {
    set(ModelProfiles.PROFILE, "frontier");
    assertSame(ModelProfile.FRONTIER, ModelProfiles.resolve(settings, true));
    set(ModelProfiles.PROFILE, " SMALL ");
    assertSame(ModelProfile.SMALL, ModelProfiles.resolve(settings, false));
  }

  @Test
  void anUnknownValueFallsBackToTheSuggestionAndIsReported() {
    set(ModelProfiles.PROFILE, "huge");
    assertSame(ModelProfile.SMALL, ModelProfiles.resolve(settings, true));
    assertEquals(1, ModelProfiles.validate(settings).size());
  }

  @Test
  void aCustomProfileStartsFromSmallAndTakesItsNumbersFromTheSettings() {
    set(ModelProfiles.PROFILE, "custom");
    assertEquals(ModelProfile.SMALL.retries(), ModelProfiles.resolve(settings, true).retries());
    set(ModelProfiles.CUSTOM_RETRIES, "4");
    set(ModelProfiles.CUSTOM_STEPS, "6");
    set(ModelProfiles.CUSTOM_STRUCTURED, "true");
    set(ModelProfiles.CUSTOM_SYMBOLS, "frontier");
    ModelProfile custom = ModelProfiles.resolve(settings, true);
    assertEquals("custom", custom.id());
    assertEquals(4, custom.retries());
    assertEquals(6, custom.maxSteps());
    assertTrue(custom.prefersStructuredOutput());
    assertEquals(SymbolBudget.FRONTIER, custom.symbolBudget());
  }

  @Test
  void customNumbersAreClampedAndBadOnesAreReported() {
    set(ModelProfiles.PROFILE, "custom");
    set(ModelProfiles.CUSTOM_RETRIES, "99");
    set(ModelProfiles.CUSTOM_STEPS, "0");
    ModelProfile custom = ModelProfiles.resolve(settings, true);
    assertEquals(VerifyConfig.MAX_RETRIES, custom.retries());
    assertEquals(1, custom.maxSteps());
    set(ModelProfiles.CUSTOM_STRUCTURED, "sometimes");
    set(ModelProfiles.CUSTOM_SYMBOLS, "medium");
    assertEquals(4, ModelProfiles.validate(settings).size());
  }

  @Test
  void theProfileSuppliesTheDefaultsAndAnExplicitSettingWins() {
    ModelProfile frontier = ModelProfile.FRONTIER;
    assertEquals(1, VerifyConfig.from(settings, frontier.retries()).retries());
    assertEquals(8, PlanConfig.from(settings, frontier.maxSteps()).maxSteps());
    assertEquals(
        SymbolBudget.FRONTIER, SymbolConfig.from(settings, frontier.symbolBudget()).budget());
    set(VerifyConfig.RETRIES, "3");
    set(PlanConfig.MAX_STEPS, "2");
    set(SymbolConfig.PROFILE, "small");
    assertEquals(3, VerifyConfig.from(settings, frontier.retries()).retries());
    assertEquals(2, PlanConfig.from(settings, frontier.maxSteps()).maxSteps());
    assertEquals(SymbolBudget.SMALL, SymbolConfig.from(settings, frontier.symbolBudget()).budget());
  }

  @Test
  void theOldEntryPointsStillGiveTheSmallDefaults() {
    assertEquals(VerifyConfig.defaults(), VerifyConfig.from(settings));
    assertEquals(PlanConfig.defaults(), PlanConfig.from(settings));
    assertEquals(SymbolConfig.defaults(), SymbolConfig.from(settings));
  }

  @Test
  void structuredEditsFollowTheProfileUnlessTheSettingIsThere() {
    AiConfig base = AiConfig.from(settings);
    assertFalse(ModelProfiles.apply(base, settings, ModelProfile.SMALL).structuredEdits());
    assertTrue(ModelProfiles.apply(base, settings, ModelProfile.FRONTIER).structuredEdits());
    set(AiConfig.STRUCTURED_EDITS, "false");
    assertFalse(
        ModelProfiles.apply(AiConfig.from(settings), settings, ModelProfile.FRONTIER)
            .structuredEdits());
  }

  @Test
  void aSmallProfileLeavesTheConfigurationExactlyAsItWas() {
    AiConfig base = AiConfig.from(settings).withModel("qwen3.5:9b");
    assertEquals(base, ModelProfiles.apply(base, settings, ModelProfile.SMALL));
  }

  @Test
  void parameterCountsAreReadFromTagsAndOnlyDescribeTheSuggestion() {
    assertEquals(9.0, ModelProfiles.parameterBillions("qwen3.5:9b").orElseThrow());
    assertEquals(7.0, ModelProfiles.parameterBillions("qwen2.5-coder:7b-instruct").orElseThrow());
    assertEquals(0.5, ModelProfiles.parameterBillions("qwen2.5:0.5b").orElseThrow());
    assertTrue(ModelProfiles.parameterBillions("mixtral:8x7b").isEmpty());
    assertTrue(ModelProfiles.parameterBillions("llama3").isEmpty());
    assertTrue(ModelProfiles.parameterBillions(null).isEmpty());
    assertTrue(
        ModelProfiles.describe(settings, "qwen3.5:9b", true)
            .contains("small (suggested: qwen3.5:9b (about 9B parameters) runs on this computer)"));
    assertTrue(ModelProfiles.describe(settings, "m", false).startsWith("frontier (suggested"));
    set(ModelProfiles.PROFILE, "small");
    assertEquals("small (set by ai.profile)", ModelProfiles.describe(settings, "m", false));
  }

  @Test
  void aProfileNeverNamesAProvider() {
    List<String> text =
        List.of(
            ModelProfile.SMALL.toString().toLowerCase(),
            ModelProfile.FRONTIER.toString().toLowerCase());
    for (String t : text) {
      assertFalse(t.contains("ollama"));
      assertFalse(t.contains("anthropic"));
    }
  }
}
