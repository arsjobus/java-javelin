package com.javelin.ui.shell;

import javafx.scene.control.SplitPane;
import java.util.function.DoubleConsumer;
import javafx.scene.control.SplitPane.Divider;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;

/**
 * The sidebar (the project tree) and the editor area side by side, with a divider the user can drag to resize the
 * sidebar. It is the same {@link SplitPane} control, and so the same look and behaviour, as the divider between the
 * editors and the terminal that {@link BottomPanel} puts inside the editor area: the two dividers are independent, the
 * terminal keeps its place whatever the sidebar's width, and each follows the window when it is resized.
 *
 * <p>The sidebar keeps the width the user gave it (in pixels, not as a share of the window): growing the window gives
 * the extra space to the editors, and hiding the sidebar (View > Focus Mode) and showing it again restores the same
 * width. The width stays between {@link #MIN_SIDEBAR_WIDTH} and what leaves {@link #MIN_EDITOR_WIDTH} for the editors.
 * Hiding is done by the sidebar's own {@code visible} property, so the code that toggles Focus Mode does not need to
 * know this class exists.
 */
public final class WorkArea {
    public static final double DEFAULT_SIDEBAR_WIDTH = 250;
    public static final double MIN_SIDEBAR_WIDTH = 120;
    public static final double MIN_EDITOR_WIDTH = 240;

    private final SplitPane split = new SplitPane();
    private final Region sidebar;
    private double sidebarWidth = DEFAULT_SIDEBAR_WIDTH;
    private boolean applying;
    private boolean pressed; // a mouse button is down on the split pane: a divider move now is the user dragging
    private boolean dragged;
    private DoubleConsumer onSidebarWidthChanged = width -> {};

    public WorkArea(Region sidebar, Region content) {
        this.sidebar = sidebar;
        split.getItems().add(content);
        SplitPane.setResizableWithParent(sidebar, false);
        sidebar.setMinWidth(MIN_SIDEBAR_WIDTH);
        content.setMinWidth(MIN_EDITOR_WIDTH); // the divider stops where the editors would get narrower than this
        split.widthProperty().addListener((o, was, now) -> applyWidth());
        split.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> { pressed = true; dragged = false; });
        split.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            pressed = false;
            if (dragged) onSidebarWidthChanged.accept(sidebarWidth); // once per drag, not once per pixel
            dragged = false;
        });
        sidebar.visibleProperty().addListener((o, was, visible) -> { if (visible) attach(); else detach(); });
        if (sidebar.isVisible()) attach();
    }

    /** The node to place in the window. */
    public Region node() { return split; }

    public boolean sidebarShown() { return split.getItems().contains(sidebar); }

    /** The width the sidebar has, or has when it is shown again, in pixels, measured to the middle of the divider. */
    public double sidebarWidth() { return sidebarWidth; }

    /**
     * Sets the sidebar's width (within its limits), as if the user had dragged the divider there. Before the window has
     * a size (a width restored from the last run, set before the window is shown) the width is kept as asked and
     * limited once the size is known, so a wide sidebar is not squeezed to the minimum on the way.
     */
    public void sidebarWidth(double width) {
        sidebarWidth = split.getWidth() > 0 ? clampWidth(width, split.getWidth()) : Math.max(MIN_SIDEBAR_WIDTH, width);
        applyWidth();
    }

    /** Called with the new width (pixels) once each time the user finishes dragging the divider; not called for {@link #sidebarWidth(double)}. */
    public void onSidebarWidthChanged(DoubleConsumer listener) { onSidebarWidthChanged = listener == null ? width -> {} : listener; }

    // ---- the policy, free of any control so it can be tested on its own ----

    /** {@code width} limited to what the sidebar may have in a window area {@code total} pixels wide (the smaller limit wins in a tiny window). */
    static double clampWidth(double width, double total) {
        double max = Math.max(MIN_SIDEBAR_WIDTH, total - MIN_EDITOR_WIDTH);
        return Math.max(MIN_SIDEBAR_WIDTH, Math.min(width, max));
    }

    /** The divider position (0 to 1) that gives the sidebar {@code width} pixels out of {@code total}. */
    static double dividerPosition(double width, double total) { return total <= 0 ? 0.5 : clampWidth(width, total) / total; }

    // ---- wiring ----

    private void attach() {
        if (sidebarShown()) return;
        split.getItems().add(0, sidebar);
        Divider divider = split.getDividers().get(0);
        divider.positionProperty().addListener((o, was, now) -> {
            if (applying || split.getWidth() <= 0) return;
            sidebarWidth = clampWidth(now.doubleValue() * split.getWidth(), split.getWidth());
            if (pressed) dragged = true;
        });
        applyWidth();
    }

    private void detach() { split.getItems().remove(sidebar); }

    private void applyWidth() {
        double total = split.getWidth();
        if (!sidebarShown() || total <= 0) return; // applied when the window first gets a size
        applying = true;
        try {
            split.setDividerPositions(dividerPosition(sidebarWidth, total));
        } finally {
            applying = false;
        }
    }
}
