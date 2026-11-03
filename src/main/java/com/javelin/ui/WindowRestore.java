package com.javelin.ui;

/**
 * What to do with the main window after the last dialog has closed, kept free of JavaFX so it can be unit tested.
 *
 * <p>On macOS (and some Linux window managers) closing a dialog that has no owner window hands the application's
 * focus away, and the main window ends up minimized or behind other windows. The fix has two parts: every dialog is
 * given an owner window (see {@link Dialogs}), and when the last dialog has closed the main window is put back the
 * way the user left it with {@link #restore(Target)}.
 *
 * <p>The restore does as little as possible. Raising and focusing a window that is already in front and focused
 * makes some window managers (Windows in particular) hand the focus back and forth between the main window and the
 * window that is closing, which shows as the screen flickering "like windows switching rapidly"; so a window that
 * needs nothing is left completely alone.
 */
public final class WindowRestore {
    private WindowRestore() {}

    /** The few window operations needed to bring a window back; {@link Dialogs} adapts a JavaFX {@code Stage} to it. */
    public interface Target {
        boolean isIconified();
        boolean isFocused();
        void setIconified(boolean iconified);
        void toFront();
        void requestFocus();
    }

    /** What {@link #restore(Target)} had to do. */
    public enum Outcome {
        /** The window was visible and focused: nothing was done. */
        LEFT_ALONE,
        /** The window was visible but had lost focus: it was raised and focused. */
        REFOCUSED,
        /** The window had been minimized: it was restored, raised and focused. */
        UNMINIMIZED
    }

    /** Brings {@code window} back after the last dialog closed, touching it only as far as needed. */
    public static Outcome restore(Target window) {
        if (window.isIconified()) {
            window.setIconified(false);
            window.toFront();
            window.requestFocus();
            return Outcome.UNMINIMIZED;
        }
        if (!window.isFocused()) {
            window.toFront();
            window.requestFocus();
            return Outcome.REFOCUSED;
        }
        return Outcome.LEFT_ALONE;
    }
}
