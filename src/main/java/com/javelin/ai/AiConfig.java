package com.javelin.ai;

import com.javelin.settings.Settings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code ai.*} settings with their defaults and limits. JavaFX-free. All of them are read from
 * the user settings file only (see {@link Settings#isUserOnly}): opening a project must not be able
 * to choose where your code is sent.
 */
public record AiConfig(
    String provider,
    String endpoint,
    String model,
    int maxContextChars,
    Duration timeout,
    int editBlockMinLines) {
  public static final String PROVIDER = "ai.provider";
  public static final String ENDPOINT = "ai.ollama.endpoint";
  public static final String MODEL = "ai.model";
  public static final String MAX_CONTEXT_CHARS = "ai.maxContextChars";
  public static final String TIMEOUT_SECONDS = "ai.timeoutSeconds";

  /**
   * From this many lines up, Propose Change asks the model for edit blocks instead of a whole copy;
   * 0 asks for them always, a huge number never. See {@link PromptBuilder#EDIT_BLOCK_MIN_LINES}.
   */
  public static final String EDIT_BLOCK_MIN_LINES = "ai.editBlockMinLines";

  public static final String DEFAULT_PROVIDER = "ollama";
  public static final int DEFAULT_MAX_CONTEXT_CHARS = 12_000,
      MIN_MAX_CONTEXT_CHARS = 500,
      MAX_MAX_CONTEXT_CHARS = 200_000;
  public static final int DEFAULT_EDIT_BLOCK_MIN_LINES = PromptBuilder.EDIT_BLOCK_MIN_LINES,
      MIN_EDIT_BLOCK_MIN_LINES = 0,
      MAX_EDIT_BLOCK_MIN_LINES = 100_000;
  public static final int DEFAULT_TIMEOUT_SECONDS = 120,
      MIN_TIMEOUT_SECONDS = 5,
      MAX_TIMEOUT_SECONDS = 1800;

  public static AiConfig from(Settings settings) {
    return new AiConfig(
        settings.get(PROVIDER, DEFAULT_PROVIDER).strip().toLowerCase(java.util.Locale.ROOT),
        settings.get(ENDPOINT, LoopbackEndpoint.DEFAULT).strip(),
        settings.get(MODEL, "").strip(),
        clamp(
            settings.getInt(MAX_CONTEXT_CHARS, DEFAULT_MAX_CONTEXT_CHARS),
            MIN_MAX_CONTEXT_CHARS,
            MAX_MAX_CONTEXT_CHARS),
        Duration.ofSeconds(
            clamp(
                settings.getInt(TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS),
                MIN_TIMEOUT_SECONDS,
                MAX_TIMEOUT_SECONDS)),
        clamp(
            settings.getInt(EDIT_BLOCK_MIN_LINES, DEFAULT_EDIT_BLOCK_MIN_LINES),
            MIN_EDIT_BLOCK_MIN_LINES,
            MAX_EDIT_BLOCK_MIN_LINES));
  }

  /**
   * Problems with the current {@code ai.*} values, for display; {@link #from} falls back or clamps
   * silently.
   */
  public static List<String> validate(Settings settings) {
    List<String> problems = new ArrayList<>();
    String provider =
        settings.get(PROVIDER, DEFAULT_PROVIDER).strip().toLowerCase(java.util.Locale.ROOT);
    if (!AiProviders.available().contains(provider))
      problems.add(
          PROVIDER
              + ": '"
              + provider
              + "' is not an AI provider this version has; available: "
              + String.join(", ", AiProviders.available()));
    try {
      LoopbackEndpoint.require(settings.get(ENDPOINT, LoopbackEndpoint.DEFAULT));
    } catch (AiException ex) {
      problems.add(ENDPOINT + ": " + ex.getMessage());
    }
    checkInt(settings, MAX_CONTEXT_CHARS, MIN_MAX_CONTEXT_CHARS, MAX_MAX_CONTEXT_CHARS, problems);
    checkInt(settings, TIMEOUT_SECONDS, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS, problems);
    checkInt(
        settings,
        EDIT_BLOCK_MIN_LINES,
        MIN_EDIT_BLOCK_MIN_LINES,
        MAX_EDIT_BLOCK_MIN_LINES,
        problems);
    return problems;
  }

  public boolean hasModel() {
    return !model.isEmpty();
  }

  public AiConfig withModel(String name) {
    return new AiConfig(
        provider,
        endpoint,
        name == null ? "" : name.strip(),
        maxContextChars,
        timeout,
        editBlockMinLines);
  }

  private static void checkInt(
      Settings settings, String key, int min, int max, List<String> problems) {
    String raw = settings.get(key, null);
    if (raw == null) return;
    try {
      int value = Integer.parseInt(raw.trim());
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

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
