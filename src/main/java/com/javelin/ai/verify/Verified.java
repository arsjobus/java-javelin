package com.javelin.ai.verify;

import java.util.List;
import java.util.function.Predicate;

/**
 * The outcome of a verified request: the answer to show the user and every attempt that led to it,
 * each with what its check found. {@code answer} is the attempt that passed, else the last one that
 * held a change, else the last one: a failed change is still shown (the user may Apply anyway),
 * with its problems.
 */
public record Verified<R>(R answer, List<VerificationLoop.Attempt<R>> attempts) {
  public Verified {
    attempts = List.copyOf(attempts);
  }

  /** The attempt whose answer is shown. */
  public VerificationLoop.Attempt<R> chosen() {
    return choose(attempts, a -> a.verification() != null);
  }

  static <R> VerificationLoop.Attempt<R> choose(
      List<VerificationLoop.Attempt<R>> attempts, Predicate<VerificationLoop.Attempt<R>> checked) {
    VerificationLoop.Attempt<R> lastChecked = null;
    for (var a : attempts) if (checked.test(a) && a.verification().ok()) return a;
    for (var a : attempts) if (checked.test(a)) lastChecked = a;
    return lastChecked != null ? lastChecked : attempts.get(attempts.size() - 1);
  }

  /** Runs a loop and wraps what it found. */
  public static <R> Verified<R> of(List<VerificationLoop.Attempt<R>> attempts) {
    if (attempts.isEmpty()) throw new IllegalStateException("a verified request has an attempt");
    return new Verified<>(choose(attempts, a -> a.verification() != null).result(), attempts);
  }

  /** How many requests went to the model. */
  public int requests() {
    return attempts.size();
  }

  /** The text for the review dialog: one line per attempt; empty when nothing was checked. */
  public String report() {
    return VerificationReport.text(this);
  }
}
