package com.javelin.ui.shell;

import com.javelin.build.Problem;
import com.javelin.build.ProcessManager;
import com.javelin.ui.AiPanel;
import com.javelin.ui.OutputPanel;
import com.javelin.ui.ProblemsPanel;
import com.javelin.ui.ReferencesPanel;
import com.javelin.ui.TerminalsPanel;
import java.io.File;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;
import javafx.collections.ListChangeListener;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.control.SplitPane;
import javafx.scene.control.SplitPane.Divider;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;

/**
 * The editor area with the Output, Problems, References, AI and Terminal panels under it, which can be shown and hidden.
 *
 * <p>Like the sidebar (see {@link WorkArea}), the panel keeps the height in pixels that the user gave it: a taller
 * window gives the extra space to the editors. Until the user drags the divider (or a height is set from the last run)
 * the panel takes {@link #DEFAULT_SHARE} of the area.
 */
public final class BottomPanel {
    public static final double DEFAULT_SHARE = 0.3;
    public static final double MIN_BOTTOM_HEIGHT = 80;
    public static final double MIN_EDITOR_HEIGHT = 120;

    private final OutputPanel output = new OutputPanel();
    private final ProblemsPanel problems = new ProblemsPanel();
    private final TerminalsPanel terminals;
    private final ReferencesPanel references = new ReferencesPanel();
    private final Tab outputTab = new Tab("Output", output);
    private final Tab problemsTab = new Tab("Problems (0)", problems);
    private final Tab referencesTab = new Tab("References", references);
    private final AiPanel ai = new AiPanel();
    private final Tab aiTab = new Tab("AI", ai);
    private final Tab terminalTab;
    private final TabPane bottomTabs;
    private final SplitPane editorSplit;
    private double bottomHeight; // pixels; 0 until the user (or the last run) chose one
    private boolean applying;
    private boolean pressed; // a mouse button is down on the split pane: a divider move now is the user dragging
    private boolean dragged;
    private boolean sized; // the divider has been placed with a real window height; moves before that are layout settling, not the user
    private DoubleConsumer onHeightChanged = height -> {};

    public BottomPanel(Node editors, ProcessManager processes, Supplier<File> projectDirectory) {
        terminals = new TerminalsPanel(processes, projectDirectory);
        terminalTab = new Tab("Terminal", terminals);
        bottomTabs = new TabPane(outputTab, problemsTab, referencesTab, aiTab, terminalTab);
        editorSplit = new SplitPane(editors);
        bottomTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        editorSplit.setOrientation(Orientation.VERTICAL);
        SplitPane.setResizableWithParent(bottomTabs, false);
        bottomTabs.setMinHeight(MIN_BOTTOM_HEIGHT);
        editorSplit.heightProperty().addListener((o, was, now) -> applyHeight());
        editorSplit.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> { pressed = true; dragged = false; });
        editorSplit.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            pressed = false;
            if (dragged) onHeightChanged.accept(bottomHeight); // once per drag, not once per pixel
            dragged = false;
        });
        problems.items().addListener((ListChangeListener<Problem>) c -> problemsTab.setText("Problems (" + problems.items().size() + ")"));
    }

    public Region node() { return editorSplit; }
    public OutputPanel output() { return output; }
    public ProblemsPanel problems() { return problems; }
    public ReferencesPanel references() { return references; }
    public AiPanel ai() { return ai; }
    public TerminalsPanel terminals() { return terminals; }
    public Tab outputTab() { return outputTab; }
    public Tab problemsTab() { return problemsTab; }
    public Tab referencesTab() { return referencesTab; }
    public Tab aiTab() { return aiTab; }
    public Tab terminalTab() { return terminalTab; }

    public void show(Tab tab) {
        if (!editorSplit.getItems().contains(bottomTabs)) {
            editorSplit.getItems().add(bottomTabs);
            Divider divider = editorSplit.getDividers().get(0);
            divider.positionProperty().addListener((o, was, now) -> {
                if (applying || !sized || editorSplit.getHeight() <= 0) return;
                bottomHeight = clampHeight((1 - now.doubleValue()) * editorSplit.getHeight(), editorSplit.getHeight());
                if (pressed) dragged = true;
            });
            applyHeight();
        }
        bottomTabs.getSelectionModel().select(tab);
    }

    public void toggle() {
        if (editorSplit.getItems().contains(bottomTabs)) editorSplit.getItems().remove(bottomTabs);
        else show(bottomTabs.getSelectionModel().getSelectedItem());
    }

    /** The height in pixels the panel has when shown, or 0 while the user has not chosen one (it then takes {@link #DEFAULT_SHARE}). */
    public double bottomHeight() { return bottomHeight; }

    /**
     * Sets the panel's height as if the user had dragged the divider there (limited to what leaves room for the editors
     * once the window has a size). Not reported to {@link #onHeightChanged}.
     */
    public void bottomHeight(double height) {
        bottomHeight = height > 0 ? Math.max(MIN_BOTTOM_HEIGHT, height) : 0;
        applyHeight();
    }

    /** Called with the new height (pixels) once each time the user finishes dragging the divider between the editors and the panel. */
    public void onHeightChanged(DoubleConsumer listener) { onHeightChanged = listener == null ? height -> {} : listener; }

    // ---- the policy, free of any control so it can be tested on its own ----

    /** {@code height} limited to what the panel may have in an area {@code total} pixels tall (the smaller limit wins in a tiny window). */
    static double clampHeight(double height, double total) {
        double max = Math.max(MIN_BOTTOM_HEIGHT, total - MIN_EDITOR_HEIGHT);
        return Math.max(MIN_BOTTOM_HEIGHT, Math.min(height, max));
    }

    /** The divider position (0 at the top, 1 at the bottom) that gives the panel {@code height} pixels out of {@code total}; the default share when no height was chosen. */
    static double dividerPosition(double height, double total) {
        if (total <= 0) return 1 - DEFAULT_SHARE;
        return height <= 0 ? 1 - DEFAULT_SHARE : 1 - clampHeight(height, total) / total;
    }

    private void applyHeight() {
        if (!editorSplit.getItems().contains(bottomTabs)) return;
        double total = editorSplit.getHeight();
        if (total <= 0) { // no size yet: place the default now, a height from the last run is applied when the area is laid out
            if (bottomHeight <= 0) place(dividerPosition(0, 0));
            return;
        }
        place(dividerPosition(bottomHeight, total));
        sized = true;
    }

    private void place(double position) {
        applying = true;
        try {
            editorSplit.setDividerPositions(position);
        } finally {
            applying = false;
        }
    }
}
