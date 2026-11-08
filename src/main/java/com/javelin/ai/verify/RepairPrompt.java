package com.javelin.ai.verify;

import com.javelin.ai.AiRequest;
import java.util.List;

/**
 * The message that goes back to the model after a check failed. It is small on purpose: the first
 * few problems with file and line, and only the part of the change they point at, never the whole
 * previous answer. The original request is sent again unchanged above it, so the model works from
 * the same code and the same format rules, and its new answer is read against the original code.
 */
public final class RepairPrompt {
  private RepairPrompt() {}

  /** The most problems and hunk lines that go back. */
  public static final int MAX_PROBLEMS = 5, MAX_HUNK_LINES = 30;

  /**
   * {@code first} with a failure report appended; {@code attempt} is the number that failed
   * (1-based).
   */
  public static AiRequest append(AiRequest first, Verification failed, String hunk, int attempt) {
    StringBuilder sb = new StringBuilder(first.prompt());
    sb.append("\n\n===== YOUR PREVIOUS ANSWER (attempt ")
        .append(attempt)
        .append(") FAILED =====\n");
    sb.append(
        failed.outcome() == Verification.Outcome.TESTS_FAILED
            ? "The code compiled but these tests failed:\n"
            : "The code did not compile. Compiler messages:\n");
    List<VerifyProblem> problems = failed.problems();
    for (int i = 0; i < Math.min(MAX_PROBLEMS, problems.size()); i++)
      sb.append("- ").append(problems.get(i).label()).append('\n');
    if (problems.size() > MAX_PROBLEMS)
      sb.append("- (").append(problems.size() - MAX_PROBLEMS).append(" more)\n");
    if (hunk != null && !hunk.isBlank()) {
      sb.append("\nThe part of your change they point at:\n")
          .append(hunk.stripTrailing())
          .append('\n');
    }
    sb.append(
        "\n"
            + "Answer again with the complete corrected change, in the same format as before. Make"
            + " it against the ORIGINAL code given above, not against your previous answer. Change"
            + " only what the task needs.\n");
    return new AiRequest(
        first.model(),
        first.system(),
        sb.toString(),
        first.contextTokens(),
        first.sampling(),
        first.responseSchema());
  }

  /**
   * The lines of {@code text} around {@code line} (1-based), numbered, at most {@link
   * #MAX_HUNK_LINES}; the start of the text when {@code line} is 0 or out of range.
   */
  public static String hunkAround(String text, int line) {
    String[] lines = text.split("\n", -1);
    int center = line >= 1 && line <= lines.length ? line : 1;
    int from = Math.max(1, center - MAX_HUNK_LINES / 3);
    int to = Math.min(lines.length, from + MAX_HUNK_LINES - 1);
    StringBuilder sb = new StringBuilder();
    for (int i = from; i <= to; i++)
      sb.append(String.format("%4d | %s%n", i, lines[i - 1].replace("\r", "")));
    return sb.toString();
  }
}
