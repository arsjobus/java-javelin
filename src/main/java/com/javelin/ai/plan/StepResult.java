package com.javelin.ai.plan;

import com.javelin.ai.FailureReason;

/**
 * What became of one step. {@code failure} is the shared reason when the step failed because of
 * what the model did (null when it did not fail, or when the machine was at fault); {@code summary}
 * is one line for the report; {@code checks} is the verification report of the step (empty when
 * nothing was checked); {@code requests} is how many requests the step took.
 */
public record StepResult(
    int number,
    PlanStep step,
    Status status,
    FailureReason failure,
    String summary,
    String checks,
    int requests) {
  public enum Status {
    DONE,
    FAILED,
    NOT_RUN
  }

  public boolean done() {
    return status == Status.DONE;
  }

  /** The line of the report: {@code Step 2 (Calc.java#sub): done, compiles}. */
  public String line() {
    String what =
        switch (status) {
          case DONE -> "done" + (summary.isEmpty() ? "" : ", " + summary);
          case FAILED ->
              "FAILED"
                  + (failure == null ? "" : " (" + failure + ")")
                  + (summary.isEmpty() ? "" : ": " + summary);
          case NOT_RUN -> "not run";
        };
    return "Step " + number + " (" + step.label() + "): " + what;
  }
}
