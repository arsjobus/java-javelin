package com.javelin.ui;

/**
 * Keeps count of the dialogs and file choosers that are open at the moment, kept free of JavaFX so
 * it can be unit tested. Dialogs open from dialogs (the Settings dialog asks for a shortcut, then
 * asks to confirm moving it; Replace asks twice in a row), and the main window may only be restored
 * once the <em>last</em> of them has closed and only once for the whole sequence: restoring after
 * each one raises the main window over a dialog that is still open, which then takes the focus
 * back, and that fight is the flicker.
 */
final class DialogNesting {
  private int open;
  private boolean restorePending;

  /** A dialog or chooser is about to be shown. */
  void opened() {
    open++;
  }

  /** Whether any dialog or chooser is open now. */
  boolean any() {
    return open > 0;
  }

  /**
   * Whether a deferred restore can run without raising the main window over a newly opened dialog.
   */
  boolean restoreReady() {
    return restorePending && open == 0;
  }

  /** How many are open now. */
  int depth() {
    return open;
  }

  /**
   * A dialog or chooser has closed.
   *
   * @return true when the main window should now be restored: this was the last open dialog and no
   *     restore is already waiting to run. The caller must then call {@link #restored()} after
   *     restoring.
   */
  boolean closed() {
    if (open > 0) open--;
    if (open > 0 || restorePending) return false;
    restorePending = true;
    return true;
  }

  /**
   * The deferred restore asked for by {@link #closed()} has run or was skipped because another
   * dialog opened.
   */
  void restored() {
    restorePending = false;
  }

  /** Whether a restore has been asked for and has not run yet. */
  boolean restoreWaiting() {
    return restorePending;
  }
}
