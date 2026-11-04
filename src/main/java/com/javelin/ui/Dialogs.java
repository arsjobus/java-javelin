package com.javelin.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.Dialog;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * The one place dialogs are shown from. Two things go wrong with dialogs, and both look like the
 * main window being minimized or the screen flickering as if windows were switching rapidly:
 *
 * <ul>
 *   <li>A dialog without an owner window makes macOS treat it as a separate application window:
 *       when it closes (Cancel, Escape, OK) the main window is minimized or lost behind other
 *       windows.
 *   <li>A dialog opened while another dialog is open (Settings asking for a shortcut, then to
 *       confirm moving it) but owned by the <em>main</em> window instead of the open dialog is a
 *       sibling of it, not its child; closing the child raises the main window over the parent
 *       dialog, which takes the focus back, and the two keep exchanging it. Raising the main window
 *       after <em>every</em> dialog has the same effect.
 * </ul>
 *
 * <p>So every dialog and native file chooser goes through here: it is owned by the dialog that is
 * open at the moment (or by the main window when none is), and the main window is restored once,
 * after the last one has closed, and only if it actually needs it (see {@link WindowRestore}).
 */
public final class Dialogs {
  private Dialogs() {}

  private static Stage owner;

  /**
   * Windows of the dialogs showing now, innermost last. Only touched on the JavaFX application
   * thread.
   */
  private static final List<Window> showing = new ArrayList<>();

  private static final DialogNesting nesting = new DialogNesting();

  /** Sets the window that owns every dialog; called once when the main window is created. */
  public static void setOwner(Stage stage) {
    owner = stage;
  }

  /**
   * Shows {@code dialog} owned by the dialog that is open (or the main window), waits for it, then
   * restores the main window if needed.
   */
  public static <T> Optional<T> show(Dialog<T> dialog) {
    Window parent = parentFor(dialog.getOwner());
    if (parent != null && dialog.getOwner() != parent) {
      dialog.initOwner(parent);
      dialog.initModality(Modality.WINDOW_MODAL);
    }
    WindowTracker tracker = new WindowTracker(dialog);
    dialog.showingProperty().addListener(tracker);
    nesting.opened();
    try {
      return dialog.showAndWait();
    } finally {
      dialog.showingProperty().removeListener(tracker);
      if (tracker.window != null) showing.remove(tracker.window);
      closed();
    }
  }

  /**
   * Runs a native file or directory chooser and restores the main window afterwards, whatever the
   * outcome.
   */
  public static <T> T guarded(Supplier<T> chooser) {
    nesting.opened();
    try {
      return chooser.get();
    } finally {
      closed();
    }
  }

  // ---- internals ----

  /**
   * The window a new dialog belongs to: the innermost dialog that is open, or the main window. An
   * owner the caller chose is kept only when it is not the main window while another dialog is open
   * (the Settings dialog hands its own main-window owner to the confirmations it opens, which would
   * make them siblings of it).
   */
  private static Window parentFor(Window requested) {
    Window innermost = showing.isEmpty() ? null : showing.get(showing.size() - 1);
    if (innermost != null
        && (requested == null || requested == owner || !showing.contains(requested)))
      return innermost;
    return requested != null ? requested : owner;
  }

  private static void closed() {
    if (!nesting.closed())
      return; // another dialog is still open, or a restore is already on its way
    if (owner == null) {
      nesting.restored();
      return;
    }
    scheduleRestore();
  }

  private static void scheduleRestore() {
    WindowRestore.Target target = new StageTarget(owner);
    // Later, not now: the dialog's window is still going away and the OS is still choosing which
    // window is next.
    Platform.runLater(
        () -> {
          try {
            if (nesting.restoreReady()) WindowRestore.restore(target);
          } finally {
            nesting.restored();
          }
        });
  }

  /** Remembers the dialog's window while it shows, so dialogs opened from it can be owned by it. */
  private static final class WindowTracker implements ChangeListener<Boolean> {
    private final Dialog<?> dialog;
    Window window;

    WindowTracker(Dialog<?> dialog) {
      this.dialog = dialog;
    }

    @Override
    public void changed(ObservableValue<? extends Boolean> value, Boolean was, Boolean now) {
      if (now && dialog.getDialogPane().getScene() != null) {
        window = dialog.getDialogPane().getScene().getWindow();
        if (!showing.contains(window)) showing.add(window);
      } else if (!now && window != null) showing.remove(window);
    }
  }

  private record StageTarget(Stage stage) implements WindowRestore.Target {
    @Override
    public boolean isIconified() {
      return stage.isIconified();
    }

    @Override
    public boolean isFocused() {
      return stage.isFocused();
    }

    @Override
    public void setIconified(boolean iconified) {
      stage.setIconified(iconified);
    }

    @Override
    public void toFront() {
      stage.toFront();
    }

    @Override
    public void requestFocus() {
      stage.requestFocus();
    }
  }
}
