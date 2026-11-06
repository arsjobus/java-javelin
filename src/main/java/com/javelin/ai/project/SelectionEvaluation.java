package com.javelin.ai.project;

import com.javelin.ai.AiException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Measures how well each way of choosing context serves a feature request: for a project and a list
 * of requests with the files a good answer needs, it builds the context in Full Project and in
 * Smart Context mode and reports which of those files were included, how many tokens that took and
 * how long building took. It needs no model, so it runs offline and repeatably; a file that a
 * request needs and a mode missed is the record of a failure to fix (a related word to add to
 * {@link Vocabulary}, a rule in {@link SmartSelector}) and, kept as a case, a regression test.
 * JavaFX-free.
 */
public final class SelectionEvaluation {
  private SelectionEvaluation() {}

  /** A request and the project files a good answer to it needs to have seen. */
  public record Case(String name, String request, Set<String> neededFiles) {
    public Case {
      neededFiles = Set.copyOf(neededFiles);
    }
  }

  /** How one mode did on one case. */
  public record Outcome(
      Case testCase,
      ContextMode mode,
      int includedFiles,
      int eligibleFiles,
      int tokens,
      Set<String> found,
      Set<String> missing,
      long millis) {
    /** The share of needed files that were included, 1.0 when nothing was needed. */
    public double recall() {
      int needed = found.size() + missing.size();
      return needed == 0 ? 1.0 : (double) found.size() / needed;
    }
  }

  public record Report(List<Outcome> outcomes) {
    public Report {
      outcomes = List.copyOf(outcomes);
    }

    public List<Outcome> of(ContextMode mode) {
      return outcomes.stream().filter(o -> o.mode() == mode).toList();
    }

    /** The mean recall over the cases of one mode. */
    public double meanRecall(ContextMode mode) {
      return of(mode).stream().mapToDouble(Outcome::recall).average().orElse(1.0);
    }

    /** A table for people. */
    public String describe() {
      StringBuilder out = new StringBuilder();
      out.append(
          String.format(
              Locale.ROOT,
              "%-34s %-14s %6s %8s %7s %6s  %s%n",
              "case",
              "mode",
              "files",
              "tokens",
              "recall",
              "ms",
              "missing"));
      for (Outcome o : outcomes)
        out.append(
            String.format(
                Locale.ROOT,
                "%-34s %-14s %3d/%-3d %8d %6.0f%% %6d  %s%n",
                o.testCase().name(),
                o.mode().label(),
                o.includedFiles(),
                o.eligibleFiles(),
                o.tokens(),
                100 * o.recall(),
                o.millis(),
                o.missing().isEmpty() ? "-" : String.join(", ", new TreeSet<>(o.missing()))));
      return out.toString();
    }
  }

  /** Builds each case in both modes against the same budget and records what came out. */
  public static Report run(ProjectWorkspace workspace, List<Case> cases, ContextBudget budget)
      throws AiException {
    List<Outcome> outcomes = new ArrayList<>();
    for (Case c : cases)
      for (ContextMode mode : ContextMode.values()) outcomes.add(one(workspace, c, mode, budget));
    return new Report(outcomes);
  }

  private static Outcome one(ProjectWorkspace ws, Case c, ContextMode mode, ContextBudget budget)
      throws AiException {
    long start = System.nanoTime();
    ProjectIndex index = ws.index();
    List<Pick> picks =
        mode == ContextMode.FULL_PROJECT
            ? FullProjectSelector.select(index)
            : SmartSelector.select(index, ws.reader(), c.request(), ws.options());
    ProjectContext context =
        ContextBuilder.build(
            index,
            ws.reader(),
            mode,
            picks,
            Adjustments.NONE,
            budget,
            ProjectPromptBuilder.fixedTokens(c.request()),
            ws.options(),
            0);
    long millis = (System.nanoTime() - start) / 1_000_000;
    Set<String> included = new LinkedHashSet<>(context.includedPaths());
    Set<String> found = new TreeSet<>();
    Set<String> missing = new TreeSet<>();
    for (String needed : c.neededFiles()) (included.contains(needed) ? found : missing).add(needed);
    return new Outcome(
        c,
        mode,
        context.included().size(),
        context.eligibleFiles(),
        context.textTokens(),
        found,
        missing,
        millis);
  }
}
