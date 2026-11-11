package com.javelin.ui.ai;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiConfig;
import com.javelin.ai.AiException;
import com.javelin.ai.AiProvider;
import com.javelin.ai.plan.PlanConfig;
import com.javelin.ai.plan.PlanReview;
import com.javelin.ai.plan.PlanRun;
import com.javelin.ai.plan.Planner;
import com.javelin.ai.plan.StepExecutor;
import com.javelin.ai.plan.StepResult;
import com.javelin.ai.plan.TaskPlan;
import com.javelin.ai.profile.ModelProfile;
import com.javelin.ai.profile.ModelProfiles;
import com.javelin.ai.project.Adjustments;
import com.javelin.ai.project.AppliedChanges;
import com.javelin.ai.project.ContextMode;
import com.javelin.ai.project.ProjectAiConfig;
import com.javelin.ai.project.ProjectAiResult;
import com.javelin.ai.project.ProjectAiService;
import com.javelin.ai.project.ProjectPlan;
import com.javelin.ai.project.ProjectProposal;
import com.javelin.ai.project.ProjectReader;
import com.javelin.ai.project.ProjectRefresh;
import com.javelin.ai.project.ProjectWorkspace;
import com.javelin.ai.project.ProposalApplier;
import com.javelin.ai.project.ScanOptions;
import com.javelin.ai.project.TokenCalibration;
import com.javelin.ai.symbols.SymbolConfig;
import com.javelin.ai.verify.JavacVerifier;
import com.javelin.ai.verify.ProjectClasspath;
import com.javelin.ai.verify.Verified;
import com.javelin.ai.verify.Verifier;
import com.javelin.ai.verify.VerifyConfig;
import com.javelin.ui.Dialogs;
import com.javelin.ui.editor.EditorTabs;
import com.javelin.ui.shell.BottomPanel;
import com.javelin.ui.shell.Workbench;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

/**
 * AI > Project Request...: a request about the whole open project, and AI > Undo Last Project
 * Change. It follows the rules of AI.md like the single-file actions and adds two: the user sees
 * every file that would be sent (and can change that) before anything is, and a multi-file proposal
 * is reviewed file by file and written only for the files the user ticks. The logic is in {@code
 * com.javelin.ai.project}; this class is the window.
 */
final class ProjectAiController {
  private final AiController parent;
  private final Workbench bench;
  private final EditorTabs editors;
  private final BottomPanel bottom;

  private ProjectWorkspace workspace; // worker thread, then FX thread after it finished

  /** The profile and provider of the request in progress (FX thread only). */
  private ModelProfile profile = ModelProfile.SMALL;

  private AiProvider provider;
  private AppliedChanges lastApplied; // FX thread only
  private ProjectReader lastReader;

  /** What the model's own token counts have taught this session; shared by every request. */
  private final TokenCalibration calibration = new TokenCalibration();

  ProjectAiController(
      AiController parent, Workbench bench, EditorTabs editors, BottomPanel bottom) {
    this.parent = parent;
    this.bench = bench;
    this.editors = editors;
    this.bottom = bottom;
  }

  // ---- asking ----

  void request() {
    if (parent.busy()) return;
    if (!bench.requireProject("Project Request")) return;
    AiConfig base = AiConfig.from(bench.settings());
    AiProvider chosen = parent.providerFor(base);
    if (chosen == null) return;
    if (!base.hasModel()) {
      parent.chooseModel(this::request);
      return;
    }
    provider = chosen;
    profile = ModelProfiles.resolve(bench.settings(), chosen.isLocal());
    AiConfig config = ModelProfiles.apply(base, bench.settings(), profile);
    PlanConfig planConfig = PlanConfig.from(bench.settings(), profile.maxSteps());
    Optional<ProjectAiDialogs.Request> asked =
        ProjectAiDialogs.ask(planConfig.enabled(), planConfig.maxSteps());
    if (asked.isEmpty()) return;
    if (planConfig.enabled()) {
      plan(provider, config, planConfig, asked.get().instruction());
      return;
    }
    ProjectAiService service =
        new ProjectAiService(
            provider,
            config,
            ProjectAiConfig.from(bench.settings()),
            SymbolConfig.from(bench.settings(), profile.symbolBudget()),
            calibration);
    prepare(service, asked.get().mode(), asked.get().instruction(), Adjustments.NONE);
  }

  /** Looks at the project and works out the plan on the worker, then shows it. */
  private void prepare(
      ProjectAiService service, ContextMode mode, String instruction, Adjustments adjustments) {
    File root = bench.projectDirectory();
    AiCancel cancel = parent.begin("Project Request: looking at the project...");
    parent
        .worker()
        .submit(
            () -> {
              try {
                ProjectWorkspace ws = workspaceFor(root.toPath(), service.projectConfig().scan());
                ProjectPlan plan = service.prepare(ws, mode, instruction, adjustments, cancel);
                Platform.runLater(
                    () -> {
                      parent.end(cancel);
                      confirm(service, plan, adjustments);
                    });
              } catch (AiException ex) {
                Platform.runLater(() -> prepareFailed(cancel, ex));
              } catch (IOException | RuntimeException ex) {
                Platform.runLater(() -> readFailed(cancel, ex));
              }
            });
  }

  private void prepareFailed(AiCancel cancel, AiException ex) {
    parent.end(cancel);
    if (ex.kind() == AiException.Kind.CANCELLED) parent.report(ex);
    else bench.showError("Project Request", ex.getMessage());
  }

  private void readFailed(AiCancel cancel, Exception ex) {
    parent.end(cancel);
    bench.showError("Project Request", "The project could not be read: " + ex.getMessage());
  }

  private ProjectWorkspace workspaceFor(Path root, ScanOptions scan) throws IOException {
    if (workspace != null && workspace.root().equals(root.toRealPath())) workspace.refresh(scan);
    else workspace = new ProjectWorkspace(root, scan);
    return workspace;
  }

  private void confirm(ProjectAiService service, ProjectPlan plan, Adjustments adjustments) {
    VerifyConfig verify = VerifyConfig.from(bench.settings(), profile.retries());
    ProjectAiDialogs.Decision decision =
        ProjectAiDialogs.confirm(plan, adjustments, verify.appendedTo(plan.preview(), true));
    switch (decision.choice()) {
      case CANCEL -> bench.status("Project request not sent.");
      case UPDATE -> prepare(service, plan.mode(), plan.instruction(), decision.adjustments());
      case SEND -> {
        if (parent.externalConsent(provider, plan.preview())) send(service, plan, verify);
        else bench.status("Project request not sent.");
      }
    }
  }

  // ---- a planned request (ai.plan) ----

  /**
   * Plan, then run: the planning request (names and signatures only) is shown and sent, the plan
   * that comes back is shown for editing, and each step then sends only its own method. Every
   * request is approved before it is sent; the result is reviewed like any project change.
   */
  private void plan(
      AiProvider provider, AiConfig config, PlanConfig planConfig, String instruction) {
    Planner planner = new Planner(provider, config, planConfig);
    File root = bench.projectDirectory();
    ScanOptions scan = ProjectAiConfig.from(bench.settings()).scan();
    AiCancel cancel = parent.begin("Project Request: looking at the project...");
    parent
        .worker()
        .submit(
            () -> {
              try {
                ProjectWorkspace ws = workspaceFor(root.toPath(), scan);
                Planner.PlanRequest prepared = planner.prepare(ws, instruction, cancel);
                Platform.runLater(
                    () -> {
                      parent.end(cancel);
                      confirmPlanRequest(provider, config, planConfig, planner, ws, prepared);
                    });
              } catch (AiException ex) {
                Platform.runLater(() -> prepareFailed(cancel, ex));
              } catch (IOException | RuntimeException ex) {
                Platform.runLater(() -> readFailed(cancel, ex));
              }
            });
  }

  private void confirmPlanRequest(
      AiProvider provider,
      AiConfig config,
      PlanConfig planConfig,
      Planner planner,
      ProjectWorkspace ws,
      Planner.PlanRequest prepared) {
    if (!ProjectAiDialogs.confirmPlanRequest(prepared)
        || !parent.externalConsent(provider, prepared.preview())) {
      bench.status("Project request not sent.");
      return;
    }
    AiCancel cancel =
        parent.begin("Project Request: planning with " + prepared.indicator() + "...");
    parent.statusLabel().setText("AI > " + provider.displayName() + " " + provider.location());
    bottom.ai().begin("Project request, the plan: " + prepared.instruction(), prepared.indicator());
    bottom.show(bottom.aiTab());
    parent
        .worker()
        .submit(
            () -> {
              try {
                Planner.Planned planned = planner.plan(prepared, ws, streamToPanel(), cancel);
                Platform.runLater(
                    () ->
                        planned(
                            cancel,
                            new StepExecutor(provider, config, planConfig),
                            planConfig,
                            ws,
                            prepared,
                            planned));
              } catch (AiException ex) {
                Platform.runLater(() -> failed(cancel, ex.getMessage(), ex));
              } catch (RuntimeException ex) {
                Platform.runLater(() -> failed(cancel, "Unexpected error: " + ex, null));
              }
            });
  }

  private void planned(
      AiCancel cancel,
      StepExecutor executor,
      PlanConfig planConfig,
      ProjectWorkspace ws,
      Planner.PlanRequest prepared,
      Planner.Planned planned) {
    parent.end(cancel);
    bottom
        .ai()
        .finish(
            planned.plan() == null
                ? "No plan: " + planned.note()
                : "The plan has " + planned.plan().steps().size() + " step(s).");
    Optional<TaskPlan> chosen =
        ProjectAiDialogs.editPlan(
            prepared.indicator(),
            planned.note(),
            planned.plan() == null ? "" : planned.plan().text(),
            planConfig.maxSteps(),
            text -> PlanReview.review(prepared.instruction(), text, ws, executor));
    if (chosen.isEmpty()) {
      bench.status("The plan was not sent: nothing was changed.");
      return;
    }
    runPlan(executor, chosen.get(), ws, prepared);
  }

  private void runPlan(
      StepExecutor executor, TaskPlan plan, ProjectWorkspace ws, Planner.PlanRequest prepared) {
    VerifyConfig verify = VerifyConfig.from(bench.settings(), profile.retries());
    AiCancel cancel = parent.begin("Project Request: running the plan...");
    bottom
        .ai()
        .begin(
            "Project request, " + plan.steps().size() + " step(s): " + plan.instruction(),
            prepared.indicator());
    bottom.show(bottom.aiTab());
    parent
        .worker()
        .submit(
            () -> {
              try {
                Verifier verifier =
                    verify.enabled()
                        ? new JavacVerifier(
                            ws.root(),
                            ProjectClasspath.resolve(
                                ws.root(), ProjectClasspath.defaultRepository()),
                            verify)
                        : null;
                PlanRun run =
                    executor.run(
                        plan,
                        ws,
                        new StepExecutor.Listener() {
                          @Override
                          public void onText(String piece) {
                            Platform.runLater(() -> bottom.ai().append(piece));
                          }

                          @Override
                          public void onThinking(String piece) {
                            Platform.runLater(() -> bottom.ai().appendThinking(piece));
                          }

                          @Override
                          public void stepStarted(
                              int number, int total, com.javelin.ai.plan.PlanStep step) {
                            Platform.runLater(
                                () ->
                                    bottom
                                        .ai()
                                        .append(
                                            "\n\n===== Step "
                                                + number
                                                + " of "
                                                + total
                                                + ": "
                                                + step.label()
                                                + " =====\n"));
                          }

                          @Override
                          public void stepFinished(StepResult result) {
                            Platform.runLater(
                                () -> bottom.ai().append("\n----- " + result.line() + " -----\n"));
                          }
                        },
                        cancel,
                        verifier,
                        verify.retries());
                ProjectAiResult result = run.asResult();
                ProjectRefresh.Result fresh =
                    ProjectRefresh.check(result.snapshot(), ws.index(), ws.reader(), ws.options());
                Platform.runLater(() -> finished(cancel, ws, result, fresh, ""));
              } catch (AiException ex) {
                Platform.runLater(() -> failed(cancel, ex.getMessage(), ex));
              } catch (IOException | RuntimeException ex) {
                Platform.runLater(() -> failed(cancel, "Unexpected error: " + ex, null));
              }
            });
  }

  private AiProvider.StreamListener streamToPanel() {
    return new AiProvider.StreamListener() {
      @Override
      public void onText(String piece) {
        Platform.runLater(() -> bottom.ai().append(piece));
      }

      @Override
      public void onThinking(String piece) {
        Platform.runLater(() -> bottom.ai().appendThinking(piece));
      }
    };
  }

  // ---- sending ----

  private void send(ProjectAiService service, ProjectPlan plan, VerifyConfig verify) {
    ProjectWorkspace ws = workspace;
    AiCancel cancel = parent.begin("Project Request: waiting for " + plan.indicator() + "...");
    parent.statusLabel().setText("AI > " + plan.providerName() + " " + plan.location());
    bottom.ai().begin("Project request: " + plan.instruction(), plan.indicator());
    bottom.show(bottom.aiTab());
    parent
        .worker()
        .submit(
            () -> {
              try {
                Verifier verifier =
                    verify.enabled()
                        ? new JavacVerifier(
                            ws.root(),
                            ProjectClasspath.resolve(
                                ws.root(), ProjectClasspath.defaultRepository()),
                            verify)
                        : null;
                Verified<ProjectAiResult> verified =
                    service.runVerified(
                        plan,
                        ws,
                        new AiProvider.StreamListener() {
                          @Override
                          public void onText(String piece) {
                            Platform.runLater(() -> bottom.ai().append(piece));
                          }

                          @Override
                          public void onThinking(String piece) {
                            Platform.runLater(() -> bottom.ai().appendThinking(piece));
                          }
                        },
                        cancel,
                        verifier,
                        verify.retries());
                ProjectAiResult result = verified.answer();
                ProjectRefresh.Result fresh =
                    ProjectRefresh.check(result.snapshot(), ws.index(), ws.reader(), ws.options());
                String verification = verified.report();
                Platform.runLater(() -> finished(cancel, ws, result, fresh, verification));
              } catch (AiException ex) {
                Platform.runLater(() -> failed(cancel, ex.getMessage(), ex));
              } catch (IOException | RuntimeException ex) {
                Platform.runLater(() -> failed(cancel, "Unexpected error: " + ex, null));
              }
            });
  }

  private void failed(AiCancel cancel, String message, AiException ex) {
    parent.end(cancel);
    bottom.ai().fail(message);
    if (ex != null) parent.report(ex);
    else bench.status("AI: " + message);
  }

  private void finished(
      AiCancel cancel,
      ProjectWorkspace ws,
      ProjectAiResult result,
      ProjectRefresh.Result fresh,
      String verification) {
    parent.end(cancel);
    String note =
        verification.isBlank()
            ? result.note()
            : result.note() == null || result.note().isBlank()
                ? verification
                : result.note() + "\n\n" + verification;
    bottom.ai().finish(note);
    ProjectProposal proposal = result.proposal();
    if (proposal == null) {
      bench.status(
          "Project request: " + (result.note() == null ? "done." : firstLine(result.note())));
      return;
    }
    String warning = null;
    if (fresh.isStale())
      warning =
          "These files changed after the project was sent: "
              + String.join(", ", fresh.changedSentFiles())
              + ". The checks below use their current text.";
    List<ProposalApplier.Check> checks =
        ProposalApplier.check(ws.reader(), proposal, unsavedPaths(ws.root()), ws.options());
    Optional<Set<String>> approved = ProjectAiDialogs.review(proposal, checks, note, warning);
    if (approved.isEmpty()) {
      bench.status("Proposed changes rejected: nothing was changed.");
      return;
    }
    apply(ws, checks, approved.get());
  }

  private void apply(
      ProjectWorkspace ws, List<ProposalApplier.Check> checks, Set<String> approved) {
    try {
      AppliedChanges applied = ProposalApplier.apply(ws.reader(), checks, approved);
      if (applied.isEmpty()) {
        bench.status("Nothing was applied.");
        return;
      }
      lastApplied = applied;
      lastReader = ws.reader();
      editors.reloadChangedFiles();
      bench.status(
          "Applied the AI's changes to "
              + applied.paths().size()
              + " file(s). AI > Undo Last Project Change takes them back.");
    } catch (IOException ex) {
      bench.showError("Project Request", ex.getMessage());
    }
  }

  // ---- taking it back ----

  void undo() {
    if (lastApplied == null || lastReader == null) {
      bench.status("There is no project change to undo.");
      return;
    }
    Alert confirm =
        new Alert(
            Alert.AlertType.CONFIRMATION,
            "Put back "
                + lastApplied.paths().size()
                + " file(s) as they were before the AI's change?\n\n"
                + String.join("\n", lastApplied.paths())
                + "\n\nA file you edited since is left as it is.",
            ButtonType.OK,
            ButtonType.CANCEL);
    confirm.setTitle("Undo Last Project Change");
    confirm.setHeaderText(null);
    if (Dialogs.show(confirm).filter(ButtonType.OK::equals).isEmpty()) {
      bench.status("Undo cancelled.");
      return;
    }
    AppliedChanges.Rollback rollback = lastApplied.rollback(lastReader);
    lastApplied = null;
    lastReader = null;
    editors.reloadChangedFiles();
    if (rollback.skipped().isEmpty())
      bench.status("Undid the AI's change to " + rollback.restored().size() + " file(s).");
    else
      bench.showError(
          "Undo Last Project Change",
          "Put back: "
              + rollback.restored().size()
              + " file(s).\nLeft as they are:\n"
              + String.join("\n", rollback.skipped()));
  }

  // ---- helpers ----

  private Set<String> unsavedPaths(Path root) {
    Set<String> out = new HashSet<>();
    for (EditorTabs.DirtyBuffer buffer : editors.dirtyBuffers()) {
      if (buffer.file() == null) continue;
      try {
        Path real = buffer.file().toPath().toRealPath();
        if (real.startsWith(root))
          out.add(root.relativize(real).toString().replace(File.separatorChar, '/'));
      } catch (IOException ex) {
        // a buffer whose file is gone cannot be the target of a change to an existing file
      }
    }
    return out;
  }

  private static String firstLine(String text) {
    int newline = text.indexOf('\n');
    return newline < 0 ? text : text.substring(0, newline);
  }
}
