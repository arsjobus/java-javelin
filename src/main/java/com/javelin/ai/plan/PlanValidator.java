package com.javelin.ai.plan;

import com.javelin.ai.AiException;
import com.javelin.ai.FailureReason;
import com.javelin.ai.MethodFinder;
import com.javelin.ai.project.MultiFileReply;
import com.javelin.ai.project.ProjectFile;
import com.javelin.ai.project.ProjectIndex;
import com.javelin.ai.project.ProjectReader;
import com.javelin.ai.project.ProjectWorkspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a plan may run. A step must name a file the AI may be shown and a method that is
 * in it; the only way to name something that is not there is to say {@code new}, and a file or
 * method that an earlier step declares new may be used by the steps after it (the methods of a file
 * that an earlier step creates whole are checked when the step runs, as nothing says yet what the
 * file will hold). Anything else is a problem with the step's number and one reason from the shared
 * {@link FailureReason} list (an unknown file is {@code WRONG_FILE}, an unknown method {@code
 * ANCHOR_NOT_FOUND}), and a plan with a problem does not run: it is shown, edited and checked
 * again, never guessed at. The step limit is checked here and again by {@link StepExecutor}. Reads
 * files; changes nothing.
 */
public final class PlanValidator {
  private PlanValidator() {}

  /** A reason a plan or one of its steps cannot run; {@code step} is 1-based, 0 for the plan. */
  public record Problem(int step, FailureReason reason, String message) {
    public String describe() {
      return step == 0 ? message : "Step " + step + ": " + message;
    }
  }

  /** What checking found; the plan may run when there is no problem. */
  public record Check(List<Problem> problems) {
    public Check {
      problems = List.copyOf(problems);
    }

    public boolean ok() {
      return problems.isEmpty();
    }

    /** The problems as lines for the dialog and for the re-ask. */
    public String describe() {
      StringBuilder out = new StringBuilder();
      for (Problem p : problems) out.append(p.describe()).append('\n');
      return out.toString();
    }
  }

  /** The current text of a file of the project, as the model would be shown it. */
  @FunctionalInterface
  public interface TextSource {
    String read(String path) throws IOException;
  }

  /** Reads the files of an open workspace. */
  public static TextSource sourceOf(ProjectWorkspace workspace) {
    return path -> {
      ProjectFile file = workspace.index().find(path);
      if (file == null) throw new IOException(path + " is not a file of the project.");
      return workspace.reader().read(file, workspace.options().maxFileBytes());
    };
  }

  public static Check check(TaskPlan plan, int maxSteps, ProjectWorkspace workspace) {
    return check(plan, maxSteps, workspace.index(), sourceOf(workspace));
  }

  public static Check check(TaskPlan plan, int maxSteps, ProjectIndex index, TextSource texts) {
    List<Problem> problems = new ArrayList<>();
    if (plan.steps().isEmpty())
      problems.add(new Problem(0, FailureReason.NO_EDIT_BLOCK, "The plan has no steps."));
    if (plan.steps().size() > maxSteps)
      problems.add(
          new Problem(
              0,
              FailureReason.OVERSIZED,
              "The plan has "
                  + plan.steps().size()
                  + " steps and the limit is "
                  + maxSteps
                  + ". Remove or merge steps, or raise ai.plan.maxSteps."));
    Set<String> createdFiles = new HashSet<>();
    Map<String, Set<String>> createdMethods = new HashMap<>();
    int number = 0;
    for (PlanStep step : plan.steps()) {
      number++;
      Problem problem = checkStep(number, step, index, texts, createdFiles, createdMethods);
      if (problem != null) problems.add(problem);
    }
    return new Check(problems);
  }

  private static Problem checkStep(
      int number,
      PlanStep step,
      ProjectIndex index,
      TextSource texts,
      Set<String> createdFiles,
      Map<String, Set<String>> createdMethods) {
    String where = step.label();
    if (step.file().isEmpty())
      return new Problem(number, FailureReason.WRONG_FILE, "names no file.");
    if (step.task().isEmpty())
      return new Problem(
          number, FailureReason.UNPARSEABLE, where + " says nothing about what to do.");
    Path resolved;
    try {
      MultiFileReply.refuseIfNotChangeable(step.file());
      resolved = ProjectReader.resolve(index.root(), step.file());
    } catch (AiException | IOException ex) {
      return new Problem(number, FailureReason.WRONG_FILE, step.file() + " " + ex.getMessage());
    }
    if (!step.symbol().isEmpty() && !MethodFinder.isIdentifier(step.symbol()))
      return new Problem(
          number,
          FailureReason.ANCHOR_NOT_FOUND,
          "'" + step.symbol() + "' is not the name of a method.");
    boolean inProject = index.find(step.file()) != null;
    boolean madeEarlier = createdFiles.contains(step.file());
    if (!inProject && !madeEarlier) {
      if (!step.isNew())
        return new Problem(
            number,
            FailureReason.WRONG_FILE,
            step.file()
                + " is not a file of the project. Write 'new' in the method column to create it.");
      if (Files.exists(resolved))
        return new Problem(
            number,
            FailureReason.WRONG_FILE,
            step.file()
                + " exists but is left out of what the AI is shown (see ai.project.exclude and"
                + " .gitignore), so it cannot be planned.");
      createdFiles.add(step.file());
      if (!step.symbol().isEmpty())
        createdMethods.computeIfAbsent(step.file(), k -> new HashSet<>()).add(step.symbol());
      return null;
    }
    String text = "";
    if (inProject && !madeEarlier) {
      try {
        text = texts.read(step.file());
      } catch (IOException ex) {
        return new Problem(
            number, FailureReason.WRONG_FILE, step.file() + " cannot be read: " + ex.getMessage());
      }
    }
    Set<String> added = createdMethods.computeIfAbsent(step.file(), k -> new HashSet<>());
    boolean declared = !step.symbol().isEmpty() && MethodFinder.declares(text, step.symbol());
    boolean knownNew = added.contains(step.symbol());
    if (step.isNew()) {
      if (step.symbol().isEmpty())
        return new Problem(
            number,
            FailureReason.REFUSED,
            step.file() + " exists already. Name the method to add, or the method to change.");
      if (declared || knownNew)
        return new Problem(
            number,
            FailureReason.REFUSED,
            step.file()
                + " already has a method '"
                + step.symbol()
                + "'. Leave out 'new' to change it.");
      added.add(step.symbol());
      return null;
    }
    if (step.symbol().isEmpty())
      return new Problem(
          number,
          FailureReason.ANCHOR_NOT_FOUND,
          step.file() + " is named without a method. Name the method this step changes.");
    // what an earlier step writes into a file it creates is not known until it has run, so a
    // method of such a file is checked when its step runs (StepContext), not here
    if (!declared && !knownNew && !madeEarlier)
      return new Problem(
          number,
          FailureReason.ANCHOR_NOT_FOUND,
          step.file()
              + " has no method '"
              + step.symbol()
              + "'. Write 'new "
              + step.symbol()
              + "' if the step adds it.");
    return null;
  }
}
