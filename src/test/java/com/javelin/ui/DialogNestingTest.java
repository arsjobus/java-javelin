package com.javelin.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The main window is restored once, after the last of several nested or consecutive dialogs has
 * closed.
 */
class DialogNestingTest {
  @Test
  void aSingleDialogAsksForOneRestoreWhenItCloses() {
    DialogNesting n = new DialogNesting();
    n.opened();
    assertTrue(n.any());
    assertTrue(n.closed());
    assertFalse(n.any());
    assertTrue(n.restoreWaiting());
    n.restored();
    assertFalse(n.restoreWaiting());
  }

  @Test
  void aDialogOpenedFromADialogDoesNotRestoreTheMainWindowWhenItClosesFirst() {
    DialogNesting n = new DialogNesting();
    n.opened(); // Settings
    n.opened(); // "Move this shortcut?" confirmation
    assertEquals(2, n.depth());
    assertFalse(
        n.closed(),
        "the Settings dialog is still open: raising the main window now would fight it for focus");
    assertTrue(n.any());
    assertTrue(n.closed(), "now the last one has closed");
  }

  @Test
  void twoDialogsInARowAreOnlyRestoredOnceWhileTheFirstRestoreIsStillWaiting() {
    DialogNesting n = new DialogNesting();
    n.opened();
    assertTrue(n.closed()); // Replace: "Find" is cancelled/answered
    n.opened();
    assertFalse(
        n.restoreReady(), "a pending restore must not raise the main window over the next dialog");
    assertFalse(n.closed(), "the restore for the first is still waiting; one is enough");
    n.restored();
    n.opened();
    assertTrue(n.closed(), "once it ran, the next dialog asks again");
  }

  @Test
  void aCloseWithoutAnOpenNeverGoesNegative() {
    DialogNesting n = new DialogNesting();
    assertTrue(n.closed());
    assertEquals(0, n.depth());
    n.restored();
    n.opened();
    assertEquals(1, n.depth());
  }
}
