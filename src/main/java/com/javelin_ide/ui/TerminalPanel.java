package com.javelin_ide.ui;

import com.javelin_ide.build.ProcessManager;
import com.javelin_ide.terminal.TerminalCompletion;
import com.javelin_ide.terminal.TerminalSession;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * One terminal tab: a persistent shell fed from a text field. There is no PTY, so full-screen programs
 * (vim, top) are unsupported, but Ctrl+C interrupts the running command, Ctrl+D exits an idle shell,
 * and Ctrl+L clears the transcript. Ctrl+C copies instead when text is selected.
 * The prompt shows the active Python virtualenv, and the title shows a dot while a command is running.
 * Call from the FX thread only.
 */
public final class TerminalPanel extends BorderPane {
    private static final int MAX_CHARS = 400_000;
    private static final int KEEP_CHARS = 300_000;
    private static final String MONO = "-fx-font-family: 'Monospaced'; -fx-font-size: 13px;";

    private final String baseName;
    private final Supplier<File> directorySupplier;
    private final TerminalSession session;
    private final TextArea transcript = new TextArea();
    private final TextField input = new TextField();
    private final Label prompt = new Label("$");
    private final Label state = new Label("Idle");
    private final Button interrupt = new Button("Interrupt (Ctrl+C)");
    private final StringProperty title = new SimpleStringProperty();
    private final List<String> history = new ArrayList<>();
    private int lastBackground;
    private final Timeline ticker = new Timeline(new KeyFrame(Duration.millis(400), e -> tick()));
    private int historyIndex;

    public TerminalPanel(String name, ProcessManager processes, Supplier<File> directory) {
        this.baseName = name;
        this.directorySupplier = directory;
        this.session = new TerminalSession(name, processes, directory, new TerminalSession.Listener() {
            @Override public void onOutput(String line) { Platform.runLater(() -> append(line)); }
            @Override public void onStateChanged() { Platform.runLater(TerminalPanel.this::refresh); }
        });

        transcript.setEditable(false);
        transcript.getStyleClass().add("terminal-transcript");
        transcript.setStyle(MONO);
        input.getStyleClass().add("terminal-input");
        input.setStyle(MONO);
        input.setPromptText("Type a command (no PTY: full-screen programs are unsupported)");
        prompt.setStyle(MONO);
        HBox.setHgrow(input, Priority.ALWAYS);
        HBox inputRow = new HBox(6, prompt, input);
        inputRow.setPadding(new Insets(4));

        interrupt.setOnAction(e -> interruptAction());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, state, spacer, interrupt);
        header.setPadding(new Insets(4));

        input.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.UP && historyIndex > 0) { input.setText(history.get(--historyIndex)); input.end(); e.consume(); }
            else if (e.getCode() == KeyCode.DOWN && historyIndex < history.size()) {
                historyIndex++; input.setText(historyIndex == history.size() ? "" : history.get(historyIndex)); input.end(); e.consume();
            }
        });
        input.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.TAB && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()) {
                completeInput(); e.consume();
            }
        });
        input.setOnAction(e -> submit());
        addEventFilter(KeyEvent.KEY_PRESSED, this::handleControlKeys);

        setTop(header);
        setCenter(transcript);
        setBottom(inputRow);
        ticker.setCycleCount(Timeline.INDEFINITE);
        ticker.play();
        refresh();
    }

    private void completeInput() {
        String before = input.getText();
        int caret = input.getCaretPosition();
        File currentDirectory = sessionDirectory();
        String completed = TerminalCompletion.complete(
                before, caret,
                currentDirectory == null ? Path.of(System.getProperty("user.home")) : currentDirectory.toPath(),
                System.getenv(),
                com.javelin_ide.build.ShellCommand.isWindows());
        if (!completed.equals(before)) {
            input.setText(completed);
            input.positionCaret(Math.min(completed.length(), caret + completed.length() - before.length()));
        }
    }

    private File sessionDirectory() {
        // Keep completion rooted in the same directory as commands submitted to this terminal.
        return directorySupplier.get();
    }

    /** Tab title: name, active virtualenv, and a dot while a command is running. */
    public ReadOnlyStringProperty titleProperty() { return title; }

    public void focusInput() { input.requestFocus(); }

    /** Asks before closing a terminal that is still running something. Returns true if closing may proceed. */
    public boolean confirmClose() {
        if (!session.isBusy()) return true;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Close Terminal");
        alert.setHeaderText(baseName + " is still running a command");
        alert.setContentText(session.foreground() + "\n\nClosing this terminal will stop it.");
        return Dialogs.show(alert).filter(b -> b == ButtonType.OK).isPresent();
    }

    /** Stops the shell and its tick timer. Call once when the tab is closed. */
    public void dispose() {
        ticker.stop();
        session.close();
    }

    private void tick() {
        session.tick();
        // A background job can exit without any other state change, so the label is refreshed when the count moves.
        if (session.backgroundJobs() != lastBackground) refresh();
    }

    private void handleControlKeys(KeyEvent e) {
        if (!e.isControlDown() || e.isAltDown() || e.isMetaDown() || e.isShiftDown()) return;
        switch (e.getCode()) {
            case C -> {
                if (!input.getSelectedText().isEmpty() || !transcript.getSelectedText().isEmpty()) return; // let the default copy happen
                interruptAction();
                e.consume();
            }
            case D -> {
                if (input.getText().isEmpty()) {
                    try { session.exitIfIdle(); } catch (IOException ex) { append("[could not reach shell: " + ex.getMessage() + "]"); }
                }
                e.consume();
            }
            case L -> { transcript.clear(); e.consume(); }
            default -> { }
        }
    }

    private void interruptAction() {
        if (session.interrupt()) { append("^C"); return; }
        if (!input.getText().isEmpty()) { append(prompt.getText() + " " + input.getText() + "^C"); input.clear(); }
    }

    private void submit() {
        String line = input.getText();
        input.clear();
        history.add(line);
        historyIndex = history.size();
        try {
            if (!line.isEmpty()) append(prompt.getText() + " " + line);
            session.submit(line);
        } catch (IOException ex) {
            append("[could not reach shell: " + ex.getMessage() + "]");
        }
    }

    private void refresh() {
        String venv = session.venvName().orElse(null);
        boolean busy = session.isBusy();
        prompt.setText(venv == null ? "$" : "(" + venv + ") $");
        prompt.getStyleClass().remove("prompt-venv");
        if (venv != null) prompt.getStyleClass().add("prompt-venv");
        int background = session.backgroundJobs();
        lastBackground = background;
        String jobs = background == 0 ? "" : "  \u2022  " + background + " background job" + (background == 1 ? "" : "s");
        state.setText(busy ? "\u25CF Running: " + session.foreground() + jobs : "Idle" + jobs + (venv == null ? "" : "  \u2022  virtualenv: " + venv));
        interrupt.setDisable(!busy);
        title.set(baseName + (venv == null ? "" : " (" + venv + ")") + (busy ? " \u25CF" : ""));
    }

    private void append(String line) {
        transcript.appendText(line + "\n");
        if (transcript.getLength() > MAX_CHARS) transcript.deleteText(0, transcript.getLength() - KEEP_CHARS);
    }
}
