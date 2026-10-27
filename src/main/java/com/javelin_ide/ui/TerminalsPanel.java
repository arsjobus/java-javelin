package com.javelin_ide.ui;

import com.javelin_ide.build.ProcessManager;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

import java.io.File;
import java.util.function.Supplier;

/** Tabbed terminals. Each tab owns its own shell, working state, and virtualenv. Call from the FX thread only. */
public final class TerminalsPanel extends BorderPane {
    private final ProcessManager processes;
    private final Supplier<File> directory;
    private final TabPane tabs = new TabPane();
    private int counter;

    public TerminalsPanel(ProcessManager processes, Supplier<File> directory) {
        this.processes = processes;
        this.directory = directory;
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabs.setTabMinWidth(90);
        Button add = new Button("+ New Terminal");
        add.setOnAction(e -> newTerminal());
        HBox bar = new HBox(add);
        bar.setPadding(new Insets(4));
        setTop(bar);
        setCenter(tabs);
        newTerminal();
    }

    public TerminalPanel newTerminal() {
        TerminalPanel panel = new TerminalPanel("Terminal " + (++counter), processes, directory);
        Tab tab = new Tab();
        tab.textProperty().bind(panel.titleProperty());
        tab.setContent(panel);
        tab.setOnCloseRequest(e -> { if (!panel.confirmClose()) e.consume(); });
        tab.setOnClosed(e -> panel.dispose());
        tabs.getTabs().add(tab);
        tabs.getSelectionModel().select(tab);
        panel.focusInput();
        return panel;
    }

    /** Stops and removes every terminal session when the active project changes. */
    public void closeAll() {
        for (Tab tab : tabs.getTabs()) {
            if (tab.getContent() instanceof TerminalPanel panel) panel.dispose();
        }
        tabs.getTabs().clear();
    }

    /** Focuses the selected terminal, opening one if none are left. */
    public void focusInput() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        if (tab == null) newTerminal();
        else if (tab.getContent() instanceof TerminalPanel panel) panel.focusInput();
    }
}
