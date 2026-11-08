package com.javelin.ai.verify;

import com.javelin.ai.FailureReason;
import java.util.List;

/**
 * What compiling (and, when asked, testing) a proposed change gave. The change was never written to
 * the project: this is about an in-memory copy. {@link Outcome#NOT_CHECKED} is not a failure of the
 * model: it says the machine could not look (no compiler, classpath unknown, no Java file changed).
 */
public record Verification(
    Outcome outcome,
    List<VerifyProblem> problems,
    int testsRun,
    boolean testsRequested,
    long millis,
    String note) {
  public enum Outcome {
    PASSED,
    COMPILE_FAILED,
    TESTS_FAILED,
    NOT_CHECKED
  }

  public Verification {
    problems = List.copyOf(problems);
    if (note == null) note = "";
  }

  public static Verification passed(
      int testsRun, boolean testsRequested, long millis, String note) {
    return new Verification(Outcome.PASSED, List.of(), testsRun, testsRequested, millis, note);
  }

  public static Verification notChecked(String why) {
    return new Verification(Outcome.NOT_CHECKED, List.of(), 0, false, 0, why);
  }

  /** True when the change compiled (and its tests passed, if they were run). */
  public boolean ok() {
    return outcome == Outcome.PASSED;
  }

  /** True when the loop should ask the model again. */
  public boolean failed() {
    return outcome == Outcome.COMPILE_FAILED || outcome == Outcome.TESTS_FAILED;
  }

  /** The shared failure name, or null when nothing failed. */
  public FailureReason failure() {
    return switch (outcome) {
      case COMPILE_FAILED -> FailureReason.COMPILE_ERROR;
      case TESTS_FAILED -> FailureReason.TEST_FAILURE;
      default -> null;
    };
  }

  /** One line for the review dialog. */
  public String summary() {
    return switch (outcome) {
      case PASSED ->
          testsRequested
              ? "compiles; " + testsRun + " test(s) passed"
              : "compiles" + (note.isEmpty() ? "" : " (" + note + ")");
      case COMPILE_FAILED ->
          "does not compile ("
              + problems.size()
              + " problem"
              + (problems.size() == 1 ? "" : "s")
              + ")";
      case TESTS_FAILED ->
          "compiles, but " + problems.size() + " test failure" + (problems.size() == 1 ? "" : "s");
      case NOT_CHECKED -> "not checked: " + note;
    };
  }
}
