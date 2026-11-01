package com.javelin_ide.ui.shell;

import com.javelin_ide.build.Problem;
import com.javelin_ide.build.ProcessManager;
import com.javelin_ide.ui.OutputPanel;
import com.javelin_ide.ui.ProblemsPanel;
import com.javelin_ide.ui.ReferencesPanel;
import com.javelin_ide.ui.TerminalsPanel;
import java.io.File;
import java.util.function.Supplier;
import javafx.collections.ListChangeListener;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;

/** The editor area with the Output, Problems, References and Terminal panels under it, which can be shown and hidden. */
public final class BottomPanel {
    private final OutputPanel output = new OutputPanel();
    private final ProblemsPanel problems = new ProblemsPanel();
    private final TerminalsPanel terminals;
    private final ReferencesPanel references = new ReferencesPanel();
    private final Tab outputTab = new Tab("Output", output);
    private final Tab problemsTab = new Tab("Problems (0)", problems);
    private final Tab referencesTab = new Tab("References", references);
    private final Tab terminalTab;
    private final TabPane bottomTabs;
    private final SplitPane editorSplit;

    public BottomPanel(Node editors, ProcessManager processes, Supplier<File> projectDirectory) {
        terminals = new TerminalsPanel(processes, projectDirectory);
        terminalTab = new Tab("Terminal", terminals);
        bottomTabs = new TabPane(outputTab, problemsTab, referencesTab, terminalTab);
        editorSplit = new SplitPane(editors);
        bottomTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        editorSplit.setOrientation(Orientation.VERTICAL);
        problems.items().addListener((ListChangeListener<Problem>) c -> problemsTab.setText("Problems (" + problems.items().size() + ")"));
    }

    public Node node() { return editorSplit; }
    public OutputPanel output() { return output; }
    public ProblemsPanel problems() { return problems; }
    public ReferencesPanel references() { return references; }
    public TerminalsPanel terminals() { return terminals; }
    public Tab outputTab() { return outputTab; }
    public Tab problemsTab() { return problemsTab; }
    public Tab referencesTab() { return referencesTab; }
    public Tab terminalTab() { return terminalTab; }

    public void show(Tab tab) {
        if (!editorSplit.getItems().contains(bottomTabs)) { editorSplit.getItems().add(bottomTabs); editorSplit.setDividerPositions(0.7); }
        bottomTabs.getSelectionModel().select(tab);
    }

    public void toggle() {
        if (editorSplit.getItems().contains(bottomTabs)) editorSplit.getItems().remove(bottomTabs);
        else show(bottomTabs.getSelectionModel().getSelectedItem());
    }
}
