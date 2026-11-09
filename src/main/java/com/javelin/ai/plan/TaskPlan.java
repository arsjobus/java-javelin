package com.javelin.ai.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * A request split into steps, in the order they run. It is only data: the planner makes one, the
 * user may edit, reorder or remove its steps, {@link PlanValidator} says whether it may run and
 * {@link StepExecutor} runs it.
 */
public record TaskPlan(String instruction, List<PlanStep> steps) {
  public TaskPlan {
    steps = List.copyOf(steps);
  }

  public TaskPlan withSteps(List<PlanStep> other) {
    return new TaskPlan(instruction, other);
  }

  /** The plan without step {@code index} (0-based). */
  public TaskPlan without(int index) {
    List<PlanStep> out = new ArrayList<>(steps);
    out.remove(index);
    return withSteps(out);
  }

  /** The plan with the step at {@code from} moved to {@code to} (both 0-based). */
  public TaskPlan moved(int from, int to) {
    List<PlanStep> out = new ArrayList<>(steps);
    out.add(to, out.remove(from));
    return withSteps(out);
  }

  /** The editable text: one numbered line per step. */
  public String text() {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < steps.size(); i++)
      out.append(i + 1).append(". ").append(steps.get(i).line()).append('\n');
    return out.toString();
  }
}
