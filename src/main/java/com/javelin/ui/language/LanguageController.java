package com.javelin.ui.language;

import com.javelin.Branding;
import com.javelin.build.Problem;
import com.javelin.editor.Symbols;
import com.javelin.io.ProjectPaths;
import com.javelin.language.Completions;
import com.javelin.language.DiagnosticStore;
import com.javelin.language.LanguageProblems;
import com.javelin.language.LanguageService;
import com.javelin.language.Languages;
import com.javelin.language.Lsp;
import com.javelin.language.RenamePlan;
import com.javelin.language.TextEdits;
import com.javelin.ui.CompletionPopup;
import com.javelin.ui.Dialogs;
import com.javelin.ui.HoverPopup;
import com.javelin.ui.ReferencesPanel;
import com.javelin.ui.editor.EditorPositions;
import com.javelin.ui.editor.EditorTabs;
import com.javelin.ui.shell.BottomPanel;
import com.javelin.ui.shell.EditorListener;
import com.javelin.ui.shell.Workbench;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import org.fxmisc.richtext.CodeArea;

/**
 * Language intelligence (v0.5). Servers are started only from Language > Start Language Server...,
 * with a command the user confirms. Results are shown near the caret or in the status bar, never as
 * unsolicited dialogs.
 */
public final class LanguageController implements EditorListener {
  private final Workbench bench;
  private final EditorTabs editors;
  private final BottomPanel bottom;
  private final DiagnosticStore diagnostics;
  private final Consumer<Parent> popupStyler;
  private final Label languageStatus = new Label();
  private final CompletionPopup completionPopup = new CompletionPopup();
  private final HoverPopup hoverPopup = new HoverPopup();
  private final LanguageService language =
      new LanguageService(
          this::onDiagnostics,
          this::onLanguageLog,
          () -> Platform.runLater(this::updateLanguageStatus));

  public LanguageController(
      Workbench bench,
      EditorTabs editors,
      BottomPanel bottom,
      DiagnosticStore diagnostics,
      Consumer<Parent> popupStyler) {
    this.bench = bench;
    this.editors = editors;
    this.bottom = bottom;
    this.diagnostics = diagnostics;
    this.popupStyler = popupStyler;
    bottom.references().setOnOpen(r -> editors.openFile(r.file(), r.line(), r.column()));
  }

  /** The "Language servers: ..." label for the status bar. */
  public Label statusLabel() {
    return languageStatus;
  }

  /** Stops every language server (when the window closes). */
  public void dispose() {
    language.stopAll();
  }

  // ---- what the editor tells us about documents ----

  @Override
  public void opened(File file, String text) {
    language.open(file, text);
  }

  @Override
  public void changed(File file, int position, int removedLength, String inserted) {
    language.change(file, position, removedLength, inserted);
  }

  @Override
  public void saved(File file, File previous, String text) {
    language.saved(file, text);
  }

  @Override
  public void closed(File file) {
    language.close(file);
  }

  // ---- what the servers tell us ----

  private void onLanguageLog(String line) {
    Platform.runLater(() -> bottom.output().append(line));
  }

  private void updateLanguageStatus() {
    List<String> running = language.runningLanguages();
    languageStatus.setText(
        running.isEmpty() ? "" : "Language servers: " + String.join(", ", running));
  }

  private void onDiagnostics(URI uri, List<Lsp.Diagnostic> reported) {
    Platform.runLater(
        () -> {
          File file = ProjectPaths.fromUri(uri);
          if (file == null) return;
          diagnostics.replace(file, reported);
          List<Problem> list = LanguageProblems.toProblems(bench.displayPath(file), reported);
          bottom.problems().replace("language:" + file.getPath(), list);
          CodeArea editor = editors.editorFor(file);
          if (editor != null) editors.highlight(editor);
        });
  }

  // ---- commands ----

  /**
   * The caret, nudged left when it sits just after an identifier so that the identifier is the
   * symbol under it.
   */
  private int symbolOffset(CodeArea a) {
    return Symbols.symbolOffset(a.getText(), a.getCaretPosition());
  }

  private String wordAt(CodeArea a, int offset) {
    return Symbols.wordAt(a.getText(), offset);
  }

  /**
   * Runs a language action against the current editor, which must hold a saved file with a
   * recognized extension.
   */
  private void withLanguageEditor(java.util.function.BiConsumer<CodeArea, File> action) {
    CodeArea editor = editors.currentEditor().orElse(null);
    if (editor == null) {
      bench.status("Open a file first");
      return;
    }
    File file = editors.fileOf(editor);
    if (file == null) {
      bench.status("Save the file first; language features need a file on disk");
      return;
    }
    if (Languages.idFor(file) == null) {
      bench.status("No language support for this file type");
      return;
    }
    action.accept(editor, file);
  }

  private void languageError(Throwable error) {
    Throwable t = error;
    while (t instanceof CompletionException && t.getCause() != null) t = t.getCause();
    bench.status(t.getMessage() == null ? "Language server error" : t.getMessage());
  }

  public void startServer() {
    withLanguageEditor(
        (editor, file) -> {
          String id = Languages.idFor(file);
          if (language.isRunning(id)) {
            bench.status("The " + id + " language server is already running");
            return;
          }
          File projectDirectory = bench.projectDirectory();
          File root =
              projectDirectory != null ? projectDirectory : file.getAbsoluteFile().getParentFile();
          String key = "language.server." + id;
          TextInputDialog d =
              new TextInputDialog(
                  bench
                      .settings()
                      .get(key, bench.preferences().get(key, Languages.suggestedCommand(id))));
          d.setTitle("Start Language Server");
          d.setHeaderText(
              "Command for the "
                  + id
                  + " language server.\nIt runs in "
                  + root.getAbsolutePath()
                  + " and is started only if you confirm.");
          d.setContentText("Command:");
          Dialogs.show(d)
              .filter(s -> !s.isBlank())
              .ifPresent(
                  command -> {
                    bench.preferences().put(key, command.trim());
                    try {
                      bench.status("Starting " + id + " language server...");
                      language
                          .start(id, command.trim(), root)
                          .whenComplete(
                              (v, err) ->
                                  Platform.runLater(
                                      () -> {
                                        if (err == null)
                                          bench.status("Language server ready: " + id);
                                        else {
                                          languageError(err);
                                          bench.status(
                                              "Language server failed to start: "
                                                  + bench.statusText());
                                          bottom.show(bottom.outputTab());
                                        }
                                      }));
                    } catch (IOException ex) {
                      bench.showError("Could not start language server", ex.getMessage());
                    }
                  });
        });
  }

  public void stopServer() {
    List<String> running = language.runningLanguages();
    if (running.isEmpty()) {
      bench.status("No language servers running");
      return;
    }
    String current =
        editors.currentEditor().map(editors::fileOf).map(Languages::idFor).orElse(null);
    String target =
        running.contains(current) ? current : running.size() == 1 ? running.get(0) : null;
    if (target == null) {
      ChoiceDialog<String> d = new ChoiceDialog<>(running.get(0), running);
      d.setTitle("Stop Language Server");
      d.setHeaderText("Select a language server to stop");
      d.setContentText("Language:");
      target = Dialogs.show(d).orElse(null);
    }
    if (target != null) {
      language.stop(target);
      bench.status("Stopping " + target + " language server");
    }
  }

  public void complete() {
    withLanguageEditor(
        (editor, file) -> {
          int requestCaret = editor.getCaretPosition();
          language
              .completion(file, requestCaret)
              .whenComplete(
                  (items, err) ->
                      Platform.runLater(
                          () -> {
                            if (err != null) {
                              languageError(err);
                              return;
                            }
                            if (items.isEmpty()) {
                              bench.status("No completions");
                              return;
                            }
                            if (editor.getCaretPosition() != requestCaret)
                              return; // the user moved on
                            showCompletions(editor, items, requestCaret);
                          }));
        });
  }

  private void showCompletions(CodeArea editor, List<Lsp.CompletionItem> items, int requestCaret) {
    Completions.Plan plan =
        Completions.plan(
            editor.getText(),
            requestCaret,
            items,
            position -> EditorPositions.offsetOf(editor, position));
    List<CompletionPopup.Entry> entries = new ArrayList<>();
    for (Completions.Candidate candidate : plan.candidates())
      entries.add(
          new CompletionPopup.Entry(
              candidate.item().label(),
              candidate.item().detail(),
              candidate.item().filter(),
              candidate));
    completionPopup.show(
        editor,
        entries,
        plan.popupStart(),
        entry -> {
          // characters may have been typed (or deleted) while the list was open
          Completions.Replacement change =
              Completions.replacement(
                  (Completions.Candidate) entry.payload(),
                  requestCaret,
                  editor.getCaretPosition(),
                  editor.getLength());
          editor.replaceText(change.start(), change.end(), change.text());
        },
        popupStyler);
  }

  public void hover() {
    withLanguageEditor(
        (editor, file) ->
            language
                .hover(file, symbolOffset(editor))
                .whenComplete(
                    (text, err) ->
                        Platform.runLater(
                            () -> {
                              if (err != null) {
                                languageError(err);
                                return;
                              }
                              if (text.isBlank()) {
                                bench.status("No hover information");
                                return;
                              }
                              hoverPopup.show(editor, text, popupStyler);
                            })));
  }

  public void signatureHelp() {
    withLanguageEditor(
        (editor, file) -> {
          int requestCaret = editor.getCaretPosition();
          language
              .signatureHelp(file, requestCaret)
              .whenComplete(
                  (text, err) ->
                      Platform.runLater(
                          () -> {
                            if (err != null) {
                              languageError(err);
                              return;
                            }
                            if (text.isBlank()) {
                              bench.status("No signature help here");
                              return;
                            }
                            hoverPopup.show(editor, text, popupStyler);
                          }));
        });
  }

  /**
   * Lists the file's outline in the References panel; double-click jumps to a symbol. Read-only.
   */
  public void documentSymbols() {
    withLanguageEditor(
        (editor, file) ->
            language
                .documentSymbols(file)
                .whenComplete(
                    (symbols, err) -> {
                      Platform.runLater(
                          () -> {
                            if (err != null) {
                              languageError(err);
                              return;
                            }
                            List<ReferencesPanel.Reference> entries = new ArrayList<>();
                            for (Lsp.Symbol symbol : symbols) {
                              int line =
                                  Math.max(
                                      0,
                                      Math.min(
                                          symbol.range().start().line(),
                                          editor.getParagraphs().size() - 1));
                              String label =
                                  "  ".repeat(symbol.depth())
                                      + symbol.kindName()
                                      + " "
                                      + symbol.name()
                                      + (symbol.detail() == null || symbol.detail().isBlank()
                                          ? ""
                                          : "  " + symbol.detail());
                              entries.add(
                                  new ReferencesPanel.Reference(
                                      file,
                                      line + 1,
                                      symbol.range().start().character() + 1,
                                      label));
                            }
                            bottom
                                .references()
                                .show(
                                    "Symbols in " + file.getName() + " (" + entries.size() + ")",
                                    entries);
                            bottom.show(bottom.referencesTab());
                            bench.status(
                                entries.isEmpty()
                                    ? "No symbols found"
                                    : entries.size() + " symbol(s)");
                          });
                    }));
  }

  /**
   * Asks the server for formatting edits and applies them to the editor. Nothing is written to
   * disk, and the result is one undoable step per edit. If the text changed while the server was
   * working, the result is discarded.
   */
  public void formatDocument() {
    withLanguageEditor(
        (editor, file) -> {
          String snapshot = editor.getText();
          language
              .formatting(file, bench.editorPrefs().tabSize(), bench.editorPrefs().insertSpaces())
              .whenComplete(
                  (edits, err) ->
                      Platform.runLater(
                          () -> {
                            if (err != null) {
                              languageError(err);
                              return;
                            }
                            if (edits.isEmpty()) {
                              bench.status("Nothing to format");
                              return;
                            }
                            if (!snapshot.equals(editor.getText())) {
                              bench.status(
                                  "The file changed while formatting; nothing was applied");
                              return;
                            }
                            List<int[]> spans = new ArrayList<>(); // {start, end, edit index}
                            for (int i = 0; i < edits.size(); i++)
                              spans.add(
                                  new int[] {
                                    EditorPositions.offsetOf(editor, edits.get(i).range().start()),
                                    EditorPositions.offsetOf(editor, edits.get(i).range().end()),
                                    i
                                  });
                            spans.sort(
                                (a, b) ->
                                    Integer.compare(
                                        b[0], a[0])); // bottom to top so earlier offsets stay valid
                            for (int[] span : spans)
                              replaceMinimal(
                                  editor, span[0], span[1], edits.get(span[2]).newText());
                            bench.status(
                                "Formatted "
                                    + file.getName()
                                    + " ("
                                    + edits.size()
                                    + " edit(s), unsaved)");
                          }));
        });
  }

  public void goToDefinition() {
    withLanguageEditor(
        (editor, file) ->
            language
                .definition(file, symbolOffset(editor))
                .whenComplete(
                    (locations, err) -> {
                      List<ReferencesPanel.Reference> found =
                          err == null ? toReferences(locations) : List.of();
                      Platform.runLater(
                          () -> {
                            if (err != null) {
                              languageError(err);
                              return;
                            }
                            if (found.isEmpty()) {
                              bench.status(
                                  locations.isEmpty()
                                      ? "No definition found"
                                      : "The definition is not in a local file");
                              return;
                            }
                            if (found.size() == 1) {
                              editors.openFile(
                                  found.get(0).file(), found.get(0).line(), found.get(0).column());
                              return;
                            }
                            bottom.references().show("Definitions (" + found.size() + ")", found);
                            bottom.show(bottom.referencesTab());
                          });
                    }));
  }

  public void findReferences() {
    withLanguageEditor(
        (editor, file) -> {
          String word = wordAt(editor, symbolOffset(editor));
          language
              .references(file, symbolOffset(editor))
              .whenComplete(
                  (locations, err) -> {
                    List<ReferencesPanel.Reference> found =
                        err == null ? toReferences(locations) : List.of();
                    Platform.runLater(
                        () -> {
                          if (err != null) {
                            languageError(err);
                            return;
                          }
                          bottom
                              .references()
                              .show(
                                  "References"
                                      + (word.isEmpty() ? "" : " to " + word)
                                      + " ("
                                      + found.size()
                                      + ")",
                                  found);
                          bottom.show(bottom.referencesTab());
                          bench.status(
                              found.isEmpty()
                                  ? "No references found"
                                  : found.size() + " reference(s)");
                        });
                  });
        });
  }

  /**
   * Converts server locations to panel entries, reading each file once for the line previews. Runs
   * off the FX thread.
   */
  private List<ReferencesPanel.Reference> toReferences(List<Lsp.Location> locations) {
    Map<File, List<String>> lines = new HashMap<>();
    List<ReferencesPanel.Reference> out = new ArrayList<>();
    for (Lsp.Location location : locations) {
      File file = ProjectPaths.fromUri(location.uri());
      if (file == null) continue;
      List<String> content =
          lines.computeIfAbsent(
              file,
              f -> {
                try {
                  return Files.readAllLines(f.toPath());
                } catch (IOException | RuntimeException ex) {
                  return List.of();
                }
              });
      int line = location.range().start().line();
      out.add(
          new ReferencesPanel.Reference(
              file,
              line + 1,
              location.range().start().character() + 1,
              line < content.size() ? content.get(line) : ""));
    }
    return out;
  }

  public void renameSymbol() {
    withLanguageEditor(
        (editor, file) -> {
          int offset = symbolOffset(editor);
          String word = wordAt(editor, offset);
          if (word.isEmpty()) {
            bench.status("Put the caret on the symbol to rename");
            return;
          }
          TextInputDialog d = new TextInputDialog(word);
          d.setTitle("Rename Symbol");
          d.setHeaderText(
              "Rename " + word + "\nYou will see what changes before anything is applied.");
          d.setContentText("New name:");
          Dialogs.show(d)
              .map(String::trim)
              .filter(n -> !n.isEmpty() && !n.equals(word))
              .ifPresent(
                  newName ->
                      language
                          .rename(file, offset, newName)
                          .whenComplete(
                              (edit, err) ->
                                  Platform.runLater(
                                      () -> {
                                        if (err != null) {
                                          languageError(err);
                                          return;
                                        }
                                        applyRename(word, newName, edit);
                                      })));
        });
  }

  /**
   * Shows the server's proposed edits and, on approval, applies them to editor tabs. Nothing is
   * written to disk.
   */
  private void applyRename(String oldName, String newName, Lsp.WorkspaceEdit edit) {
    if (edit.unsupported()) {
      bench.showError(
          "Rename",
          "The language server also asked to create, rename or delete files, which "
              + Branding.FULL_NAME
              + " never does automatically. Nothing was changed.");
      return;
    }
    RenamePlan plan = RenamePlan.of(edit, ProjectPaths::fromUri);
    int total = plan.total();
    if (total == 0) {
      bench.status("The server proposed no changes");
      return;
    }
    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
    ButtonType apply = new ButtonType("Apply"), cancel = ButtonType.CANCEL;
    confirm.setTitle("Rename Symbol");
    confirm.setHeaderText("Apply rename?");
    confirm.setContentText(plan.summary(oldName, newName, bench::displayPath));
    confirm.getButtonTypes().setAll(apply, cancel);
    if (Dialogs.show(confirm).orElse(cancel) != apply) {
      bench.status("Rename cancelled");
      return;
    }
    for (Map.Entry<File, List<Lsp.TextEdit>> e : plan.byFile().entrySet()) {
      editors.openFile(e.getKey());
      CodeArea editor = editors.editorFor(e.getKey());
      if (editor == null) continue;
      for (TextEdits.Change c : TextEdits.resolve(editor.getText(), e.getValue()))
        replaceMinimal(editor, c.start(), c.end(), c.text());
    }
    bench.status("Renamed " + oldName + " to " + newName + " (" + total + " edit(s), unsaved)");
  }

  /**
   * Replaces a range but leaves untouched the leading and trailing text that is identical, so
   * whole-file edits stay small.
   */
  private void replaceMinimal(CodeArea editor, int start, int end, String replacement) {
    TextEdits.Change c = TextEdits.minimal(editor.getText(start, end), replacement);
    if (c != null) editor.replaceText(start + c.start(), start + c.end(), c.text());
  }
}
