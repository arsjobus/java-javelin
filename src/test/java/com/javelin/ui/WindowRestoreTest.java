package com.javelin.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** After a dialog closes (Cancel, Escape or OK) the main window must be back on screen, not minimized. */
class WindowRestoreTest {
    /** A window that records what was done to it. */
    private static final class FakeWindow implements WindowRestore.Target {
        boolean iconified;
        final List<String> calls = new ArrayList<>();
        FakeWindow(boolean iconified) { this.iconified = iconified; }
        @Override public boolean isIconified() { return iconified; }
        @Override public void setIconified(boolean value) { iconified = value; calls.add("setIconified(" + value + ")"); }
        @Override public void toFront() { calls.add("toFront"); }
        @Override public void requestFocus() { calls.add("requestFocus"); }
    }

    @Test void aMinimizedWindowIsRestoredRaisedAndFocused() {
        FakeWindow window = new FakeWindow(true);
        assertTrue(WindowRestore.restore(window), "reports that it had to un-minimize the window");
        assertFalse(window.iconified);
        assertEquals(List.of("setIconified(false)", "toFront", "requestFocus"), window.calls);
    }

    @Test void aVisibleWindowIsNotTouchedBeyondBeingRaisedAndFocused() {
        FakeWindow window = new FakeWindow(false);
        assertFalse(WindowRestore.restore(window));
        assertFalse(window.iconified);
        assertEquals(List.of("toFront", "requestFocus"), window.calls, "no needless un-minimize call");
    }

    @Test void restoringTwiceIsHarmless() {
        FakeWindow window = new FakeWindow(true);
        WindowRestore.restore(window);
        assertFalse(WindowRestore.restore(window), "the second pass finds it already restored");
        assertFalse(window.iconified);
    }
}
