package com.javelin.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * After the last dialog closes (Cancel, Escape or OK) the main window must be back on screen, not
 * minimized, and it must not be raised and focused again when it does not need it: that is what
 * made the screen flicker.
 */
class WindowRestoreTest {
  /** A window that records what was done to it. */
  private static final class FakeWindow implements WindowRestore.Target {
    boolean iconified;
    boolean focused;
    final List<String> calls = new ArrayList<>();

    FakeWindow(boolean iconified, boolean focused) {
      this.iconified = iconified;
      this.focused = focused;
    }

    @Override
    public boolean isIconified() {
      return iconified;
    }

    @Override
    public boolean isFocused() {
      return focused;
    }

    @Override
    public void setIconified(boolean value) {
      iconified = value;
      calls.add("setIconified(" + value + ")");
    }

    @Override
    public void toFront() {
      calls.add("toFront");
    }

    @Override
    public void requestFocus() {
      focused = true;
      calls.add("requestFocus");
    }
  }

  @Test
  void aMinimizedWindowIsRestoredRaisedAndFocused() {
    FakeWindow window = new FakeWindow(true, false);
    assertEquals(WindowRestore.Outcome.UNMINIMIZED, WindowRestore.restore(window));
    assertFalse(window.iconified);
    assertEquals(List.of("setIconified(false)", "toFront", "requestFocus"), window.calls);
  }

  @Test
  void aVisibleWindowThatLostFocusIsRaisedAndFocusedButNotUnminimized() {
    FakeWindow window = new FakeWindow(false, false);
    assertEquals(WindowRestore.Outcome.REFOCUSED, WindowRestore.restore(window));
    assertEquals(List.of("toFront", "requestFocus"), window.calls, "no needless un-minimize call");
  }

  @Test
  void aVisibleFocusedWindowIsLeftCompletelyAlone() {
    FakeWindow window = new FakeWindow(false, true);
    assertEquals(WindowRestore.Outcome.LEFT_ALONE, WindowRestore.restore(window));
    assertEquals(
        List.of(),
        window.calls,
        "raising a window that is already in front makes the focus bounce between windows");
  }

  @Test
  void restoringTwiceTouchesTheWindowOnlyTheFirstTime() {
    FakeWindow window = new FakeWindow(true, false);
    WindowRestore.restore(window);
    window.calls.clear();
    assertEquals(
        WindowRestore.Outcome.LEFT_ALONE,
        WindowRestore.restore(window),
        "the second pass finds it already restored");
    assertEquals(List.of(), window.calls);
  }
}
