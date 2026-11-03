package com.javelin.ui.editor;

import com.javelin.editor.FunctionWalkthrough;
import com.javelin.ui.shell.Workbench;
import java.util.List;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import org.fxmisc.richtext.CodeArea;

/** Shows a manually navigated, static walkthrough of a selected Java method. */
final class WalkthroughController {
    private final Workbench bench;
    private final Consumer<Parent> styler;
    private final Popup popup = new Popup();
    private final VBox bubble = new VBox(8);
    private final Label progress = new Label();
    private final Label explanation = new Label();
    private final Button previous = new Button("Previous");
    private final Button next = new Button("Next");
    private final Button exit = new Button("Exit");
    private CodeArea editor;
    private List<FunctionWalkthrough.Step> steps = List.of();
    private int originalStart;
    private int originalEnd;
    private int currentStep;
    private boolean wasEditable;

    private final javafx.event.EventHandler<KeyEvent> keyFilter = this::handleKey;
    private final javafx.beans.value.ChangeListener<String> textListener =
        (observable, oldText, newText) -> stop();

    WalkthroughController(Workbench bench, Consumer<Parent> styler) {
        this.bench = bench;
        this.styler = styler;
        bubble.getStyleClass().addAll("popup-pane", "walkthrough-bubble");
        progress.getStyleClass().add("walkthrough-progress");
        explanation.getStyleClass().add("popup-text");
        explanation.setWrapText(true);
        explanation.setMaxWidth(400);
        HBox controls = new HBox(6, previous, next, exit);
        controls.setAlignment(Pos.CENTER_RIGHT);
        bubble.getChildren().addAll(progress, explanation, controls);
        popup.getContent().add(bubble);
        popup.setAutoHide(false);
        previous.setOnAction(event -> showPrevious());
        next.setOnAction(event -> advance());
        exit.setOnAction(event -> stop());
        bubble.addEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
    }

    void start(CodeArea target) {
        stop();
        var selection = target.getSelection();
        if (selection.getLength() == 0) {
            bench.status("Select a complete Java method, then choose Walk me Through.");
            return;
        }
        String selected = target.getText(selection.getStart(), selection.getEnd());
        steps = FunctionWalkthrough.plan(selected);
        if (steps.isEmpty()) {
            bench.status("Select one complete Java method with a body to walk through.");
            return;
        }
        editor = target;
        originalStart = selection.getStart();
        originalEnd = selection.getEnd();
        wasEditable = editor.isEditable();
        editor.setEditable(false);
        editor.textProperty().addListener(textListener);
        editor.addEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
        currentStep = 0;
        showStep();
    }

    void stop() {
        if (editor == null) return;
        popup.hide();
        CodeArea previousEditor = editor;
        editor = null;
        previousEditor.removeEventFilter(KeyEvent.KEY_PRESSED, keyFilter);
        previousEditor.textProperty().removeListener(textListener);
        previousEditor.setEditable(wasEditable);
        previousEditor.selectRange(Math.min(originalStart, previousEditor.getLength()),
            Math.min(originalEnd, previousEditor.getLength()));
        steps = List.of();
    }

    private void showStep() {
        if (editor == null || currentStep >= steps.size()) {
            stop();
            return;
        }
        FunctionWalkthrough.Step step = steps.get(currentStep);
        editor.selectRange(originalStart + step.start(), originalStart + step.end());
        editor.requestFollowCaret();
        progress.setText("Step " + (currentStep + 1) + " of " + steps.size()
            + " | Static walkthrough; code is not executed | Esc to end");
        explanation.setText(step.explanation());
        previous.setDisable(currentStep == 0);
        next.setText(currentStep == steps.size() - 1 ? "Finish" : "Next");
        styler.accept(bubble);
        Platform.runLater(this::showPopupNearCode);
    }

    private void showPopupNearCode() {
        if (editor == null || editor.getScene() == null || editor.getScene().getWindow() == null) return;
        var bounds = editor.getCaretBounds();
        if (bounds.isEmpty()) return;
        Bounds caret = bounds.get();
        if (popup.isShowing()) {
            popup.setX(caret.getMinX());
            popup.setY(caret.getMaxY() + 6);
        } else {
            popup.show(editor.getScene().getWindow(), caret.getMinX(), caret.getMaxY() + 6);
        }
    }

    private void handleKey(KeyEvent event) {
        if (event.getCode() == KeyCode.ESCAPE) {
            stop();
        } else if (event.getCode() == KeyCode.RIGHT || event.getCode() == KeyCode.SPACE) {
            advance();
        } else if (event.getCode() == KeyCode.LEFT) {
            showPrevious();
        } else {
            return;
        }
        event.consume();
    }

    private void showPrevious() {
        if (editor == null || currentStep == 0) return;
        currentStep--;
        showStep();
    }

    private void advance() {
        if (editor == null) return;
        if (currentStep == steps.size() - 1) {
            stop();
            return;
        }
        currentStep++;
        showStep();
    }
}
