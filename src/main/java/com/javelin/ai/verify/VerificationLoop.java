package com.javelin.ai.verify;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiException;
import com.javelin.ai.AiRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * Ask, check, and ask again with the problems, within a budget. It knows nothing about prompts of a
 * particular action or about files: a {@link Step} asks the model and checks what came back. The
 * number of requests is never more than {@code retries + 1}, whatever the step says.
 */
public final class VerificationLoop {
  private VerificationLoop() {}

  /** What a check of one answer found, and the part of the change to show the model. */
  public record Checked(Verification verification, String hunk) {}

  /**
   * One round: the answer and what the check found ({@code verification} null: nothing to check).
   */
  public record Attempt<R>(int number, R result, Verification verification) {}

  public interface Step<R> {
    /** Sends {@code request} and reads the answer. */
    R ask(AiRequest request, int attempt) throws AiException;

    /** Checks the answer; null when it holds no change to check (the loop stops). */
    Checked check(R result, AiCancel cancel);

    /** A repeat request failed (the provider stopped, say); the attempts so far are kept. */
    default void retryFailed(int attempt, AiException why) {}

    /** Called after each attempt, for progress text. */
    default void attempted(Attempt<R> attempt) {}
  }

  public static <R> List<Attempt<R>> run(
      AiRequest first, int retries, Step<R> step, AiCancel cancel) throws AiException {
    List<Attempt<R>> attempts = new ArrayList<>();
    AiRequest request = first;
    int budget = Math.max(0, retries) + 1;
    for (int n = 1; n <= budget; n++) {
      cancel.throwIfCancelled();
      R result;
      try {
        result = step.ask(request, n);
      } catch (AiException ex) {
        if (n == 1 || ex.kind() == AiException.Kind.CANCELLED) throw ex;
        step.retryFailed(n, ex);
        break;
      }
      Checked checked = step.check(result, cancel);
      Attempt<R> attempt =
          new Attempt<>(n, result, checked == null ? null : checked.verification());
      attempts.add(attempt);
      step.attempted(attempt);
      if (checked == null || !checked.verification().failed() || n == budget) break;
      request = RepairPrompt.append(first, checked.verification(), checked.hunk(), n);
    }
    return attempts;
  }
}
