package com.javelin.ai.project;

import com.javelin.ai.AiUsage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Makes the token estimate real for the model in use. No tokenizer is bundled, so {@link
 * ContextBudget} estimates with a fixed number of characters per token, deliberately low. After a
 * request the provider says how many prompt tokens it counted (for Ollama, {@code
 * prompt_eval_count}); this class turns each such answer into a characters-per-token figure for
 * that model and hands the next budget a better one.
 *
 * <p>It errs toward more tokens, because an estimate that is too low overflows the window and an
 * estimate that is too high only leaves room unused. Three things follow. The figure is the lowest
 * of the last {@value #WINDOW} observations, not their mean, so one prose-heavy request does not
 * make code look cheap; an observation is ignored when it cannot be a whole prompt (the server
 * reused a cached start of the prompt and counted only the rest, which makes the text look cheaper
 * than it is); and a safety factor is taken off, with hard limits on both sides. Nothing is used
 * before {@value #MIN_SAMPLES} observations of the model. Calibration lasts as long as the editor
 * runs: nothing is written to disk. Thread-safe. JavaFX-free.
 */
public final class TokenCalibration {
  static final int WINDOW = 8;
  static final int MIN_SAMPLES = 2;
  static final double SAFETY = 0.95;
  static final double LOWEST = 1.5;
  static final double HIGHEST = 4.5;

  /** A ratio outside this range cannot come from a whole prompt: the sample is ignored. */
  static final double PLAUSIBLE_LOW = 1.0;

  static final double PLAUSIBLE_HIGH = 6.0;

  private final Map<String, Deque<Double>> samples = new HashMap<>();

  /**
   * Records one answer.
   *
   * @param model the model that counted
   * @param chars the characters of the request (system message and prompt)
   * @param messages how many chat messages the request was sent as (their framing costs tokens too)
   * @param usage what the provider reported; nothing is recorded when it did not say
   * @return true when the observation was used
   */
  public synchronized boolean record(String model, int chars, int messages, AiUsage usage) {
    if (model == null || usage == null || !usage.isKnown() || chars <= 0) return false;
    int text = usage.promptTokens() - ContextBudget.MESSAGE_OVERHEAD_TOKENS * Math.max(1, messages);
    if (text <= 0) return false;
    double ratio = (double) chars / text;
    if (ratio < PLAUSIBLE_LOW || ratio > PLAUSIBLE_HIGH) return false;
    Deque<Double> forModel = samples.computeIfAbsent(model, k -> new ArrayDeque<>());
    forModel.addLast(ratio);
    while (forModel.size() > WINDOW) forModel.removeFirst();
    return true;
  }

  /** How many observations of {@code model} are held. */
  public synchronized int samples(String model) {
    Deque<Double> forModel = samples.get(model);
    return forModel == null ? 0 : forModel.size();
  }

  /**
   * The characters per token to estimate with for {@code model}: the standard figure until there
   * are enough observations, then the lowest of them with the safety factor, within the limits.
   */
  public synchronized double charsPerToken(String model) {
    Deque<Double> forModel = samples.get(model);
    if (forModel == null || forModel.size() < MIN_SAMPLES) return ContextBudget.CHARS_PER_TOKEN;
    double lowest = Double.MAX_VALUE;
    for (double r : forModel) lowest = Math.min(lowest, r);
    return Math.max(LOWEST, Math.min(HIGHEST, lowest * SAFETY));
  }

  /** The budget with this model's calibrated estimate (the same budget before any calibration). */
  public ContextBudget apply(ContextBudget budget, String model) {
    double chars = charsPerToken(model);
    return chars == budget.charsPerToken() ? budget : budget.withCharsPerToken(chars);
  }

  /** One line for the preview, or empty text when the standard estimate is in use. */
  public String describe(String model) {
    int n = samples(model);
    if (n < MIN_SAMPLES) return "";
    return String.format(
        Locale.ROOT,
        "Token counts are adjusted to %s: %.1f characters per token from its last %d answers"
            + " (the standard estimate is %.1f).",
        model,
        charsPerToken(model),
        n,
        ContextBudget.CHARS_PER_TOKEN);
  }
}
