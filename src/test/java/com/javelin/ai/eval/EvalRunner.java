package com.javelin.ai.eval;

import com.javelin.ai.AiAction;
import com.javelin.ai.AiCancel;
import com.javelin.ai.AiConfig;
import com.javelin.ai.AiException;
import com.javelin.ai.AiPlan;
import com.javelin.ai.AiProposal;
import com.javelin.ai.AiProvider;
import com.javelin.ai.AiResult;
import com.javelin.ai.AiService;
import com.javelin.ai.AiSource;
import com.javelin.ai.FailureReason;
import com.javelin.ai.Sampling;
import com.javelin.ai.project.Adjustments;
import com.javelin.ai.project.FileChange;
import com.javelin.ai.project.ProjectAiConfig;
import com.javelin.ai.project.ProjectAiResult;
import com.javelin.ai.project.ProjectAiService;
import com.javelin.ai.project.ProjectPlan;
import com.javelin.ai.project.ProjectProposal;
import com.javelin.ai.project.ProjectWorkspace;
import com.javelin.ai.project.ProposalApplier;
import com.javelin.ai.project.ScanOptions;
import com.javelin.ai.verify.JavacVerifier;
import com.javelin.ai.verify.ProjectClasspath;
import com.javelin.ai.verify.Verifier;
import com.javelin.ai.verify.VerifyConfig;
import com.javelin.ai.verify.VerifyTarget;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs tasks through the editor's own AI pipeline ({@link AiService} for a selection or generated
 * tests, {@link ProjectAiService} for a project request), applies what comes out the way the editor
 * would, then asks the compiler and the tests whether the result works. Nothing here judges the
 * model's words: a task passes when the judging test passes.
 *
 * <p>Every task gets a fresh copy of its fixture, and nothing outside the scratch folder is
 * touched. Phase 0 makes one attempt per task and never retries unless asked for retries (Phase 2);
 * {@code attempts} is recorded so the later phases, which do retry, report in the same shape.
 */
final class EvalRunner {
  /**
   * The eval cannot go on: the provider is not running or the model is not installed. Counting that
   * as a failure of every task would publish a meaningless baseline.
   */
  static final class EvalAbort extends RuntimeException {
    private static final long serialVersionUID = 1L;

    EvalAbort(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private static final Pattern CLASS_DECLARATION =
      Pattern.compile("(?m)^\\s*(?:public\\s+)?(?:final\\s+)?(?:abstract\\s+)?class\\s+(\\w+)");
  private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

  private final AiProvider provider;
  private final EvalProfile profile;
  private final Path scratch;
  private final int retries;

  /** One attempt per task, as in Phase 0. */
  EvalRunner(AiProvider provider, EvalProfile profile, Path scratch) {
    this(provider, profile, scratch, -1);
  }

  /**
   * With {@code retries >= 0} a proposal is compiled in memory by the editor's own verifier and, if
   * that fails, the model is asked again with the compiler's messages, up to {@code retries} more
   * times (Phase 2). The judging below is unchanged and does not use the verifier.
   */
  EvalRunner(AiProvider provider, EvalProfile profile, Path scratch, int retries) {
    this.provider = provider;
    this.profile = profile;
    this.scratch = scratch;
    this.retries = retries;
  }

  int retries() {
    return retries;
  }

  private Verifier verifier(Path project) {
    return new JavacVerifier(
        project,
        new ProjectClasspath.Resolved(FixtureBuild.libraries(), true, ""),
        new VerifyConfig(true, retries, false, 30));
  }

  /** One run over all tasks; run number {@code run} uses seed {@code seed + run}. */
  List<TaskResult> runAll(List<EvalTask> tasks, long seed, int run, Consumer<String> progress) {
    List<TaskResult> results = new ArrayList<>();
    for (EvalTask task : tasks) {
      TaskResult r = run(task, seed + run);
      results.add(r);
      progress.accept(
          "run "
              + (run + 1)
              + " "
              + task.id()
              + ": "
              + (r.passed() ? "pass" : "FAIL " + r.failure())
              + " ("
              + r.millis() / 1000.0
              + " s)");
    }
    return results;
  }

  TaskResult run(EvalTask task, long seed) {
    long started = System.nanoTime();
    MeteredProvider metered = new MeteredProvider(provider, Sampling.deterministic(seed));
    try {
      Path work = Files.createTempDirectory(scratch, task.id() + "-");
      Path project = work.resolve("project");
      Trees.copy(task.before(), project);
      Applied applied = ask(task, metered, project);
      if (!applied.ok())
        return result(
            task, metered, started, applied, false, false, applied.failure(), applied.detail());
      if (applied.changedLines() > task.maxChangedLines())
        return result(
            task,
            metered,
            started,
            applied,
            false,
            false,
            FailureReason.OVERSIZED,
            "the change is "
                + applied.changedLines()
                + " lines; the limit for this task is "
                + task.maxChangedLines());
      FixtureBuild.Compiled compiled = FixtureBuild.compile(project, work.resolve("build"));
      if (!compiled.ok())
        return result(
            task,
            metered,
            started,
            applied,
            false,
            false,
            FailureReason.COMPILE_ERROR,
            String.join(" | ", compiled.errors().stream().limit(3).toList()));
      String failure = judge(task, project, applied, work, compiled);
      return result(
          task,
          metered,
          started,
          applied,
          true,
          failure == null,
          failure == null ? null : FailureReason.TEST_FAILURE,
          failure == null ? "" : failure);
    } catch (AiException ex) {
      return fromException(task, metered, started, ex);
    } catch (IOException ex) {
      throw new java.io.UncheckedIOException(ex);
    }
  }

  // ---- asking the pipeline ----

  /** What the pipeline produced and the editor would have written. */
  private record Applied(
      boolean ok,
      FailureReason failure,
      String detail,
      int changedLines,
      boolean flagged,
      List<String> addedTestFiles) {
    static Applied none(FailureReason reason, String detail) {
      return new Applied(
          false,
          reason == null ? FailureReason.NO_EDIT_BLOCK : reason,
          detail,
          0,
          false,
          List.of());
    }
  }

  private Applied ask(EvalTask task, AiProvider metered, Path project)
      throws AiException, IOException {
    AiConfig config = profile.aiConfig();
    return switch (task.mode()) {
      case SELECTION -> askForChange(task, metered, config, project);
      case GENERATE_TESTS -> askForTests(task, metered, config, project);
      case PROJECT -> askProject(task, metered, config, project);
    };
  }

  private Applied askForChange(EvalTask task, AiProvider metered, AiConfig config, Path project)
      throws AiException, IOException {
    Path file = project.resolve(task.file());
    String text = Files.readString(file);
    int[] range = Trees.selection(text, task.selectFrom(), task.selectTo());
    String name = file.getFileName().toString();
    AiSource source = new AiSource(name, name, "java", text, range[0], range[1]);
    AiService service = new AiService(metered, config);
    AiPlan plan = service.prepare(AiAction.PROPOSE_CHANGE, source, task.request());
    AiResult result =
        retries < 0
            ? service.run(plan, s -> {}, new AiCancel())
            : service
                .runVerified(
                    plan,
                    s -> {},
                    new AiCancel(),
                    new VerifyTarget(task.file(), text),
                    verifier(project),
                    retries)
                .answer();
    AiProposal proposal = result.proposal();
    if (proposal == null) return Applied.none(result.failure(), result.note());
    Files.writeString(file, proposal.applyTo(text));
    var diff = proposal.diff();
    return new Applied(
        true, null, "", diff.added() + diff.removed(), result.hasErrors(), List.of());
  }

  private Applied askForTests(EvalTask task, AiProvider metered, AiConfig config, Path project)
      throws AiException, IOException {
    Path file = project.resolve(task.file());
    String name = file.getFileName().toString();
    AiSource source = new AiSource(name, name, "java", Files.readString(file), 0, 0);
    AiService service = new AiService(metered, config);
    AiPlan plan = service.prepare(AiAction.GENERATE_TESTS, source, "");
    AiResult result = service.run(plan, s -> {}, new AiCancel());
    AiProposal proposal = result.proposal();
    if (proposal == null) return Applied.none(result.failure(), result.note());
    String relative =
        task.file().replace("src/main/java/", "src/test/java/").replaceFirst("[^/]+$", "")
            + proposal.suggestedName();
    Trees.write(project, relative, proposal.replacement());
    var diff = proposal.diff();
    return new Applied(
        true, null, "", diff.added() + diff.removed(), result.hasErrors(), List.of(relative));
  }

  private Applied askProject(EvalTask task, AiProvider metered, AiConfig config, Path project)
      throws AiException, IOException {
    ScanOptions scan = ScanOptions.defaults();
    ProjectWorkspace workspace = new ProjectWorkspace(project, scan);
    ProjectAiService service =
        new ProjectAiService(
            metered,
            config,
            new ProjectAiConfig(profile.contextTokens(), profile.reserveTokens(), scan));
    AiCancel cancel = new AiCancel();
    ProjectPlan plan =
        service.prepare(workspace, profile.contextMode(), task.request(), Adjustments.NONE, cancel);
    ProjectAiResult result =
        retries < 0
            ? service.run(plan, workspace, s -> {}, cancel)
            : service
                .runVerified(plan, workspace, s -> {}, cancel, verifier(project), retries)
                .answer();
    ProjectProposal proposal = result.proposal();
    if (proposal == null) return Applied.none(result.failure(), result.note());
    List<ProposalApplier.Check> checks =
        ProposalApplier.check(workspace.reader(), proposal, Set.of(), scan);
    Set<String> approved = new HashSet<>();
    for (ProposalApplier.Check c : checks) {
      if (!c.status().applicable())
        return Applied.none(FailureReason.WRONG_FILE, c.change().path() + " " + c.message());
      approved.add(c.change().path());
    }
    ProposalApplier.apply(workspace.reader(), checks, approved);
    int lines = 0;
    boolean flagged = false;
    for (FileChange change : proposal.changes()) {
      lines += change.diff().added() + change.diff().removed();
      flagged |= change.hasErrors();
    }
    return new Applied(true, null, "", lines, flagged, List.of());
  }

  // ---- judging ----

  /** Null when the task passed, otherwise what was wrong. */
  private String judge(
      EvalTask task, Path project, Applied applied, Path work, FixtureBuild.Compiled compiled)
      throws IOException {
    if (task.mode() != EvalTask.Mode.GENERATE_TESTS)
      return nullIfEmpty(FixtureBuild.run(compiled.classes(), task.tests()).describe());
    List<String> classes = new ArrayList<>();
    for (String relative : applied.addedTestFiles())
      classes.addAll(declaredClasses(project, relative));
    if (classes.isEmpty()) return "the generated file declares no class";
    FixtureBuild.TestRun onRealCode = FixtureBuild.run(compiled.classes(), classes);
    if (!onRealCode.ok())
      return "the generated tests fail on the real code: " + onRealCode.describe();
    Path mutantProject = work.resolve("mutant");
    Trees.copy(project, mutantProject);
    Trees.overlay(task.mutant(), mutantProject);
    FixtureBuild.Compiled mutant =
        FixtureBuild.compile(mutantProject, work.resolve("mutant-build"));
    if (!mutant.ok())
      throw new IllegalStateException(
          task.id() + ": the mutant does not compile: " + mutant.errors());
    FixtureBuild.TestRun onMutant = FixtureBuild.run(mutant.classes(), classes);
    if (onMutant.crash() != null || onMutant.timedOut() || !onMutant.failed().isEmpty())
      return null;
    return "the generated tests pass on the real code and also on the code with the planted bug";
  }

  private static String nullIfEmpty(String s) {
    return s.isEmpty() ? null : s;
  }

  private static List<String> declaredClasses(Path project, String relative) throws IOException {
    String text = Files.readString(project.resolve(relative));
    Matcher pkg = PACKAGE.matcher(text);
    String prefix = pkg.find() ? pkg.group(1) + "." : "";
    List<String> names = new ArrayList<>();
    Matcher m = CLASS_DECLARATION.matcher(text);
    while (m.find()) names.add(prefix + m.group(1));
    return names;
  }

  // ---- results ----

  private static TaskResult result(
      EvalTask task,
      MeteredProvider metered,
      long started,
      Applied applied,
      boolean compiled,
      boolean passed,
      FailureReason failure,
      String detail) {
    return new TaskResult(
        task.id(),
        task.category(),
        applied.ok(),
        compiled,
        passed,
        metered.calls(),
        (System.nanoTime() - started) / 1_000_000,
        metered.promptTokens(),
        metered.completionTokens(),
        applied.changedLines(),
        applied.flagged(),
        failure,
        detail == null ? "" : detail);
  }

  private static TaskResult fromException(
      EvalTask task, MeteredProvider metered, long started, AiException ex) {
    FailureReason reason = ex.reason();
    if (reason == null)
      throw new EvalAbort(
          "The eval stopped at " + task.id() + ": " + ex.getMessage() + " (" + ex.kind() + ")", ex);
    return result(
        task,
        metered,
        started,
        Applied.none(reason, ex.getMessage()),
        false,
        false,
        reason,
        ex.getMessage());
  }
}
