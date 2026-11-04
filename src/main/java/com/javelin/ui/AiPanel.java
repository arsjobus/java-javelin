package com.javelin.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * Shows an AI request: which provider and model it went to, and the answer as it arrives. The answer is read-only text
 * (nothing in it is ever applied from here: a proposed change has its own review). FX thread only.
 */
public final class AiPanel extends BorderPane {
    static final String IDLE = "AI is only used when you choose an action in the AI menu. Requests go to a model on this computer.";

    private final Label header = new Label(IDLE);
    private final TextArea text = new TextArea();
    private final Button cancel = new Button("Cancel");
    private final Button copy = new Button("Copy");
    private final Button clear = new Button("Clear");
    private Runnable onCancel = () -> {};

    public AiPanel() {
        text.setEditable(false);
        text.setWrapText(true);
        text.setPromptText("The answer appears here.");
        header.setWrapText(true);
        cancel.setDisable(true);
        cancel.setOnAction(e -> onCancel.run());
        copy.setOnAction(e -> { ClipboardContent content = new ClipboardContent(); content.putString(text.getText()); Clipboard.getSystemClipboard().setContent(content); });
        clear.setOnAction(e -> { text.clear(); header.setText(IDLE); });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, header, spacer, cancel, copy, clear);
        bar.setPadding(new Insets(4, 8, 4, 8));
        HBox.setHgrow(header, Priority.SOMETIMES);
        setTop(bar);
        setCenter(text);
    }

    /** Called when the Cancel button is pressed. */
    public void onCancel(Runnable action) { onCancel = action == null ? () -> {} : action; }

    /** Starts showing a request: clears the old answer and says where this one goes. */
    public void begin(String title, String indicator) {
        text.clear();
        header.setText(title + "  |  " + indicator);
        cancel.setDisable(false);
    }

    public void append(String piece) { text.appendText(piece); }

    /** Ends a request that finished; {@code note} (may be null) is added under the answer. */
    public void finish(String note) {
        cancel.setDisable(true);
        if (note != null && !note.isBlank()) text.appendText("\n\n[" + note + "]");
    }

    /** Ends a request that failed or was cancelled, saying why under whatever arrived. */
    public void fail(String message) {
        cancel.setDisable(true);
        text.appendText((text.getText().isEmpty() ? "" : "\n\n") + message);
    }

    public String text() { return text.getText(); }

    public boolean isBusy() { return !cancel.isDisabled(); }
}
