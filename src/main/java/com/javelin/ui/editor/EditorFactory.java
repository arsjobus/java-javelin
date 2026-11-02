package com.javelin.ui.editor;

import com.javelin.editor.Indentation;
import com.javelin.ui.shell.Workbench;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.IndexRange;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.fxmisc.richtext.CaretNode;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;

/** Builds the text editors: their look, context menu, Tab / Enter behaviour and extra cursors. */
final class EditorFactory {
    private final Workbench bench;
    private final Map<CodeArea, List<CaretNode>> extraCarets = new HashMap<>();
    private boolean swallowTypedTab;
    private boolean swallowTypedEnter;

    EditorFactory(Workbench bench) { this.bench = bench; }

    CodeArea create() {
        CodeArea editor = new CodeArea();
        installContextMenu(editor);
        applyPreferences(editor);
        editor.setOnKeyPressed(this::handleKeyPressed);
        // Filters, not handlers: they must run before RichTextFX's own key handling, and Tab also arrives as a separate
        // KEY_TYPED event with "\t" that consuming KEY_PRESSED does not stop - it would add a stray tab character.
        editor.addEventFilter(KeyEvent.KEY_PRESSED, this::handleTab);
        editor.addEventFilter(KeyEvent.KEY_PRESSED, this::handleEnter);
        editor.addEventFilter(KeyEvent.KEY_TYPED, e -> {
            if (swallowTypedTab && "\t".equals(e.getCharacter())) { swallowTypedTab = false; e.consume(); }
            // Enter is the same story as Tab: without this the typed event adds a second line break after ours.
            if (swallowTypedEnter && Indentation.isLineBreak(e.getCharacter())) { swallowTypedEnter = false; e.consume(); }
        });
        editor.addEventHandler(KeyEvent.KEY_TYPED, e -> {
            if (hasExtraCarets(editor) && !e.getCharacter().isEmpty() && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
                List<Integer> positions = extraCarets.get(editor).stream().map(CaretNode::getPosition).sorted(Comparator.reverseOrder()).toList();
                for (int p : positions) editor.insertText(p, e.getCharacter());
                clearExtraCarets(editor); e.consume();
            }
        });
        return editor;
    }

    void applyPreferences(CodeArea editor) {
        editor.setStyle(bench.editorPrefs().style(bench.zoom()));
        editor.setWrapText(bench.editorPrefs().wordWrap());
        editor.setParagraphGraphicFactory(bench.editorPrefs().lineNumbers() ? LineNumberFactory.get(editor) : null);
    }

    /** Adds a cursor at the caret of {@code a}; typing, Backspace and Delete then act at every cursor. */
    void addCursor(CodeArea a) {
        CaretNode c = new CaretNode("extra-" + System.nanoTime(), a, a.getCaretPosition());
        a.addCaret(c); extraCarets.computeIfAbsent(a, k -> new ArrayList<>()).add(c); bench.status("Additional cursor added");
    }

    private boolean hasExtraCarets(CodeArea a) { return extraCarets.containsKey(a) && !extraCarets.get(a).isEmpty(); }

    private void clearExtraCarets(CodeArea a) {
        List<CaretNode> list = extraCarets.remove(a); if (list != null) list.forEach(a::removeCaret);
    }

    private void installContextMenu(CodeArea editor) {
        MenuItem copy = new MenuItem("Copy");
        copy.setOnAction(event -> {
            IndexRange selection = editor.getSelection();
            if (selection.getLength() == 0) return;
            ClipboardContent content = new ClipboardContent();
            content.putString(editor.getText(selection.getStart(), selection.getEnd()));
            Clipboard.getSystemClipboard().setContent(content);
        });
        MenuItem cut = new MenuItem("Cut");
        cut.setOnAction(event -> {
            IndexRange selection = editor.getSelection();
            if (selection.getLength() == 0) return;
            ClipboardContent content = new ClipboardContent();
            content.putString(editor.getText(selection.getStart(), selection.getEnd()));
            Clipboard.getSystemClipboard().setContent(content);
            editor.replaceText(selection.getStart(), selection.getEnd(), "");
        });
        MenuItem paste = new MenuItem("Paste");
        paste.setOnAction(event -> {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            if (!clipboard.hasString()) return;
            IndexRange selection = editor.getSelection();
            editor.replaceText(selection.getStart(), selection.getEnd(), clipboard.getString());
        });
        ContextMenu menu = new ContextMenu(copy, cut, paste);
        menu.setOnShowing(event -> {
            boolean hasSelection = editor.getSelection().getLength() > 0;
            copy.setDisable(!hasSelection);
            cut.setDisable(!hasSelection);
            paste.setDisable(!Clipboard.getSystemClipboard().hasString());
        });
        editor.setContextMenu(menu);
        editor.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.SECONDARY) return;
            IndexRange selection = editor.getSelection();
            int hit = editor.hit(event.getX(), event.getY()).getInsertionIndex();
            if (selection.getLength() > 0 && hit >= selection.getStart() && hit < selection.getEnd()) return;
            editor.moveTo(hit);
        });
    }

    /** Tab inserts spaces/a tab or indents the selected lines; Shift+Tab outdents them (see {@link Indentation}). */
    private void handleTab(KeyEvent e) {
        swallowTypedTab = false;
        if (!(e.getSource() instanceof CodeArea editor) || e.getCode() != KeyCode.TAB) return;
        if (e.isControlDown() || e.isAltDown() || e.isMetaDown() || hasExtraCarets(editor)) return;
        IndexRange selection = editor.getSelection();
        String text = editor.getText();
        Indentation.Edit edit = e.isShiftDown()
            ? Indentation.outdent(text, selection.getStart(), selection.getEnd(), bench.editorPrefs().tabSize())
            : Indentation.tab(text, selection.getStart(), selection.getEnd(), bench.editorPrefs().tabSize(), bench.editorPrefs().insertSpaces());
        if (!edit.isNoOp()) {
            editor.replaceText(edit.start(), edit.end(), edit.replacement());
            editor.selectRange(edit.selectionStart(), edit.selectionEnd());
        }
        swallowTypedTab = true; e.consume();
    }

    /** With auto-indent on, Enter inserts one line break plus the right indentation (see {@link Indentation#newline}). */
    private void handleEnter(KeyEvent e) {
        swallowTypedEnter = false;
        if (!(e.getSource() instanceof CodeArea editor) || e.getCode() != KeyCode.ENTER || !bench.editorPrefs().autoIndent()) return;
        if (e.isControlDown() || e.isAltDown() || e.isMetaDown() || hasExtraCarets(editor)) return;
        IndexRange selection = editor.getSelection();
        Indentation.Edit edit = Indentation.newline(editor.getText(), selection.getStart(), selection.getEnd(), bench.editorPrefs().indentUnit());
        editor.replaceText(edit.start(), edit.end(), edit.replacement());
        editor.selectRange(edit.selectionStart(), edit.selectionEnd());
        editor.requestFollowCaret();
        swallowTypedEnter = true; e.consume();
    }

    private void handleKeyPressed(KeyEvent e) {
        if (!(e.getSource() instanceof CodeArea editor)) return;
        if ((e.getCode() == KeyCode.BACK_SPACE || e.getCode() == KeyCode.DELETE) && hasExtraCarets(editor)) {
            boolean back = e.getCode() == KeyCode.BACK_SPACE;
            List<Integer> positions = extraCarets.get(editor).stream().map(CaretNode::getPosition).sorted(Comparator.reverseOrder()).toList();
            for (int p : positions) {
                if (back && p > 0) editor.deleteText(p - 1, p);
                if (!back && p < editor.getLength()) editor.deleteText(p, p + 1);
            }
            clearExtraCarets(editor); e.consume();
        }
    }
}
