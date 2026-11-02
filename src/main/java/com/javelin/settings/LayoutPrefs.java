package com.javelin.settings;

import com.javelin.settings.Settings.Scope;
import java.io.IOException;
import java.util.List;
import java.util.OptionalDouble;

/**
 * The window layout the user arranged by dragging dividers: how wide the project sidebar is and how tall the bottom
 * panel (Output, Problems, References, Terminal) is, in pixels. Both are kept in the user settings file
 * ({@code layout.sidebarWidth}, {@code layout.bottomHeight}), never in a project's, so opening a project cannot move the
 * user's dividers, and a window starts the way the last one was left.
 *
 * <p>A value is written with {@link Settings#saveKeys}, which touches only that key on disk, so several windows (one per
 * project) that each save their own layout do not undo each other's other settings. A missing, unreadable or absurd value
 * is ignored and the window's default applies.
 */
public final class LayoutPrefs {
    public static final String SIDEBAR_WIDTH = "layout.sidebarWidth";
    public static final String BOTTOM_HEIGHT = "layout.bottomHeight";

    /** The largest size that is believed; anything above is a typo or a leftover from a much bigger screen. */
    static final double LARGEST = 10_000;

    private final Settings settings;

    public LayoutPrefs(Settings settings) { this.settings = settings; }

    /** The remembered sidebar width in pixels, or empty when none was saved or the saved one is not a sensible size. */
    public OptionalDouble sidebarWidth() { return read(SIDEBAR_WIDTH); }

    /** The remembered height of the bottom panel in pixels, or empty when none was saved or the saved one is not a sensible size. */
    public OptionalDouble bottomHeight() { return read(BOTTOM_HEIGHT); }

    /** Saves the sidebar width (rounded to whole pixels). Nothing is written when it is already the saved value. */
    public void rememberSidebarWidth(double pixels) throws IOException { remember(SIDEBAR_WIDTH, pixels); }

    /** Saves the bottom panel's height (rounded to whole pixels). Nothing is written when it is already the saved value. */
    public void rememberBottomHeight(double pixels) throws IOException { remember(BOTTOM_HEIGHT, pixels); }

    /** Forgets both sizes, so the next window starts with the defaults again. */
    public void forget() throws IOException {
        settings.remove(Scope.USER, SIDEBAR_WIDTH);
        settings.remove(Scope.USER, BOTTOM_HEIGHT);
        settings.saveKeys(Scope.USER, List.of(SIDEBAR_WIDTH, BOTTOM_HEIGHT));
    }

    private OptionalDouble read(String key) {
        String text = settings.getUser(key);
        if (text == null) return OptionalDouble.empty();
        try {
            double value = Double.parseDouble(text.strip());
            return Double.isFinite(value) && value > 0 && value <= LARGEST ? OptionalDouble.of(value) : OptionalDouble.empty();
        } catch (NumberFormatException ex) {
            return OptionalDouble.empty();
        }
    }

    private void remember(String key, double pixels) throws IOException {
        if (!Double.isFinite(pixels) || pixels <= 0 || pixels > LARGEST) return; // a window that has no size yet reports 0
        String text = Long.toString(Math.round(pixels));
        if (text.equals(settings.getUser(key))) return;
        settings.put(Scope.USER, key, text);
        settings.saveKeys(Scope.USER, List.of(key));
    }
}
