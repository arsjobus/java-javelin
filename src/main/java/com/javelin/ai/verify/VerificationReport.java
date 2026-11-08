package com.javelin.ai.verify;

/** The per-attempt lines the review dialog shows, as plain text (JavaFX-free, so it is tested). */
public final class VerificationReport {
  private VerificationReport() {}

  /** Empty when no attempt was checked. */
  public static String text(Verified<?> verified) {
    boolean any = verified.attempts().stream().anyMatch(a -> a.verification() != null);
    if (!any) return "";
    StringBuilder sb = new StringBuilder("Checks (change compiled in memory, nothing written):");
    for (var a : verified.attempts()) {
      sb.append("\n  Attempt ").append(a.number()).append(": ");
      if (a.verification() == null) {
        sb.append("no change to check");
        continue;
      }
      sb.append(a.verification().summary());
      var problems = a.verification().problems();
      for (int i = 0; i < Math.min(3, problems.size()); i++)
        sb.append("\n      ").append(problems.get(i).label());
      if (problems.size() > 3) sb.append("\n      (").append(problems.size() - 3).append(" more)");
    }
    if (verified.attempts().size() > 1)
      sb.append("\n  The change shown is attempt ").append(verified.chosen().number()).append('.');
    return sb.toString();
  }

  /** The error line added to the shown change when its check failed. */
  public static String findingText(Verification v) {
    String first = v.problems().isEmpty() ? "" : " First: " + v.problems().get(0).label();
    return (v.outcome() == Verification.Outcome.TESTS_FAILED
            ? "The change compiles but its tests fail."
            : "The change does not compile.")
        + first;
  }
}
