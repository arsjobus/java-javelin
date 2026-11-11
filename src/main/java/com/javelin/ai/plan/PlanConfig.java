package com.javelin.ai.plan;

import com.javelin.settings.Settings;

/**
 * The {@code ai.plan.*} settings. User file only, like every {@code ai.*} key. Planning is off by
 * default: it is one more request to the model before the work, so a project request behaves as
 * before until the user turns it on. {@code maxSteps} is a hard limit, not advice: a plan longer
 * than it is refused when it is checked and again when it is about to run. The default is the small
 * profile's limit in ROADMAP-AI-RELIABILITY.md; the ceiling is the frontier profile's. The model
 * profile ({@code ai.profile}) supplies the default when {@code ai.plan.maxSteps} is not set.
 */
public record PlanConfig(boolean enabled, int maxSteps) {
  public static final String ENABLED = "ai.plan";
  public static final String MAX_STEPS = "ai.plan.maxSteps";

  /** The small profile: 3 steps, one file each. */
  public static final int SMALL_STEPS = 3;

  /** The frontier profile's limit, which is also the most any setting can ask for. */
  public static final int FRONTIER_STEPS = 8;

  public PlanConfig {
    maxSteps = Math.max(1, Math.min(FRONTIER_STEPS, maxSteps));
  }

  public static PlanConfig defaults() {
    return new PlanConfig(false, SMALL_STEPS);
  }

  public static PlanConfig from(Settings settings) {
    return from(settings, SMALL_STEPS);
  }

  /**
   * The settings, with {@code profileSteps} (the model profile's limit) where {@code
   * ai.plan.maxSteps} is not set: an explicit setting wins over the profile.
   */
  public static PlanConfig from(Settings settings, int profileSteps) {
    return new PlanConfig(
        settings.getBoolean(ENABLED, false), settings.getInt(MAX_STEPS, profileSteps));
  }

  /** The same configuration with another switch (for tests and the eval). */
  public PlanConfig withEnabled(boolean on) {
    return new PlanConfig(on, maxSteps);
  }

  /** Problems with the current values, for display; {@link #from} clamps silently. */
  public static java.util.List<String> validate(Settings settings) {
    java.util.List<String> problems = new java.util.ArrayList<>();
    String raw = settings.get(MAX_STEPS, null);
    if (raw != null) {
      try {
        long value = Long.parseLong(raw.trim());
        if (value < 1 || value > FRONTIER_STEPS)
          problems.add(
              MAX_STEPS
                  + ": "
                  + value
                  + " is outside 1-"
                  + FRONTIER_STEPS
                  + "; the nearest allowed value is used");
      } catch (NumberFormatException ex) {
        problems.add(MAX_STEPS + ": '" + raw + "' is not a whole number; the default is used");
      }
    }
    String on = settings.get(ENABLED, null);
    if (on != null && !on.strip().equalsIgnoreCase("true") && !on.strip().equalsIgnoreCase("false"))
      problems.add(ENABLED + ": '" + on + "' is not true or false; false is used");
    return problems;
  }
}
