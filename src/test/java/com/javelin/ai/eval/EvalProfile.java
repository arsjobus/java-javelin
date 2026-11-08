package com.javelin.ai.eval;

import com.javelin.ai.AiConfig;
import com.javelin.ai.project.ContextBudget;
import com.javelin.ai.project.ContextMode;
import java.time.Duration;
import java.util.function.Function;

/**
 * The knobs of one eval run, by name. Phase 0 has one profile, {@code small}: a model that runs on
 * this computer, asked the way the editor asks it today (the product's own limits and window) but
 * with temperature 0 and a seed so a run can be repeated. A frontier profile is not part of Phase 0
 * (the roadmap says no frontier baseline until there is access to measure one), so asking for any
 * other name is an error, not a silent fallback.
 *
 * <p>Two overrides compare edit formats on the same model: {@code -Deval.editBlockMinLines=0} asks
 * for edit blocks for every selection (the default is whole blocks under {@link
 * AiConfig#DEFAULT_EDIT_BLOCK_MIN_LINES} lines), and {@code -Deval.structured=true} asks for JSON
 * edits. Nothing changes the editor's defaults until a run shows the change helps.
 */
record EvalProfile(
    String name,
    String model,
    String endpoint,
    String thinkLevel,
    Duration timeout,
    int maxContextChars,
    int editBlockMinLines,
    int contextTokens,
    int reserveTokens,
    ContextMode contextMode,
    boolean structuredEdits) {

  static final String SMALL = "small";

  /** The product's defaults for everything but the model. */
  static EvalProfile small(String model) {
    return new EvalProfile(
        SMALL,
        model,
        "http://127.0.0.1:11434",
        AiConfig.DEFAULT_THINK_LEVEL,
        Duration.ofSeconds(AiConfig.DEFAULT_TIMEOUT_SECONDS),
        AiConfig.DEFAULT_MAX_CONTEXT_CHARS,
        AiConfig.DEFAULT_EDIT_BLOCK_MIN_LINES,
        ContextBudget.DEFAULT_RUNTIME_TOKENS,
        ContextBudget.DEFAULT_RESERVE_TOKENS,
        ContextMode.SMART,
        AiConfig.DEFAULT_STRUCTURED_EDITS);
  }

  /**
   * The profile asked for by name, with {@code eval.*} overrides read through {@code property}
   * (system properties when run under Maven).
   *
   * @throws IllegalArgumentException for a name this phase does not have, or a model that is not
   *     named
   */
  static EvalProfile resolve(Function<String, String> property) {
    String name = property.apply("eval.profile");
    if (name != null && !name.isBlank() && !name.strip().equals(SMALL))
      throw new IllegalArgumentException(
          "Unknown eval profile '"
              + name
              + "'. Phase 0 has only 'small'; a frontier baseline waits until it can be measured.");
    String model = property.apply("eval.model");
    if (model == null || model.isBlank())
      throw new IllegalArgumentException(
          "Name the model to measure: mvn -Peval test -Deval.model=<ollama model name>");
    EvalProfile base = small(model.strip());
    return new EvalProfile(
        SMALL,
        base.model(),
        or(property.apply("eval.endpoint"), base.endpoint()),
        or(property.apply("eval.think"), base.thinkLevel()),
        Duration.ofSeconds(
            Integer.parseInt(
                or(
                    property.apply("eval.timeoutSeconds"),
                    String.valueOf(base.timeout().toSeconds())))),
        base.maxContextChars(),
        Integer.parseInt(
            or(property.apply("eval.editBlockMinLines"), String.valueOf(base.editBlockMinLines()))),
        base.contextTokens(),
        base.reserveTokens(),
        base.contextMode(),
        Boolean.parseBoolean(
            or(property.apply("eval.structured"), String.valueOf(base.structuredEdits()))));
  }

  private static String or(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.strip();
  }

  AiConfig aiConfig() {
    return new AiConfig(
        "ollama",
        endpoint,
        model,
        maxContextChars,
        timeout,
        editBlockMinLines,
        thinkLevel,
        structuredEdits);
  }
}
