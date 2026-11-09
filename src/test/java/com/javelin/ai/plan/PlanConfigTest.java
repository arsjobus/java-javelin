package com.javelin.ai.plan;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanConfigTest {
  @TempDir Path dir;

  private Settings settings() {
    return new Settings(dir.resolve("settings.conf"));
  }

  @Test
  void planningIsOffUntilTurnedOnAndHoldsTheSmallProfilesLimit() {
    PlanConfig c = PlanConfig.from(settings());
    assertFalse(c.enabled());
    assertEquals(PlanConfig.SMALL_STEPS, c.maxSteps());
    assertEquals(3, PlanConfig.SMALL_STEPS);
    assertEquals(8, PlanConfig.FRONTIER_STEPS);
    assertEquals(PlanConfig.defaults(), c);
  }

  @Test
  void theSettingsAreReadAndTheLimitIsClamped() {
    Settings s = settings();
    s.put(Scope.USER, PlanConfig.ENABLED, "true");
    s.put(Scope.USER, PlanConfig.MAX_STEPS, "5");
    assertEquals(new PlanConfig(true, 5), PlanConfig.from(s));
    s.put(Scope.USER, PlanConfig.MAX_STEPS, "99");
    assertEquals(8, PlanConfig.from(s).maxSteps());
    s.put(Scope.USER, PlanConfig.MAX_STEPS, "0");
    assertEquals(1, PlanConfig.from(s).maxSteps());
    s.put(Scope.USER, PlanConfig.MAX_STEPS, "many");
    assertEquals(3, PlanConfig.from(s).maxSteps());
  }

  @Test
  void badValuesAreReported() {
    Settings s = settings();
    assertTrue(PlanConfig.validate(s).isEmpty());
    s.put(Scope.USER, PlanConfig.MAX_STEPS, "12");
    s.put(Scope.USER, PlanConfig.ENABLED, "maybe");
    var problems = PlanConfig.validate(s);
    assertEquals(2, problems.size(), problems.toString());
    assertTrue(problems.get(0).contains("outside 1-8"));
    s.put(Scope.USER, PlanConfig.MAX_STEPS, "x");
    assertTrue(PlanConfig.validate(s).get(0).contains("not a whole number"));
  }

  @Test
  void theKeysAreUserOnlyLikeEveryAiKey() {
    assertTrue(Settings.isUserOnly(PlanConfig.ENABLED));
    assertTrue(Settings.isUserOnly(PlanConfig.MAX_STEPS));
  }

  @Test
  void theSwitchCanBeFlippedWithoutTouchingTheLimit() {
    PlanConfig on = PlanConfig.defaults().withEnabled(true);
    assertTrue(on.enabled());
    assertEquals(3, on.maxSteps());
  }
}
