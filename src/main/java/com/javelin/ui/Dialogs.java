package com.javelin.ui;

import java.util.Optional;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.control.Dialog;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * The one place dialogs are shown from. A dialog without an owner window makes macOS treat it as a separate
 * application window: when it closes (Cancel, Escape, OK) the main window is minimized or lost behind other windows.
 * Every dialog and native file chooser therefore goes through here, so it is owned by the main window and the main
 * window is restored afterwards (see {@link WindowRestore}).
 */
public final class Dialogs {
    private Dialogs() {}

    private static Stage owner;

    /** Sets the window that owns every dialog; called once when the main window is created. */
    public static void setOwner(Stage stage) { owner = stage; }

    /** Shows {@code dialog} owned by the main window, waits for it, then restores the main window. */
    public static <T> Optional<T> show(Dialog<T> dialog) {
        if (owner != null && dialog.getOwner() == null) {
            dialog.initOwner(owner);
            dialog.initModality(Modality.WINDOW_MODAL);
        }
        try { return dialog.showAndWait(); }
        finally { restoreOwner(); }
    }

    /** Runs a native file or directory chooser and restores the main window afterwards, whatever the outcome. */
    public static <T> T guarded(Supplier<T> chooser) {
        try { return chooser.get(); }
        finally { restoreOwner(); }
    }

    /** Puts the main window back now and once more after the current event, when the OS may still be shuffling windows. */
    public static void restoreOwner() {
        if (owner == null) return;
        WindowRestore.Target target = new StageTarget(owner);
        WindowRestore.restore(target);
        Platform.runLater(() -> WindowRestore.restore(target));
    }

    private record StageTarget(Stage stage) implements WindowRestore.Target {
        @Override public boolean isIconified() { return stage.isIconified(); }
        @Override public void setIconified(boolean iconified) { stage.setIconified(iconified); }
        @Override public void toFront() { stage.toFront(); }
        @Override public void requestFocus() { stage.requestFocus(); }
    }
}
