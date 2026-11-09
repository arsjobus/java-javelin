package com.javelin.ai.plan;

import com.javelin.ai.FailureReason;
import com.javelin.ai.project.ProjectAiResult;
import com.javelin.ai.project.ProjectProposal;
import com.javelin.ai.project.ProjectSnapshot;
import java.util.List;

/**
 * A plan that was run: what each step did and, from the steps that completed, one proposal for the
 * whole change. A failed step stops the run; the steps before it are kept in the proposal and
 * shown, the failed step's change is not, and the steps after it are listed as not run. The
 * proposal goes through the same review as any project change: nothing here writes a file.
 */
public record PlanRun(
    TaskPlan plan, List<StepResult> results, ProjectProposal proposal, ProjectSnapshot snapshot) {
  public PlanRun {
    results = List.copyOf(results);
  }

  public int completed() {
    return (int) results.stream().filter(StepResult::done).count();
  }

  /** True when every step of the plan was done. */
  public boolean complete() {
    return !results.isEmpty() && completed() == results.size();
  }

  /** The step that stopped the run, or null when none did. */
  public StepResult failed() {
    for (StepResult r : results) if (r.status() == StepResult.Status.FAILED) return r;
    return null;
  }

  /** The text for the review dialog and the AI panel: one line per step, then what that means. */
  public String report() {
    StringBuilder out = new StringBuilder("Plan: " + completed() + " of " + results.size());
    out.append(results.size() == 1 ? " step done" : " steps done").append(".\n");
    for (StepResult r : results) {
      out.append("  ").append(r.line()).append('\n');
      if (!r.checks().isEmpty())
        for (String line : r.checks().split("\\R")) out.append("      ").append(line).append('\n');
    }
    StepResult stopped = failed();
    if (stopped != null)
      out.append("The run stopped at step ")
          .append(stopped.number())
          .append(". The changes below are those of the steps before it; the change of step ")
          .append(stopped.number())
          .append(" is not included.\n");
    return out.toString();
  }

  /** The run as the answer of a project request, so the existing review and apply path serves. */
  public ProjectAiResult asResult() {
    boolean none = proposal == null || proposal.isEmpty();
    StepResult stopped = failed();
    FailureReason why = none && stopped != null ? stopped.failure() : null;
    if (none && why == null && stopped == null) why = FailureReason.NO_EDIT_BLOCK;
    return new ProjectAiResult(
        report(), none ? null : proposal, report().strip(), snapshot, none ? why : null);
  }
}
