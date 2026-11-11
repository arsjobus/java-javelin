package com.javelin.ai.symbols;

import com.javelin.settings.Settings;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code ai.symbols.*} settings. User file only, like every {@code ai.*} key. Symbol-level
 * context is off by default: until the eval says it does not lower the pass rate (Phase 4's exit),
 * a project request sends the files it always sent. {@code ai.symbols} turns it on for Smart
 * Context; {@code ai.symbols.profile} picks the limits ({@link SymbolBudget}).
 */
public record SymbolConfig(boolean enabled, SymbolBudget budget) {
  public static final String ENABLED = "ai.symbols";
  public static final String PROFILE = "ai.symbols.profile";

  public static SymbolConfig defaults() {
    return new SymbolConfig(false, SymbolBudget.SMALL);
  }

  public static SymbolConfig from(Settings settings) {
    return new SymbolConfig(
        settings.getBoolean(ENABLED, false),
        SymbolBudget.named(settings.get(PROFILE, "small")).orElse(SymbolBudget.SMALL));
  }

  /** The same configuration with another switch (for tests and the eval). */
  public SymbolConfig withEnabled(boolean on) {
    return new SymbolConfig(on, budget);
  }

  /** Problems with the current values, for display; {@link #from} falls back silently. */
  public static List<String> validate(Settings settings) {
    List<String> problems = new ArrayList<>();
    String on = settings.get(ENABLED, null);
    if (on != null && !on.strip().equalsIgnoreCase("true") && !on.strip().equalsIgnoreCase("false"))
      problems.add(ENABLED + ": '" + on + "' is not true or false; false is used");
    String profile = settings.get(PROFILE, null);
    if (profile != null && SymbolBudget.named(profile).isEmpty())
      problems.add(PROFILE + ": '" + profile + "' is not small or frontier; small is used");
    return problems;
  }
}
