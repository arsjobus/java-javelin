package com.javelin.ai.project;

import com.javelin.settings.NameList;
import com.javelin.settings.Settings;
import java.util.ArrayList;
import java.util.List;

/**
 * The settings of project requests, with defaults and limits. Like all {@code ai.*} settings they
 * come from the user file only (see {@link Settings#isUserOnly}), so opening a project cannot widen
 * what is sent or where. {@code contextTokens} is the runtime window asked of the provider (for
 * Ollama {@code num_ctx}), which is not the same as what the model supports at most.
 */
public record ProjectAiConfig(int contextTokens, int reserveTokens, ScanOptions scan) {
  public static final String CONTEXT_TOKENS = "ai.contextTokens";
  public static final String RESERVE_TOKENS = "ai.reserveTokens";
  public static final String EXCLUDE = "ai.project.exclude";
  public static final String MAX_FILE_BYTES = "ai.project.maxFileBytes";
  public static final String MAX_FILES = "ai.project.maxFiles";
  public static final String GITIGNORE = "ai.project.gitignore";

  public static ProjectAiConfig from(Settings settings) {
    ScanOptions scan =
        new ScanOptions(
            List.copyOf(NameList.parse(settings.get(EXCLUDE, ""))),
            clamp(
                settings.getInt(MAX_FILE_BYTES, (int) ScanOptions.DEFAULT_MAX_FILE_BYTES),
                ScanOptions.MIN_MAX_FILE_BYTES,
                ScanOptions.MAX_MAX_FILE_BYTES),
            (int)
                clamp(
                    settings.getInt(MAX_FILES, ScanOptions.DEFAULT_MAX_FILES),
                    ScanOptions.MIN_MAX_FILES,
                    ScanOptions.MAX_MAX_FILES),
            settings.getBoolean(GITIGNORE, true));
    return new ProjectAiConfig(
        (int)
            clamp(
                settings.getInt(CONTEXT_TOKENS, ContextBudget.DEFAULT_RUNTIME_TOKENS),
                ContextBudget.MIN_RUNTIME_TOKENS,
                ContextBudget.MAX_RUNTIME_TOKENS),
        (int)
            clamp(
                settings.getInt(RESERVE_TOKENS, ContextBudget.DEFAULT_RESERVE_TOKENS),
                ContextBudget.MIN_RESERVE_TOKENS,
                ContextBudget.MAX_RESERVE_TOKENS),
        scan);
  }

  /** Problems with the current values, for display; {@link #from} clamps silently. */
  public static List<String> validate(Settings settings) {
    List<String> problems = new ArrayList<>();
    checkInt(
        settings,
        CONTEXT_TOKENS,
        ContextBudget.MIN_RUNTIME_TOKENS,
        ContextBudget.MAX_RUNTIME_TOKENS,
        problems);
    checkInt(
        settings,
        RESERVE_TOKENS,
        ContextBudget.MIN_RESERVE_TOKENS,
        ContextBudget.MAX_RESERVE_TOKENS,
        problems);
    checkInt(
        settings,
        MAX_FILE_BYTES,
        ScanOptions.MIN_MAX_FILE_BYTES,
        ScanOptions.MAX_MAX_FILE_BYTES,
        problems);
    checkInt(settings, MAX_FILES, ScanOptions.MIN_MAX_FILES, ScanOptions.MAX_MAX_FILES, problems);
    String git = settings.get(GITIGNORE, null);
    if (git != null
        && !git.strip().equalsIgnoreCase("true")
        && !git.strip().equalsIgnoreCase("false"))
      problems.add(GITIGNORE + ": '" + git + "' is not true or false; true is used");
    return problems;
  }

  public ContextBudget budget(int modelMaxTokens) {
    return new ContextBudget(modelMaxTokens, contextTokens, reserveTokens);
  }

  private static void checkInt(
      Settings settings, String key, long min, long max, List<String> problems) {
    String raw = settings.get(key, null);
    if (raw == null) return;
    try {
      long value = Long.parseLong(raw.trim());
      if (value < min || value > max)
        problems.add(
            key
                + ": "
                + value
                + " is outside "
                + min
                + "-"
                + max
                + "; the nearest allowed value is used");
    } catch (NumberFormatException ex) {
      problems.add(key + ": '" + raw + "' is not a whole number; the default is used");
    }
  }

  private static long clamp(long value, long min, long max) {
    return Math.max(min, Math.min(max, value));
  }
}
