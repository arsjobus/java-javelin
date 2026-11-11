package com.javelin.ai.symbols;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SymbolConfigTest {
  @TempDir Path dir;

  private Settings settings() {
    return new Settings(dir.resolve("settings.conf"));
  }

  @Test
  void symbolContextIsOffByDefaultWithTheSmallProfile() {
    SymbolConfig c = SymbolConfig.from(settings());
    assertFalse(c.enabled());
    assertEquals(SymbolBudget.SMALL, c.budget());
    assertEquals(SymbolConfig.defaults(), c);
    assertTrue(SymbolConfig.validate(settings()).isEmpty());
  }

  @Test
  void theSettingsTurnItOnAndPickTheProfile() {
    Settings s = settings();
    s.put(Scope.USER, SymbolConfig.ENABLED, "true");
    s.put(Scope.USER, SymbolConfig.PROFILE, "Frontier");
    SymbolConfig c = SymbolConfig.from(s);
    assertTrue(c.enabled());
    assertEquals(SymbolBudget.FRONTIER, c.budget());
    assertFalse(c.withEnabled(false).enabled());
  }

  @Test
  void aBadValueFallsBackAndIsReported() {
    Settings s = settings();
    s.put(Scope.USER, SymbolConfig.ENABLED, "maybe");
    s.put(Scope.USER, SymbolConfig.PROFILE, "huge");
    SymbolConfig c = SymbolConfig.from(s);
    assertFalse(c.enabled());
    assertEquals(SymbolBudget.SMALL, c.budget());
    assertEquals(2, SymbolConfig.validate(s).size());
  }

  @Test
  void theProfilesGrowFromSmallToFrontierInEveryLimit() {
    SymbolBudget s = SymbolBudget.SMALL;
    SymbolBudget f = SymbolBudget.FRONTIER;
    assertTrue(s.maxTargets() < f.maxTargets());
    assertTrue(s.maxBodyChars() < f.maxBodyChars());
    assertTrue(s.maxUsedTypes() < f.maxUsedTypes());
    assertTrue(s.maxSignaturesPerType() < f.maxSignaturesPerType());
    assertTrue(s.maxCallers() < f.maxCallers());
    assertTrue(s.maxCallerBodyChars() < f.maxCallerBodyChars());
    assertTrue(s.maxTokens() < f.maxTokens());
    assertTrue(s.wholeFileTokens() < f.wholeFileTokens());
    assertEquals(Optional.empty(), SymbolBudget.named(null));
    assertEquals(Optional.of(s), SymbolBudget.named(" SMALL "));
  }
}
