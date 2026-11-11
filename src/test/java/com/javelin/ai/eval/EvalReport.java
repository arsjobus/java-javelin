package com.javelin.ai.eval;

import com.javelin.ai.FailureReason;
import com.javelin.language.Json;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The outcome of an eval: every task of every run, and the numbers that make it readable (the pass
 * rate of each run, how much it moves between runs, what failed and why). It writes the same data
 * as JSON for tools and as the Markdown that is committed as the baseline.
 */
record EvalReport(
    String profile,
    String model,
    String date,
    long seed,
    int tasks,
    List<List<TaskResult>> runs,
    long millis,
    int retries,
    int planSteps,
    String symbols) {

  EvalReport {
    runs = runs.stream().map(List::copyOf).toList();
    if (symbols != null && symbols.isBlank()) symbols = null;
  }

  /** A report of runs without symbol-level context. */
  EvalReport(
      String profile,
      String model,
      String date,
      long seed,
      int tasks,
      List<List<TaskResult>> runs,
      long millis,
      int retries,
      int planSteps) {
    this(profile, model, date, seed, tasks, runs, millis, retries, planSteps, null);
  }

  /** A report of runs with the verification loop and one request per project task. */
  EvalReport(
      String profile,
      String model,
      String date,
      long seed,
      int tasks,
      List<List<TaskResult>> runs,
      long millis,
      int retries) {
    this(profile, model, date, seed, tasks, runs, millis, retries, 0, null);
  }

  /** A single-attempt report: the Phase 0 shape, with no verification loop. */
  EvalReport(
      String profile,
      String model,
      String date,
      long seed,
      int tasks,
      List<List<TaskResult>> runs,
      long millis) {
    this(profile, model, date, seed, tasks, runs, millis, -1, 0, null);
  }

  /** True when Smart Context worked at symbol level (Phase 4). */
  boolean symbolic() {
    return symbols != null;
  }

  /** True when project tasks went through the Phase 3 planner and step executor. */
  boolean planned() {
    return planSteps > 0;
  }

  /** True when the runs used the Phase 2 loop: compile the proposal, ask again on failure. */
  boolean verified() {
    return retries >= 0;
  }

  /**
   * Results in which a change came out of the pipeline and the project with it applied compiles:
   * the proposals that would reach the review with a compiling result. Phase 2's measure.
   */
  int reachedReviewCompiling() {
    int n = 0;
    for (List<TaskResult> run : runs) for (TaskResult r : run) if (r.applied() && r.compiled()) n++;
    return n;
  }

  /** The share of tasks that passed, for each run. */
  List<Double> rates() {
    List<Double> rates = new ArrayList<>();
    for (List<TaskResult> run : runs)
      rates.add(
          run.isEmpty()
              ? 0.0
              : run.stream().filter(TaskResult::passed).count() / (double) run.size());
    return rates;
  }

  double mean() {
    return rates().stream().mapToDouble(Double::doubleValue).average().orElse(0);
  }

  double min() {
    return rates().stream().mapToDouble(Double::doubleValue).min().orElse(0);
  }

  double max() {
    return rates().stream().mapToDouble(Double::doubleValue).max().orElse(0);
  }

  /** Sample standard deviation of the run rates (0 for a single run). */
  double stddev() {
    List<Double> r = rates();
    if (r.size() < 2) return 0;
    double mean = mean();
    double sum = 0;
    for (double x : r) sum += (x - mean) * (x - mean);
    return Math.sqrt(sum / (r.size() - 1));
  }

  /** Mean pass rate over the runs for each category. */
  Map<EvalTask.Category, Double> byCategory() {
    Map<EvalTask.Category, int[]> counts = new EnumMap<>(EvalTask.Category.class);
    for (List<TaskResult> run : runs)
      for (TaskResult r : run) {
        int[] c = counts.computeIfAbsent(r.category(), k -> new int[2]);
        c[0] += r.passed() ? 1 : 0;
        c[1]++;
      }
    Map<EvalTask.Category, Double> rates = new EnumMap<>(EvalTask.Category.class);
    counts.forEach((k, c) -> rates.put(k, c[0] / (double) c[1]));
    return rates;
  }

  /** How many task results failed for each reason, over all runs. */
  Map<FailureReason, Integer> failures() {
    Map<FailureReason, Integer> counts = new EnumMap<>(FailureReason.class);
    for (List<TaskResult> run : runs)
      for (TaskResult r : run) if (r.failure() != null) counts.merge(r.failure(), 1, Integer::sum);
    return counts;
  }

  /**
   * Results that failed because of the contract between model and editor, not because of the code
   * the model wrote: a reply that could not be read, or edits that could not be placed. Phase 1's
   * measure.
   */
  int contractFailures() {
    Map<FailureReason, Integer> f = failures();
    return f.getOrDefault(FailureReason.UNPARSEABLE, 0)
        + f.getOrDefault(FailureReason.ANCHOR_NOT_FOUND, 0);
  }

  /** How many task results there were, over all runs. */
  int totalResults() {
    int n = 0;
    for (List<TaskResult> run : runs) n += run.size();
    return n;
  }

  /** Tasks that passed in some runs and failed in others: where luck decides. */
  List<String> unstable() {
    return outcomes(true);
  }

  /** Tasks that failed in every run. */
  List<String> neverPassed() {
    return outcomes(false);
  }

  private List<String> outcomes(boolean mixed) {
    Map<String, int[]> counts = new TreeMap<>();
    for (List<TaskResult> run : runs)
      for (TaskResult r : run) {
        int[] c = counts.computeIfAbsent(r.id(), k -> new int[2]);
        c[0] += r.passed() ? 1 : 0;
        c[1]++;
      }
    List<String> ids = new ArrayList<>();
    counts.forEach(
        (id, c) -> {
          if (mixed ? c[0] > 0 && c[0] < c[1] : c[0] == 0) ids.add(id);
        });
    return ids;
  }

  /** Tokens the provider reported over a run, or -1 when any task did not report them. */
  long tokens(int run) {
    long total = 0;
    for (TaskResult r : runs.get(run)) {
      if (r.promptTokens() < 0 || r.completionTokens() < 0) return -1;
      total += r.promptTokens() + r.completionTokens();
    }
    return total;
  }

  String toJson() {
    Map<String, Object> root = new LinkedHashMap<>();
    root.put("profile", profile);
    root.put("model", model);
    root.put("date", date);
    root.put("seed", seed);
    root.put("tasks", tasks);
    root.put("runCount", runs.size());
    root.put("wallMillis", millis);
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("runRates", rates());
    summary.put("mean", mean());
    summary.put("min", min());
    summary.put("max", max());
    summary.put("stddev", stddev());
    Map<String, Object> reasons = new LinkedHashMap<>();
    failures().forEach((k, v) -> reasons.put(k.name(), v));
    summary.put("failures", reasons);
    summary.put("contractFailures", contractFailures());
    if (planned()) summary.put("planSteps", planSteps);
    if (symbolic()) summary.put("symbols", symbols);
    if (verified()) {
      summary.put("retries", retries);
      summary.put("reachedReviewCompiling", reachedReviewCompiling());
      summary.put("results", totalResults());
    }
    Map<String, Object> categories = new LinkedHashMap<>();
    byCategory().forEach((k, v) -> categories.put(k.name(), v));
    summary.put("byCategory", categories);
    summary.put("unstableTasks", unstable());
    summary.put("neverPassed", neverPassed());
    root.put("summary", summary);
    List<Object> runList = new ArrayList<>();
    for (List<TaskResult> run : runs) {
      List<Object> items = new ArrayList<>();
      for (TaskResult r : run) items.add(json(r));
      runList.add(items);
    }
    root.put("runs", runList);
    return Json.write(root);
  }

  private static Map<String, Object> json(TaskResult r) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", r.id());
    m.put("category", r.category().name());
    m.put("applied", r.applied());
    m.put("compiled", r.compiled());
    m.put("passed", r.passed());
    m.put("attempts", r.attempts());
    m.put("millis", r.millis());
    m.put("promptTokens", r.promptTokens() < 0 ? null : r.promptTokens());
    m.put("completionTokens", r.completionTokens() < 0 ? null : r.completionTokens());
    m.put("changedLines", r.changedLines());
    m.put("flagged", r.flagged());
    m.put("failure", r.failure() == null ? null : r.failure().name());
    m.put("detail", r.detail());
    return m;
  }

  private static String percent(double rate) {
    return String.format(Locale.ROOT, "%.0f%%", rate * 100);
  }

  /** The baseline document: what was measured, how, and what came out. */
  String toMarkdown() {
    StringBuilder md = new StringBuilder();
    md.append("# AI edit baseline: ").append(model).append(", ").append(date).append("\n\n");
    md.append("Measured with `mvn -Peval test -Deval.model=").append(model).append("`. ");
    md.append("Profile `").append(profile).append("`: the editor's own limits and window, ");
    md.append("temperature 0, seed ").append(seed).append(" plus the run number. ");
    md.append(tasks).append(" tasks, ").append(runs.size()).append(" runs, ");
    if (verified())
      md.append("each proposal compiled in memory and, when that failed, repaired with the ")
          .append("compiler's messages up to ")
          .append(retries)
          .append(retries == 1 ? " more request" : " more requests")
          .append(" (Phase 2). ");
    else md.append("one attempt per task (no retries exist yet). ");
    if (planned())
      md.append("Project tasks were planned first (at most ")
          .append(planSteps)
          .append(" steps, one file and method each, every step checked before the next, ")
          .append("Phase 3); compare them with the same model's baseline without a plan. ");
    if (symbolic())
      md.append("Smart Context worked at symbol level with the `")
          .append(symbols)
          .append("` profile (Phase 4): large Java files were sent as the methods the request is")
          .append(" about plus signatures; compare with the same model's baseline without it. ");
    md.append("A task passes when its judging test passes after the ");
    md.append("change is applied; nothing is judged by a model.\n\n");
    md.append("## Result\n\n");
    md.append("| Run | Passed | Rate |\n| --- | --- | --- |\n");
    for (int i = 0; i < runs.size(); i++) {
      long passed = runs.get(i).stream().filter(TaskResult::passed).count();
      md.append("| ")
          .append(i + 1)
          .append(" | ")
          .append(passed)
          .append(" of ")
          .append(runs.get(i).size())
          .append(" | ")
          .append(percent(rates().get(i)))
          .append(" |\n");
    }
    md.append("\nMean ").append(percent(mean())).append(", lowest ").append(percent(min()));
    md.append(", highest ").append(percent(max())).append(", standard deviation ");
    md.append(String.format(Locale.ROOT, "%.1f", stddev() * 100)).append(" points. ");
    if (verified()) {
      md.append("The proposed target for the small profile is 70% within two attempts. ");
      md.append("Compare with the single-attempt baseline of the same model and profile.\n\n");
      md.append("Reached review with a compiling result: ")
          .append(reachedReviewCompiling())
          .append(" of ")
          .append(totalResults())
          .append(" results (")
          .append(
              percent(totalResults() == 0 ? 0 : reachedReviewCompiling() / (double) totalResults()))
          .append(").\n\n");
    } else {
      md.append("The proposed target for the small profile is 70% within two attempts; this ");
      md.append("baseline is single-attempt.\n\n");
    }
    md.append("## By kind of task\n\n| Kind | Mean pass rate |\n| --- | --- |\n");
    byCategory()
        .forEach(
            (k, v) -> md.append("| ").append(k).append(" | ").append(percent(v)).append(" |\n"));
    md.append("\n## Why tasks failed\n\n");
    if (failures().isEmpty()) md.append("No task failed.\n");
    else {
      md.append("| Reason | Count (all runs) | Meaning |\n| --- | --- | --- |\n");
      failures()
          .forEach(
              (k, v) ->
                  md.append("| ")
                      .append(k)
                      .append(" | ")
                      .append(v)
                      .append(" | ")
                      .append(k.description())
                      .append(" |\n"));
    }
    md.append("\nContract failures (`UNPARSEABLE` plus `ANCHOR_NOT_FOUND`): ")
        .append(contractFailures())
        .append(" of ")
        .append(totalResults())
        .append(" results.\n");
    md.append("\n## Where luck decides\n\n");
    md.append(
        unstable().isEmpty()
            ? "No task changed outcome between runs.\n"
            : "Passed in some runs, failed in others: " + String.join(", ", unstable()) + ".\n");
    md.append("\n## Never passed\n\n");
    md.append(neverPassed().isEmpty() ? "None.\n" : String.join(", ", neverPassed()) + ".\n");
    md.append("\n## Cost\n\n");
    for (int i = 0; i < runs.size(); i++) {
      long tokens = tokens(i);
      md.append("- Run ")
          .append(i + 1)
          .append(": ")
          .append(runs.get(i).stream().mapToLong(TaskResult::millis).sum() / 1000)
          .append(" s of model and build time, ")
          .append(tokens < 0 ? "tokens not reported" : tokens + " tokens reported by the provider")
          .append("\n");
    }
    md.append("\nThe raw per-task results are in the `results-")
        .append(date)
        .append(".json` the run wrote to `target/eval/`.\n");
    return md.toString();
  }
}
