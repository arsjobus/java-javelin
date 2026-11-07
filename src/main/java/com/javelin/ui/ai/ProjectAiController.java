package com.javelin.ui.ai;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiConfig;
import com.javelin.ai.AiException;
import com.javelin.ai.AiProvider;
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
  private AppliedChanges lastApplied; // FX thread only
  private ProjectReader lastReader;

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
    AiConfig config = AiConfig.from(bench.settings());
    AiProvider provider = parent.providerFor(config);
    if (provider == null) return;
    if (!config.hasModel()) {
      parent.chooseModel(this::request);
      return;
    }
    Optional<ProjectAiDialogs.Request> asked = ProjectAiDialogs.ask();
    if (asked.isEmpty()) return;
    ProjectAiService service =
        new ProjectAiService(provider, config, ProjectAiConfig.from(bench.settings()));
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
                ProjectWorkspace ws = workspaceFor(root.toPath(), service);
                ProjectPlan plan = service.prepare(ws, mode, instruction, adjustments, cancel);
                Platform.runLater(
                    () -> {
                      parent.end(cancel);
                      confirm(service, plan, adjustments);
                    });
              } catch (AiException ex) {
                Platform.runLater(
                    () -> {
                      parent.end(cancel);
                      if (ex.kind() == AiException.Kind.CANCELLED) parent.report(ex);
                      else bench.showError("Project Request", ex.getMessage());
                    });
              } catch (IOException | RuntimeException ex) {
                Platform.runLater(
                    () -> {
                      parent.end(cancel);
                      bench.showError(
                          "Project Request", "The project could not be read: " + ex.getMessage());
                    });
              }
            });
  }

  private ProjectWorkspace workspaceFor(Path root, ProjectAiService service) throws IOException {
    if (workspace != null && workspace.root().equals(root.toRealPath()))
      workspace.refresh(service.projectConfig().scan());
    else workspace = new ProjectWorkspace(root, service.projectConfig().scan());
    return workspace;
  }

  private void confirm(ProjectAiService service, ProjectPlan plan, Adjustments adjustments) {
    ProjectAiDialogs.Decision decision = ProjectAiDialogs.confirm(plan, adjustments);
    switch (decision.choice()) {
      case CANCEL -> bench.status("Project request not sent.");
      case UPDATE -> prepare(service, plan.mode(), plan.instruction(), decision.adjustments());
      case SEND -> send(service, plan);
    }
  }

  // ---- sending ----

  private void send(ProjectAiService service, ProjectPlan plan) {
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
                ProjectAiResult result =
                    service.runStreaming(
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
                        cancel);
                ProjectRefresh.Result fresh =
                    ProjectRefresh.check(result.snapshot(), ws.index(), ws.reader(), ws.options());
                Platform.runLater(() -> finished(cancel, service, ws, result, fresh));
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
      ProjectAiService service,
      ProjectWorkspace ws,
      ProjectAiResult result,
      ProjectRefresh.Result fresh) {
    parent.end(cancel);
    bottom.ai().finish(result.note());
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
    Optional<Set<String>> approved =
        ProjectAiDialogs.review(proposal, checks, result.note(), warning);
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
