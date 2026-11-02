package com.javelin.ui;

/**
 * Mouse-gesture decisions for the project tree, kept free of JavaFX so they can be unit tested.
 *
 * <p>Two things made clicking in the tree unreliable:
 * <ul>
 *   <li>Every row started a drag-and-drop as soon as the pointer moved a pixel or two between press and release.
 *       A drag swallows the click, so the item was not opened and a few clicks were "lost".</li>
 *   <li>Opening relied on the tree's selection at the moment of the second click instead of on the row that was
 *       actually clicked, so anything that changed the selection in between (a tree rebuild, a drag) opened or
 *       selected another file.</li>
 * </ul>
 * This class decides "was that a double-click on the same row?" from the presses themselves and "is this a real
 * drag?" from how far the pointer travelled, so neither depends on what JavaFX counted or selected.
 */
public final class TreeGestures {
    /** Pointer travel (in pixels) below which a press-and-move is still a click, not a drag. */
    public static final double DRAG_THRESHOLD = 8.0;
    /** Longest gap between two presses on the same row that still counts as a double-click. */
    public static final long DOUBLE_CLICK_MILLIS = 500;

    private Object lastRow;
    private long lastPressMillis;
    private double pressX, pressY;

    /**
     * Records a primary-button press on {@code row} and reports whether it completes a double-click, that is: the
     * previous press was on the same row (by {@code equals}) no more than {@link #DOUBLE_CLICK_MILLIS} earlier.
     * A completed double-click is used up, so a third quick press starts a new sequence instead of firing again.
     */
    public boolean press(Object row, long nowMillis, double x, double y) {
        pressX = x;
        pressY = y;
        if (row == null) { lastRow = null; return false; }
        boolean second = lastRow != null && lastRow.equals(row)
                && nowMillis - lastPressMillis >= 0 && nowMillis - lastPressMillis <= DOUBLE_CLICK_MILLIS;
        if (second) { lastRow = null; return true; }
        lastRow = row;
        lastPressMillis = nowMillis;
        return false;
    }

    /** True once the pointer has moved at least {@link #DRAG_THRESHOLD} pixels from where the last press happened. */
    public boolean dragIntended(double x, double y) {
        return Math.hypot(x - pressX, y - pressY) >= DRAG_THRESHOLD;
    }

    /** Forgets the pending click, e.g. when the tree is rebuilt and its rows are no longer the ones that were clicked. */
    public void reset() {
        lastRow = null;
    }
}
