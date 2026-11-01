package com.javelin_ide.ui.editor;

import com.javelin_ide.editor.Brackets;
import com.javelin_ide.editor.LineNavigation;
import com.javelin_ide.editor.TextSearch;
import com.javelin_ide.io.ProjectFinder;
import com.javelin_ide.settings.Settings;
import com.javelin_ide.ui.Dialogs;
import com.javelin_ide.ui.shell.Workbench;
import java.io.File;
import java.util.Optional;
import java.util.OptionalInt;
import javafx.application.Platform;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.CodeArea;

/** Search, replace and the "go to" commands (line, file, matching bracket) of the Edit menu. */
public final class SearchController {
    private final Workbench bench;
    private final EditorTabs editors;
    private boolean searchCaseSensitive;
    /** The text of the last Search, so Find Next / Find Previous (F3 / Shift+F3) can repeat it. */
    private String lastSearch = "";

    public SearchController(Workbench bench, EditorTabs editors) {
        this.bench = bench;
        this.editors = editors;
        reloadSettings();
    }

    /** Re-reads the saved "match case" choice; called whenever settings are applied. */
    public void reloadSettings() { searchCaseSensitive = bench.settings().getBoolean(Settings.SEARCH_CASE_SENSITIVE, false); }

    public void search() {
        editors.currentEditor().ifPresent(a -> {
            String selected = a.getSelectedText();
            String initial = !selected.isEmpty() && selected.indexOf('\n') < 0 ? selected : lastSearch;
            Dialog<String> d = new Dialog<>();
            d.setTitle("Search"); d.setHeaderText("Find text (F3: next, Shift+F3: previous)");
            TextField query = new TextField(initial);
            CheckBox matchCase = new CheckBox("Match case");
            matchCase.setSelected(searchCaseSensitive);
            matchCase.setOnAction(e -> {
                searchCaseSensitive = matchCase.isSelected();
                bench.settings().put(Settings.Scope.USER, Settings.SEARCH_CASE_SENSITIVE, Boolean.toString(searchCaseSensitive));
                bench.saveUserSettings();
            });
            d.getDialogPane().setContent(new VBox(8, new Label("Search:"), query, matchCase));
            d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            d.setResultConverter(button -> button == ButtonType.OK ? query.getText() : null);
            d.setOnShown(e -> Platform.runLater(() -> { query.requestFocus(); query.selectAll(); }));
            Dialogs.show(d).filter(s -> !s.isEmpty()).ifPresent(q -> {
                lastSearch = q;
                boolean ignoreCase = !searchCaseSensitive;
                showMatch(a, q, TextSearch.first(a.getText(), q, a.getCaretPosition(), ignoreCase), ignoreCase);
            });
        });
    }

    public void findNext() {
        editors.currentEditor().ifPresent(a -> {
            if (lastSearch.isEmpty()) { search(); return; }
            IndexRange sel = a.getSelection();
            boolean ignoreCase = !searchCaseSensitive;
            showMatch(a, lastSearch, TextSearch.next(a.getText(), lastSearch, sel.getStart(), sel.getEnd(), ignoreCase), ignoreCase);
        });
    }

    public void findPrevious() {
        editors.currentEditor().ifPresent(a -> {
            if (lastSearch.isEmpty()) { search(); return; }
            boolean ignoreCase = !searchCaseSensitive;
            showMatch(a, lastSearch, TextSearch.previous(a.getText(), lastSearch, a.getSelection().getStart(), ignoreCase), ignoreCase);
        });
    }

    /** Selects the match and scrolls it into view, then gives the editor focus back (the Search dialog had it). */
    private void showMatch(CodeArea a, String query, TextSearch.Match match, boolean ignoreCase) {
        if (match == null) { bench.status("Not found: " + query); return; }
        a.selectRange(match.start(), match.end());
        a.requestFollowCaret(); a.requestFocus();
        Platform.runLater(() -> { a.requestFollowCaret(); a.requestFocus(); });   // once the dialog has finished closing
        bench.status("Found " + TextSearch.ordinal(a.getText(), query, match.start(), ignoreCase) + " of " + TextSearch.count(a.getText(), query, ignoreCase) + ": " + query);
    }

    public void replace() {
        editors.currentEditor().ifPresent(a -> {
            Dialog<String> f = new Dialog<>(); f.setTitle("Replace"); f.setHeaderText("Find text");
            TextField findField = new TextField();
            CheckBox ignoreCase = new CheckBox("Ignore case");
            f.getDialogPane().setContent(new VBox(8, new Label("Find:"), findField, ignoreCase));
            f.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            f.setResultConverter(button -> button == ButtonType.OK ? findField.getText() : null);
            f.setOnShown(e -> Platform.runLater(() -> { findField.requestFocus(); findField.selectAll(); }));
            Optional<String> q = Dialogs.show(f); if (q.isEmpty() || q.get().isEmpty()) return;
            TextInputDialog r = new TextInputDialog(); r.setTitle("Replace"); r.setHeaderText("Replacement text"); r.setContentText("Replace with:");
            r.setOnShown(e -> Platform.runLater(() -> { r.getEditor().requestFocus(); r.getEditor().selectAll(); }));
            Dialogs.show(r).ifPresent(v -> {
                String original = a.getText();
                String replaced = TextSearch.replace(original, q.get(), v, ignoreCase.isSelected());
                if (!original.equals(replaced)) a.replaceText(0, a.getLength(), replaced);
                bench.status("Replaced: " + q.get());
            });
        });
    }

    public void goToLine() {
        editors.currentEditor().ifPresent(a -> {
            TextInputDialog d = new TextInputDialog(String.valueOf(a.getCurrentParagraph() + 1));
            d.setTitle("Go to Line"); d.setHeaderText("Line number"); d.setContentText("Line:");
            Dialogs.show(d).ifPresent(s -> LineNavigation.parseLine(s, a.getParagraphs().size()).ifPresentOrElse(
                n -> { a.moveTo(n - 1, 0); a.requestFollowCaret(); bench.status("Line " + n); },
                () -> bench.showError("Invalid line", "Enter a valid line number.")));
        });
    }

    public void goToFile() {
        File projectDirectory = bench.projectDirectory();
        if (projectDirectory == null) { bench.showError("Go to File", "Open a project directory first."); return; }
        TextInputDialog d = new TextInputDialog(); d.setTitle("Go to File"); d.setHeaderText("Project file name"); d.setContentText("File:");
        Dialogs.show(d).filter(s -> !s.isBlank()).ifPresent(q -> {
            File f = ProjectFinder.find(projectDirectory, q.trim(), bench.treeExcludes()).orElse(null);
            if (f != null) editors.openFile(f); else bench.status("File not found: " + q);
        });
    }

    public void matchBracket() {
        editors.currentEditor().ifPresent(a -> {
            String text = a.getText();
            OptionalInt at = Brackets.near(text, a.getCaretPosition());
            if (at.isEmpty()) { bench.status("No bracket at caret"); return; }
            Brackets.pair(text, at.getAsInt()).ifPresentOrElse(
                pair -> { a.selectRange(pair.start(), pair.end()); bench.status("Matching bracket"); },
                () -> bench.status("No matching bracket"));
        });
    }
}
