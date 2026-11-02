package com.javelin.ui.shell;

import com.javelin.settings.LayoutPrefs;
import com.javelin.settings.Settings;
import java.io.IOException;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

/**
 * Remembers where the user put the sidebar and bottom-panel dividers, and puts them back at the next start. The rules
 * (what is a sensible size, where it is written) are in {@link LayoutPrefs}; this class only connects them to the two
 * dividers.
 *
 * <p>Dragging a divider reports a new size for every pixel moved, so a save is scheduled half a second after the last
 * change rather than on each one, and {@link #flush()} (called when the window closes) saves anything still waiting.
 * A size that cannot be written is reported once on the status line and then ignored: losing a divider position must
 * never get in the way of editing.
 */
public final class LayoutMemory {
    private static final Duration QUIET_TIME = Duration.millis(500);

    private final LayoutPrefs prefs;
    private final Consumer<String> status;
    private final PauseTransition quiet = new PauseTransition(QUIET_TIME);
    private double pendingWidth;
    private double pendingHeight;
    private boolean reportedProblem;

    public LayoutMemory(Settings settings, WorkArea area, BottomPanel bottom, Consumer<String> status) {
        this.prefs = new LayoutPrefs(settings);
        this.status = status;
        prefs.sidebarWidth().ifPresent(area::sidebarWidth);
        prefs.bottomHeight().ifPresent(bottom::bottomHeight);
        area.onSidebarWidthChanged(width -> { pendingWidth = width; quiet.playFromStart(); });
        bottom.onHeightChanged(height -> { pendingHeight = height; quiet.playFromStart(); });
        quiet.setOnFinished(event -> flush());
    }

    /** Saves any size that changed since the last save; does nothing when there is none. */
    public void flush() {
        quiet.stop();
        try {
            if (pendingWidth > 0) prefs.rememberSidebarWidth(pendingWidth);
            if (pendingHeight > 0) prefs.rememberBottomHeight(pendingHeight);
            pendingWidth = 0;
            pendingHeight = 0;
        } catch (IOException ex) {
            if (!reportedProblem) status.accept("Could not remember the window layout: " + ex.getMessage());
            reportedProblem = true;
            pendingWidth = 0;
            pendingHeight = 0;
        }
    }
}
