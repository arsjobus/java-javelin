package com.javelin.ai.plan;

import com.javelin.ai.AiException;
import com.javelin.ai.project.ProjectWorkspace;

/**
 * The logic of the plan window, without the window: reads the plan text the user edited, checks it
 * against the project and the step limit, and says what is shown beside it (what each step would
 * send, or what is wrong). A plan is {@code ok} to send only if it was read, it checks, and its
 * preview could be made; the window enables Send only for an ok plan whose text is still the text
 * that was checked, so what the user approves is exactly what runs.
 */
public final class PlanReview {
  private PlanReview() {}

  /**
   * {@code plan} is null when the text could not be read; {@code shown} is for the preview side.
   */
  public record Reviewed(TaskPlan plan, boolean ok, String shown) {}

  public static Reviewed review(
      String instruction, String text, ProjectWorkspace workspace, StepExecutor executor) {
    TaskPlan plan;
    try {
      plan = PlanParser.parseText(instruction, text);
    } catch (AiException ex) {
      return new Reviewed(null, false, ex.getMessage());
    }
    PlanValidator.Check check = PlanValidator.check(plan, executor.maxSteps(), workspace);
    if (!check.ok())
      return new Reviewed(plan, false, "This plan cannot be sent yet:\n\n" + check.describe());
    try {
      return new Reviewed(plan, true, executor.preview(plan, workspace));
    } catch (AiException ex) {
      return new Reviewed(plan, false, ex.getMessage());
    }
  }
}
