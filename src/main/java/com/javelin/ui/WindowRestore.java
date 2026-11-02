package com.javelin.ui;

/**
 * What to do with the main window after a dialog closes, kept free of JavaFX so it can be unit tested.
 *
 * <p>On macOS (and some Linux window managers) closing a dialog that has no owner window hands the application's
 * focus away, and the main window ends up minimized or behind other windows. The fix has two parts: every dialog is
 * given the main window as its owner (see {@link Dialogs}), and when a dialog has closed the main window is put back
 * the way the user left it with {@link #restore(Target)}.
 */
public final class WindowRestore {
    private WindowRestore() {}

    /** The few window operations needed to bring a window back; {@link Dialogs} adapts a JavaFX {@code Stage} to it. */
    public interface Target {
        boolean isIconified();
        void setIconified(boolean iconified);
        void toFront();
        void requestFocus();
    }

    /**
     * Brings {@code window} back after a dialog: restores it if it was minimized, raises it and gives it focus.
     *
     * @return true when the window had been minimized and was restored
     */
    public static boolean restore(Target window) {
        boolean wasMinimized = window.isIconified();
        if (wasMinimized) window.setIconified(false);
        window.toFront();
        window.requestFocus();
        return wasMinimized;
    }
}
