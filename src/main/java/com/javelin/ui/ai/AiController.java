package com.javelin.ui.ai;

import com.javelin.ai.AiAction;
import com.javelin.ai.AiCancel;
import com.javelin.ai.AiConfig;
import com.javelin.ai.AiException;
import com.javelin.ai.AiPlan;
import com.javelin.ai.AiProposal;
import com.javelin.ai.AiProvider;
import com.javelin.ai.AiProviders;
import com.javelin.ai.AiResult;
import com.javelin.ai.AiService;
import com.javelin.ai.AiSource;
import com.javelin.ai.verify.JavacVerifier;
import com.javelin.ai.verify.ProjectClasspath;
import com.javelin.ai.verify.Verified;
import com.javelin.ai.verify.Verifier;
import com.javelin.ai.verify.VerifyConfig;
import com.javelin.ai.verify.VerifyTarget;
import com.javelin.language.Languages;
import com.javelin.language.TextEdits;
import com.javelin.settings.Settings;
import com.javelin.ui.editor.EditorTabs;
import com.javelin.ui.shell.BottomPanel;
import com.javelin.ui.shell.Workbench;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Platform;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import org.fxmisc.richtext.CodeArea;

/**
 * Optional AI help (v0.7): explain or debug the selection, generate tests, propose a change.
 * Everything here follows the rules in AI.md: nothing runs unless an AI menu command is chosen, the
 * user sees exactly what would be sent and where before anything is, the answer is read in the AI
 * panel, and a proposed change changes nothing until the user reviews it and chooses Apply (it then
 * lands as an ordinary, undoable edit that stays unsaved). A failure is reported in the panel and
 * the status bar and never stops editing. The logic is in {@code com.javelin.ai}; this class is the
 * window.
 */
public final class AiController {
  private final Workbench bench;
  private final EditorTabs editors;
  private final BottomPanel bottom;
  private final Label statusLabel = new Label();
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          task -> {
            Thread thread = new Thread(task, "ai-worker");
            thread.setDaemon(true);
            return thread;
          });
  private final ProjectAiController project;
  private AiCancel running; // the request in flight; FX thread only

  public AiController(Workbench bench, EditorTabs editors, BottomPanel bottom) {
    this.bench = bench;
    this.editors = editors;
    this.bottom = bottom;
    bottom.ai().onCancel(this::cancel);
    this.project = new ProjectAiController(this, bench, editors, bottom);
  }

  /** The network indicator for the status bar: empty unless a request is in flight. */
  public Label statusLabel() {
    return statusLabel;
  }

  public void explain() {
    start(AiAction.EXPLAIN);
  }

  public void debug() {
    start(AiAction.DEBUG);
  }

  public void generateTests() {
    start(AiAction.GENERATE_TESTS);
  }

  public void proposeChange() {
    start(AiAction.PROPOSE_CHANGE);
  }

  /** AI > Project Request...: a request about the whole open project. */
  public void projectRequest() {
    project.request();
  }

  /** AI > Undo Last Project Change: puts back the files the last project change wrote. */
  public void undoProjectChange() {
    project.undo();
  }

  /** Cancels the request in flight, if there is one. */
  public void cancel() {
    if (running == null) {
      bench.status("No AI request is running.");
      return;
    }
    running.cancel();
    bench.status("Cancelling the AI request...");
  }

  public void dispose() {
    if (running != null) running.cancel();
    worker.shutdownNow();
  }

  // ---- choosing a model ----

  /**
   * AI > Choose Model...: lists what Ollama has installed and remembers the choice in the user
   * settings.
   */
  public void chooseModel() {
    chooseModel(() -> {});
  }

  void chooseModel(Runnable afterChoice) {
    if (busy()) return;
    AiConfig config = AiConfig.from(bench.settings());
    AiProvider provider = providerFor(config);
    if (provider == null) return;
    AiService service = new AiService(provider, config);
    AiCancel cancel =
        begin(
            "Looking for the models installed in "
                + provider.displayName()
                + " at "
                + provider.location()
                + "...");
    worker.submit(
        () -> {
          try {
            List<String> models = service.models(cancel);
            Platform.runLater(
                () -> {
                  end(cancel);
                  modelsListed(models, config, afterChoice);
                });
          } catch (AiException ex) {
            Platform.runLater(
                () -> {
                  end(cancel);
                  if (ex.kind() == AiException.Kind.CANCELLED) report(ex);
                  else bench.showError("Choose AI Model", ex.getMessage());
                });
          }
        });
  }

  private void modelsListed(List<String> models, AiConfig config, Runnable afterChoice) {
    if (models.isEmpty()) {
      bench.showError(
          "Choose AI Model",
          "Ollama is running but has no models installed. Install one with  ollama pull <model> "
              + " (see ollama.com/library), then choose it here.");
      return;
    }
    var chosen = AiDialogs.chooseModel(models, config.model());
    if (chosen.isEmpty()) {
      bench.status("No AI model chosen.");
      return;
    }
    try {
      bench.settings().put(Settings.Scope.USER, AiConfig.MODEL, chosen.get());
      bench.settings().saveKeys(Settings.Scope.USER, List.of(AiConfig.MODEL));
      bench.status("AI model: " + chosen.get());
    } catch (IOException | RuntimeException ex) {
      bench.showError(
          "Choose AI Model",
          "The model could not be saved to your user settings: " + ex.getMessage());
      return;
    }
    afterChoice.run();
  }

  // ---- running an action ----

  private void start(AiAction action) {
    if (busy()) return;
    CodeArea editor = editors.currentEditor().orElse(null);
    if (editor == null) {
      bench.status("Open a file first: AI actions work on the text in the editor.");
      return;
    }
    AiConfig config = AiConfig.from(bench.settings());
    AiProvider provider = providerFor(config);
    if (provider == null) return;
    if (!config.hasModel()) {
      chooseModel(() -> start(action));
      return;
    }
    String instruction = null;
    if (action.needsInstruction()) {
      instruction = AiDialogs.askInstruction().orElse(null);
      if (instruction == null) return;
    }
    File file = editors.fileOf(editor);
    IndexRange selection = editor.getSelection();
    AiSource source =
        new AiSource(
            file == null ? "Untitled" : bench.displayPath(file),
            file == null ? null : file.getName(),
            file == null ? null : Languages.idFor(file),
            editor.getText(),
            selection.getStart(),
            selection.getEnd());
    AiService service = new AiService(provider, config);
    AiPlan plan;
    try {
      plan = service.prepare(action, source, instruction);
    } catch (AiException ex) {
      bench.showError(action.label(), ex.getMessage());
      return;
    }
    VerifyConfig verify = VerifyConfig.from(bench.settings());
    VerifyTarget target = verifyTarget(action, verify, file, editor.getText());
    if (!AiDialogs.confirmSend(plan, verify.appendedTo(plan.preview(), target != null))) {
      bench.status("AI request not sent.");
      return;
    }
    send(service, plan, editor, target, verify);
  }

  /**
   * What a proposed change can be compiled as: the open Java file inside the open project. Null (no
   * check, one request) for anything else: other actions, no project, a file outside it, a file
   * that is not Java, or the check switched off ({@code ai.verify}).
   */
  private VerifyTarget verifyTarget(AiAction action, VerifyConfig verify, File file, String text) {
    File root = bench.projectDirectory();
    if (!verify.enabled()
        || action != AiAction.PROPOSE_CHANGE
        || root == null
        || file == null
        || !file.getName().endsWith(".java")) return null;
    Path base = root.toPath().toAbsolutePath().normalize();
    Path path = file.toPath().toAbsolutePath().normalize();
    if (!path.startsWith(base)) return null;
    return new VerifyTarget(base.relativize(path).toString().replace('\\', '/'), text);
  }

  private void send(
      AiService service, AiPlan plan, CodeArea editor, VerifyTarget target, VerifyConfig verify) {
    AiCancel cancel = begin(plan.action().label() + ": waiting for " + plan.indicator() + "...");
    statusLabel.setText("AI > " + plan.providerName() + " " + plan.location());
    bottom.ai().begin(plan.action().label() + " of " + plan.context().name(), plan.indicator());
    bottom.show(bottom.aiTab());
    worker.submit(
        () -> {
          try {
            Verifier verifier = null;
            if (target != null) {
              Path root = bench.projectDirectory().toPath().toAbsolutePath().normalize();
              verifier =
                  new JavacVerifier(
                      root,
                      ProjectClasspath.resolve(root, ProjectClasspath.defaultRepository()),
                      verify);
            }
            Verified<AiResult> verified =
                service.runVerified(
                    plan,
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
                    target,
                    verifier,
                    verify.retries());
            Platform.runLater(
                () -> finished(cancel, plan, editor, verified.answer(), verified.report()));
          } catch (AiException ex) {
            Platform.runLater(() -> failed(cancel, ex));
          } catch (RuntimeException ex) {
            Platform.runLater(
                () ->
                    failed(
                        cancel,
                        new AiException(AiException.Kind.BAD_RESPONSE, "Unexpected error: " + ex)));
          }
        });
  }

  private void finished(
      AiCancel cancel, AiPlan plan, CodeArea editor, AiResult result, String checks) {
    end(cancel);
    AiProposal proposal = result.proposal();
    String note = joinNotes(result.note(), checks);
    bottom.ai().finish(note);
    if (proposal == null) {
      bench.status(
          plan.action().label() + (result.note() == null ? ": done." : ": " + result.note()));
      return;
    }
    if (!AiDialogs.review(proposal, note, result.findings())) {
      bench.status("Proposed change rejected: nothing was changed.");
      return;
    }
    apply(proposal, editor);
  }

  private static String joinNotes(String note, String checks) {
    if (checks == null || checks.isBlank()) return note;
    return note == null || note.isBlank() ? checks : note + "\n\n" + checks;
  }

  private void failed(AiCancel cancel, AiException ex) {
    end(cancel);
    bottom.ai().fail(ex.getMessage());
    report(ex);
  }

  void report(AiException ex) {
    bench.status(
        ex.kind() == AiException.Kind.CANCELLED
            ? "AI request cancelled."
            : "AI: " + ex.getMessage());
  }

  // ---- applying ----

  private void apply(AiProposal proposal, CodeArea editor) {
    if (proposal.kind() == AiProposal.Kind.NEW_DOCUMENT) {
      editors.newDocument(proposal.replacement(), proposal.suggestedName());
      bench.status(
          "Opened " + proposal.suggestedName() + " (unsaved). Save it where the tests belong.");
      return;
    }
    boolean[] stillOpen = {false};
    editors.forEachEditor(
        open -> {
          if (open == editor) stillOpen[0] = true;
        });
    if (!stillOpen[0]) {
      bench.status("The file was closed, so the proposed change was not applied.");
      return;
    }
    if (!proposal.isStillValid(editor.getText())) {
      bench.showError(
          "Propose Change",
          "The text changed while the AI was working, so the change was not applied. Select the"
              + " code again and ask again.");
      return;
    }
    TextEdits.Change change = TextEdits.minimal(proposal.original(), proposal.replacement());
    if (change == null) {
      bench.status("The proposed change is the same as the current text.");
      return;
    }
    editor.replaceText(
        proposal.start() + change.start(), proposal.start() + change.end(), change.text());
    bench.status("Applied the AI's change (unsaved). Undo takes it back.");
  }

  // ---- bookkeeping ----

  ExecutorService worker() {
    return worker;
  }

  boolean busy() {
    if (running == null) return false;
    bench.status("An AI request is already running. Cancel it first (AI > Cancel AI Request).");
    return true;
  }

  AiCancel begin(String status) {
    AiCancel cancel = new AiCancel();
    running = cancel;
    bench.status(status);
    return cancel;
  }

  void end(AiCancel cancel) {
    if (running == cancel) running = null;
    statusLabel.setText("");
  }

  AiProvider providerFor(AiConfig config) {
    try {
      return AiProviders.create(config);
    } catch (AiException ex) {
      bench.showError("AI", ex.getMessage());
      return null;
    }
  }
}
