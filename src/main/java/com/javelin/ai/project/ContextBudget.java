package com.javelin.ai.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How much of a model's context window the project text may use. Three numbers that are easy to mix
 * up: what the model can do at most ({@code modelMaxTokens}, 0 when unknown), the window this
 * request asks the server for ({@code runtimeTokens}: for Ollama, {@code num_ctx}), and the room
 * kept free for the answer ({@code reserveTokens}). The window that counts is the smaller of the
 * first two. Token counts are estimates: no tokenizer is bundled, so the estimate is deliberately
 * on the high side. JavaFX-free.
 */
public record ContextBudget(
    int modelMaxTokens, int runtimeTokens, int reserveTokens, double charsPerToken) {
  public static final int DEFAULT_RUNTIME_TOKENS = 32_768,
      MIN_RUNTIME_TOKENS = 2_048,
      MAX_RUNTIME_TOKENS = 262_144;
  public static final int DEFAULT_RESERVE_TOKENS = 4_096,
      MIN_RESERVE_TOKENS = 256,
      MAX_RESERVE_TOKENS = 65_536;

  /** Characters per token used for estimates; code and prose both run near 3 to 4. */
  static final double CHARS_PER_TOKEN = 3.0;

  /** Tokens the chat format adds around each message. */
  static final int MESSAGE_OVERHEAD_TOKENS = 16;

  public ContextBudget {
    if (runtimeTokens < 1) throw new IllegalArgumentException("runtimeTokens must be positive");
    if (reserveTokens < 0) throw new IllegalArgumentException("reserveTokens must not be negative");
    if (!(charsPerToken > 0)) throw new IllegalArgumentException("charsPerToken must be positive");
  }

  /** A budget that estimates with the standard {@link #CHARS_PER_TOKEN}. */
  public ContextBudget(int modelMaxTokens, int runtimeTokens, int reserveTokens) {
    this(modelMaxTokens, runtimeTokens, reserveTokens, CHARS_PER_TOKEN);
  }

  /**
   * The same budget estimating with {@code chars} characters per token (see {@link
   * TokenCalibration}).
   */
  public ContextBudget withCharsPerToken(double chars) {
    return new ContextBudget(modelMaxTokens, runtimeTokens, reserveTokens, chars);
  }

  /** True when the estimate has been adjusted to the model in use. */
  public boolean isCalibrated() {
    return charsPerToken != CHARS_PER_TOKEN;
  }

  /** An estimate of the tokens in {@code text} with this budget's characters per token. */
  public int estimate(String text) {
    if (text == null || text.isEmpty()) return 0;
    return (int) Math.ceil(text.length() / charsPerToken);
  }

  /** An estimate of the tokens in {@code text}; never less than the real count by much. */
  public static int estimateTokens(String text) {
    if (text == null || text.isEmpty()) return 0;
    return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
  }

  /**
   * The window that really applies: the runtime window, but never above what the model supports.
   */
  public int effectiveWindow() {
    return modelMaxTokens > 0 ? Math.min(modelMaxTokens, runtimeTokens) : runtimeTokens;
  }

  /**
   * What the project text may use after {@code fixedTokens} (the system message, the request, the
   * wording around the files) and the reserve for the answer. Never negative.
   */
  public int availableForFiles(int fixedTokens) {
    return Math.max(
        0, effectiveWindow() - reserveTokens - fixedTokens - MESSAGE_OVERHEAD_TOKENS * 2);
  }

  /** What is wrong with the numbers, in words for the preview; empty when nothing is. */
  public List<String> warnings() {
    List<String> out = new ArrayList<>();
    if (modelMaxTokens > 0 && runtimeTokens > modelMaxTokens)
      out.add(
          "ai.contextTokens ("
              + format(runtimeTokens)
              + ") is more than the model supports ("
              + format(modelMaxTokens)
              + "), so "
              + format(modelMaxTokens)
              + " is used.");
    if (reserveTokens >= effectiveWindow())
      out.add(
          "The room kept for the answer ("
              + format(reserveTokens)
              + " tokens) fills the whole window, so no project files fit. Raise ai.contextTokens"
              + " or lower ai.reserveTokens.");
    return out;
  }

  /** How much of the budget {@code usedTokens} of file text and framing is. */
  public Usage usage(int usedTokens) {
    return new Usage(
        usedTokens, effectiveWindow(), reserveTokens, isCalibrated() ? charsPerToken : 0);
  }

  /** Consumption, for the preview: used of window, with the reserve for the answer. */
  public record Usage(int usedTokens, int windowTokens, int reserveTokens, double calibratedChars) {
    public Usage(int usedTokens, int windowTokens, int reserveTokens) {
      this(usedTokens, windowTokens, reserveTokens, 0);
    }

    public int freeTokens() {
      return Math.max(0, windowTokens - reserveTokens - usedTokens);
    }

    public int percent() {
      int usable = Math.max(1, windowTokens - reserveTokens);
      return (int) Math.min(100, Math.round(100.0 * usedTokens / usable));
    }

    public String describe() {
      return "about "
          + format(usedTokens)
          + " of "
          + format(Math.max(0, windowTokens - reserveTokens))
          + " tokens for the request ("
          + percent()
          + "%); the window is "
          + format(windowTokens)
          + " and "
          + format(reserveTokens)
          + " are kept free for the answer. Counts are estimates"
          + (calibratedChars > 0
              ? ", adjusted to this model at "
                  + String.format(Locale.ROOT, "%.1f", calibratedChars)
                  + " characters per token."
              : ".");
    }
  }

  public static String format(int n) {
    return String.format(Locale.ROOT, "%,d", n);
  }
}
