package com.javelin.ai.plan;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiConfig;
import com.javelin.ai.AiException;
import com.javelin.ai.AiProvider;
import com.javelin.ai.AiReply;
import com.javelin.ai.AiRequest;
import com.javelin.ai.FailureReason;
import com.javelin.ai.project.FileChange;
import com.javelin.ai.project.MultiFileReply;
import com.javelin.ai.project.ProjectFile;
import com.javelin.ai.project.ProjectProposal;
import com.javelin.ai.project.ProjectReader;
import com.javelin.ai.project.ProjectSnapshot;
import com.javelin.ai.project.ProjectWorkspace;
import com.javelin.ai.verify.RepairPrompt;
import com.javelin.ai.verify.Verification;
import com.javelin.ai.verify.VerificationLoop;
import com.javelin.ai.verify.Verified;
import com.javelin.ai.verify.Verifier;
import com.javelin.ai.verify.VerifyProblem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs a plan one step at a time. Each step is its own small request ({@link StepContext}, {@link
 * StepPrompt}) about one method, answered and read like a project request that names one file; what
 * it proposes is checked in memory ({@link Verifier}, with the repair loop and its retry budget)
 * before the next step starts, and the next step is shown the file as this one left it. Nothing is
 * written to the project: the steps pile up in memory and the result is one proposal for the usual
 * review.
 *
 * <p>A step that fails (nothing usable came back, or it still does not compile after its retries)
 * stops the run and shows what completed. One allowance is made so that a plan can change a
 * signature and then its callers: a compile error in a file that a <em>later</em> step is planned
 * to change does not fail the earlier step (it is the later step's job), and the last step is
 * judged on everything. The step limit is enforced here as well as by the validator, so a plan that
 * was edited or built by hand cannot run longer than the setting allows.
 */
public final class StepExecutor {
  /** Progress, on the worker thread: the model's text as it arrives and step boundaries. */
  public interface Listener extends AiProvider.StreamListener {
    default void stepStarted(int number, int total, PlanStep step) {}

    default void stepFinished(StepResult result) {}
  }

  private final AiProvider provider;
  private final AiConfig config;
  private final PlanConfig planConfig;
  private final java.util.function.LongSupplier clock;

  public StepExecutor(AiProvider provider, AiConfig config, PlanConfig planConfig) {
    this(provider, config, planConfig, System::currentTimeMillis);
  }

  StepExecutor(
      AiProvider provider,
      AiConfig config,
      PlanConfig planConfig,
      java.util.function.LongSupplier clock) {
    this.provider = provider;
    this.config = config;
    this.planConfig = planConfig;
    this.clock = clock;
  }

  /** The step limit this executor enforces. */
  public int maxSteps() {
    return planConfig.maxSteps();
  }

  private boolean structured() {
    return config.structuredEdits() && provider.supportsSchemaOutput();
  }

  // ---- what would be sent ----

  /**
   * What the steps would send, for the Send dialog: for each step what it sends (computed from the
   * project as it is now) and, for the first step, the exact system message and message. The text
   * of later steps is built when they run, from the files as the steps before them leave them, and
   * is made of the same kinds of part, so what is listed here is the most it can contain.
   *
   * @throws AiException REFUSED when the plan cannot run (see {@link PlanValidator})
   */
  public String preview(TaskPlan plan, ProjectWorkspace workspace) throws AiException {
    checkRunnable(plan, workspace);
    PlanValidator.TextSource texts = PlanValidator.sourceOf(workspace);
    StringBuilder out = new StringBuilder();
    out.append("Each step is a separate request to ").append(config.model()).append(".\n");
    out.append(
            "Per step, only the method it changes, its file's package, imports and signatures,\n")
        .append(
            "and the signatures of the project types it uses are sent. No other file is sent.\n");
    out.append(
        "After each step the proposed change is compiled in memory before the next begins.\n");
    out.append("\n----- steps -----\n");
    AiRequest first = null;
    for (int i = 0; i < plan.steps().size(); i++) {
      PlanStep step = plan.steps().get(i);
      out.append(i + 1).append(". ").append(step.line()).append('\n');
      try {
        StepContext context = StepContext.build(plan, i, workspace.index(), texts, Map.of());
        out.append("     sends: ").append(context.summary()).append('\n');
        if (first == null) first = StepPrompt.build(config.model(), plan, context, i, structured());
      } catch (AiException ex) {
        out.append("     sends: what an earlier step makes (read when it runs)\n");
      }
    }
    if (first != null) {
      out.append("\n----- system message (every step) -----\n").append(first.system());
      out.append("\n\n----- message of the first step -----\n").append(first.prompt()).append('\n');
    }
    return out.toString();
  }

  private void checkRunnable(TaskPlan plan, ProjectWorkspace workspace) throws AiException {
    int limit = planConfig.maxSteps();
    if (plan.steps().size() > limit)
      throw new AiException(
          AiException.Kind.REFUSED,
          FailureReason.OVERSIZED,
          "The plan has "
              + plan.steps().size()
              + " steps and the limit is "
              + limit
              + ", so it does not run.");
    PlanValidator.Check check = PlanValidator.check(plan, limit, workspace);
    if (!check.ok())
      throw new AiException(
          AiException.Kind.REFUSED,
          check.problems().get(0).reason(),
          "The plan cannot run:\n" + check.describe());
  }

  // ---- running ----

  /** The state of a run that its steps share. */
  private static final class Run {
    final Map<String, String> overlay = new LinkedHashMap<>();
    final Map<String, List<FileChange>> changes = new LinkedHashMap<>();
    final List<String> snapshotIds = new ArrayList<>();
    final List<String> explanations = new ArrayList<>();
    final List<String> problems = new ArrayList<>();
    final List<FailureReason> failures = new ArrayList<>();
  }

  private record StepAnswer(
      String text, ProjectProposal proposal, String note, FailureReason why) {}

  private record Outcome(StepResult result, List<FileChange> changes, ProjectProposal proposal) {}

  /**
   * Runs the plan. {@code verifier} may be null (no check); {@code retries} is the budget of each
   * step after a failed check.
   *
   * @throws AiException REFUSED when the plan is longer than the limit or does not validate; the
   *     user's cancel; or a provider that cannot be reached before any step completed. Any other
   *     failure of a step is a result, not an exception.
   */
  public PlanRun run(
      TaskPlan plan,
      ProjectWorkspace workspace,
      Listener listener,
      AiCancel cancel,
      Verifier verifier,
      int retries)
      throws AiException {
    checkRunnable(plan, workspace);
    PlanValidator.TextSource texts = PlanValidator.sourceOf(workspace);
    Run run = new Run();
    Map<String, ProjectSnapshot.Stamp> stamps = new LinkedHashMap<>();
    for (ProjectFile f : workspace.index().files())
      stamps.put(f.path(), new ProjectSnapshot.Stamp(f.size(), f.modified()));
    List<StepResult> results = new ArrayList<>();
    int total = plan.steps().size();
    boolean stopped = false;
    for (int i = 0; i < total; i++) {
      PlanStep step = plan.steps().get(i);
      if (stopped) {
        results.add(new StepResult(i + 1, step, StepResult.Status.NOT_RUN, null, "", "", 0));
        continue;
      }
      cancel.throwIfCancelled();
      listener.stepStarted(i + 1, total, step);
      Outcome outcome;
      try {
        outcome = runStep(plan, i, workspace, texts, run, listener, cancel, verifier, retries);
      } catch (AiException ex) {
        if (ex.kind() == AiException.Kind.CANCELLED) throw ex;
        if (results.stream().noneMatch(StepResult::done) && ex.reason() == null) throw ex;
        outcome =
            new Outcome(
                new StepResult(
                    i + 1, step, StepResult.Status.FAILED, ex.reason(), ex.getMessage(), "", 1),
                List.of(),
                null);
      }
      results.add(outcome.result());
      listener.stepFinished(outcome.result());
      if (outcome.result().done()) merge(run, outcome);
      else stopped = true;
    }
    ProjectSnapshot snapshot = snapshotOf(run, stamps);
    return new PlanRun(plan, results, proposalOf(run, snapshot), snapshot);
  }

  private Outcome runStep(
      TaskPlan plan,
      int index,
      ProjectWorkspace workspace,
      PlanValidator.TextSource texts,
      Run run,
      Listener listener,
      AiCancel cancel,
      Verifier verifier,
      int retries)
      throws AiException {
    PlanStep step = plan.steps().get(index);
    int total = plan.steps().size();
    StepContext context = StepContext.build(plan, index, workspace.index(), texts, run.overlay);
    AiRequest first = StepPrompt.build(config.model(), plan, context, index, structured());
    Map<String, String> sent = new LinkedHashMap<>();
    if (!context.newFile()) sent.put(step.file(), ProjectReader.hash(context.fileText()));
    ProjectSnapshot snapshot =
        new ProjectSnapshot(
            ProjectReader.hash(first.prompt()).substring(0, 12), clock.getAsLong(), Map.of(), sent);
    Set<String> later = new HashSet<>();
    for (int j = index + 1; j < total; j++) later.add(plan.steps().get(j).file());
    boolean last = index == total - 1;

    var loopStep =
        new VerificationLoop.Step<StepAnswer>() {
          @Override
          public StepAnswer ask(AiRequest request, int attempt) throws AiException {
            if (attempt > 1)
              listener.onText(
                  "\n\n----- step "
                      + (index + 1)
                      + ", attempt "
                      + attempt
                      + ": asking again with the problems -----\n\n");
            cancel.throwIfCancelled();
            AiReply reply = provider.completeStreaming(request, listener, cancel);
            cancel.throwIfCancelled();
            return answer(reply, step, snapshot, workspace, run.overlay);
          }

          @Override
          public void retryFailed(int attempt, AiException why) {
            listener.onText(
                "\n\n----- step "
                    + (index + 1)
                    + ", attempt "
                    + attempt
                    + " failed: "
                    + why.getMessage()
                    + " -----\n");
          }

          @Override
          public VerificationLoop.Checked check(StepAnswer answer, AiCancel c) {
            if (answer.proposal() == null || verifier == null) return null;
            FileChange change = answer.proposal().changes().get(0);
            Map<String, String> files = new LinkedHashMap<>(run.overlay);
            files.put(change.path(), change.replacement());
            Verification v = verifier.verify(files, c);
            if (!last) {
              int[] own = changedLines(change.original(), change.replacement());
              v = leavingLaterSteps(v, later, change.path(), own[0], own[1]);
            }
            String hunk;
            if (v.outcome() == Verification.Outcome.COMPILE_FAILED) {
              VerifyProblem p = v.problems().get(0);
              String text = files.get(p.path());
              hunk = text == null ? "" : p.path() + "\n" + RepairPrompt.hunkAround(text, p.line());
            } else {
              hunk = change.diff().text();
            }
            return new VerificationLoop.Checked(v, hunk);
          }
        };

    List<VerificationLoop.Attempt<StepAnswer>> attempts;
    if (verifier == null)
      attempts = List.of(new VerificationLoop.Attempt<>(1, loopStep.ask(first, 1), null));
    else attempts = VerificationLoop.run(first, retries, loopStep, cancel);
    Verified<StepAnswer> verified = Verified.of(attempts);
    var chosen = verified.chosen();
    StepAnswer answer = chosen.result();
    Verification v = chosen.verification();
    String checks = verified.report();
    int requests = verified.requests();
    if (answer.proposal() == null)
      return new Outcome(
          new StepResult(
              index + 1,
              step,
              StepResult.Status.FAILED,
              answer.why(),
              answer.note() == null ? "no change came back" : firstLine(answer.note()),
              checks,
              requests),
          List.of(),
          null);
    if (v != null && v.failed())
      return new Outcome(
          new StepResult(
              index + 1,
              step,
              StepResult.Status.FAILED,
              v.failure(),
              v.summary() + after(requests),
              checks,
              requests),
          List.of(),
          null);
    String summary = v == null ? "not checked" : v.summary();
    return new Outcome(
        new StepResult(
            index + 1,
            step,
            StepResult.Status.DONE,
            null,
            summary + after(requests),
            requests > 1 ? checks : "",
            requests),
        answer.proposal().changes(),
        answer.proposal());
  }

  private static String after(int requests) {
    return requests > 1 ? " after " + requests + " requests" : "";
  }

  private static String firstLine(String text) {
    int newline = text.indexOf('\n');
    return newline < 0 ? text : text.substring(0, newline);
  }

  /**
   * A compile error in a file that a later step changes is that step's to fix, not this one's: it
   * is set aside and said so in the check's note, and if every error is of that kind the check
   * passes. In the step's own file this holds only for errors outside the lines the step changed
   * ({@code firstLine} to {@code lastLine} of the new text, with a line of margin): a step that
   * changes a signature breaks the other methods of its file, which a later step of the same file
   * mends, but an error inside what the step wrote is its own. An error with no line is its own.
   */
  static Verification leavingLaterSteps(
      Verification v, Set<String> laterFiles, String ownFile, int firstLine, int lastLine) {
    if (v.outcome() != Verification.Outcome.COMPILE_FAILED || laterFiles.isEmpty()) return v;
    List<VerifyProblem> mine = new ArrayList<>();
    Set<String> left = new java.util.TreeSet<>();
    for (VerifyProblem p : v.problems()) {
      boolean later = laterFiles.contains(p.path());
      boolean inOwnChange =
          p.path() != null
              && p.path().equals(ownFile)
              && (p.line() <= 0 || (p.line() >= firstLine - 1 && p.line() <= lastLine + 1));
      if (later && !inOwnChange) left.add(p.path());
      else mine.add(p);
    }
    if (left.isEmpty()) return v;
    String note =
        (v.problems().size() - mine.size())
            + " problem(s) in files that later steps change: "
            + String.join(", ", left);
    if (mine.isEmpty())
      return new Verification(
          Verification.Outcome.PASSED, List.of(), 0, v.testsRequested(), v.millis(), note);
    return new Verification(
        Verification.Outcome.COMPILE_FAILED, mine, 0, v.testsRequested(), v.millis(), note);
  }

  /**
   * The first and last line (1-based) of {@code now} that differ from {@code was}, found by
   * skipping the lines the two share at the start and at the end. A new file is all changed.
   */
  static int[] changedLines(String was, String now) {
    List<String> a = was.lines().toList();
    List<String> b = now.lines().toList();
    int prefix = 0;
    while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) prefix++;
    int suffix = 0;
    while (suffix < a.size() - prefix
        && suffix < b.size() - prefix
        && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) suffix++;
    return new int[] {prefix + 1, Math.max(prefix + 1, b.size() - suffix)};
  }

  /** Reads one step's reply. Only a change to the step's own file is taken. */
  private StepAnswer answer(
      AiReply reply,
      PlanStep step,
      ProjectSnapshot snapshot,
      ProjectWorkspace workspace,
      Map<String, String> overlay)
      throws AiException {
    String text = reply.text();
    if (text.isBlank())
      throw new AiException(
          AiException.Kind.BAD_RESPONSE,
          FailureReason.NO_EDIT_BLOCK,
          "The model sent an empty answer.");
    if (reply.truncated())
      return new StepAnswer(
          text,
          null,
          "The reply was cut off by the model, so nothing is proposed for this step.",
          FailureReason.TRUNCATED);
    if (!MultiFileReply.looksLike(text))
      return new StepAnswer(
          text,
          null,
          "The model answered in words and proposed no change.",
          FailureReason.NO_EDIT_BLOCK);
    ProjectProposal proposal =
        MultiFileReply.parse(text, workspace.reader(), snapshot, workspace.options(), overlay);
    List<FileChange> mine = new ArrayList<>();
    List<String> problems = new ArrayList<>(proposal.problems());
    boolean other = false;
    for (FileChange c : proposal.changes()) {
      if (c.path().equals(step.file())) mine.add(c);
      else {
        other = true;
        problems.add(
            c.path()
                + ": this step changes only "
                + step.file()
                + ", so the change to this file is ignored.");
      }
    }
    String note = problems.isEmpty() ? null : String.join("\n", problems);
    if (mine.isEmpty()) {
      FailureReason why =
          !proposal.failures().isEmpty()
              ? proposal.failures().get(0)
              : other ? FailureReason.WRONG_FILE : FailureReason.NO_EDIT_BLOCK;
      return new StepAnswer(
          text,
          null,
          note == null
              ? "The reply holds no change to " + step.file() + "."
              : "No change to " + step.file() + " could be used: " + firstLine(note),
          why);
    }
    return new StepAnswer(
        text,
        new ProjectProposal(
            snapshot.id(), proposal.explanation(), mine, problems, proposal.failures()),
        note,
        null);
  }

  // ---- putting the steps together ----

  private void merge(Run run, Outcome outcome) {
    ProjectProposal p = outcome.proposal();
    String prefix = "Step " + outcome.result().number() + ": ";
    for (FileChange c : outcome.changes()) {
      run.changes.computeIfAbsent(c.path(), k -> new ArrayList<>()).add(c);
      run.overlay.put(c.path(), c.replacement());
    }
    run.snapshotIds.add(p.snapshotId());
    if (!p.explanation().isBlank())
      run.explanations.add(prefix + outcome.result().step().label() + ": " + p.explanation());
    for (String problem : p.problems()) run.problems.add(prefix + problem);
    run.failures.addAll(p.failures());
  }

  private static ProjectSnapshot snapshotOf(Run run, Map<String, ProjectSnapshot.Stamp> stamps) {
    Map<String, String> sent = new LinkedHashMap<>();
    for (var e : run.changes.entrySet()) {
      FileChange first = e.getValue().get(0);
      if (first.kind() == FileChange.Kind.MODIFY)
        sent.put(e.getKey(), ProjectReader.hash(first.original()));
    }
    String id = ProjectReader.hash(String.join(",", run.snapshotIds));
    return new ProjectSnapshot(id.substring(0, 12), 0L, stamps, sent);
  }

  /**
   * One proposal for the whole plan. A file changed by one step keeps that step's change as it is;
   * a file changed by several becomes a whole-file change from its first original to its last text
   * (which the review applies only if the file on disk is still the original: it cannot be moved
   * onto different text).
   */
  private static ProjectProposal proposalOf(Run run, ProjectSnapshot snapshot) {
    List<FileChange> merged = new ArrayList<>();
    for (var e : run.changes.entrySet()) {
      List<FileChange> steps = e.getValue();
      if (steps.size() == 1) {
        merged.add(steps.get(0));
        continue;
      }
      FileChange first = steps.get(0);
      List<com.javelin.ai.Finding> findings = new ArrayList<>();
      List<com.javelin.ai.EditBlocks.Anchor> anchors = new ArrayList<>();
      StringBuilder raw = new StringBuilder();
      for (FileChange c : steps) {
        for (var f : c.findings()) if (!findings.contains(f)) findings.add(f);
        anchors.addAll(c.anchors());
        raw.append(c.rawSection()).append('\n');
      }
      merged.add(
          new FileChange(
              first.path(),
              first.kind(),
              first.original(),
              steps.get(steps.size() - 1).replacement(),
              raw.toString(),
              com.javelin.ai.ChangeReply.Format.WHOLE_BLOCK,
              first.inContext(),
              findings,
              anchors));
    }
    return new ProjectProposal(
        snapshot.id(), String.join("\n", run.explanations), merged, run.problems, run.failures);
  }
}
