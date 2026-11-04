package com.javelin.ui.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.javelin.build.ProcessManager;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The resizable sidebar. The width rules are plain arithmetic and always run; the rest drives the
 * real controls (the same {@code SplitPane} the window uses, with the real {@link BottomPanel} and
 * its terminal inside) through real mouse events on the dividers, so it needs a display and is
 * skipped on a machine without one (a headless build server).
 */
class WorkAreaTest {
  // ---- the rules ----

  @Test
  void theWidthStaysBetweenTheMinimumAndWhatLeavesRoomForTheEditors() {
    assertEquals(250, WorkArea.clampWidth(250, 1200));
    assertEquals(WorkArea.MIN_SIDEBAR_WIDTH, WorkArea.clampWidth(10, 1200));
    assertEquals(1200 - WorkArea.MIN_EDITOR_WIDTH, WorkArea.clampWidth(5000, 1200));
    assertEquals(
        WorkArea.MIN_SIDEBAR_WIDTH,
        WorkArea.clampWidth(250, 300),
        "in a tiny window the sidebar minimum wins");
    assertEquals(WorkArea.MIN_SIDEBAR_WIDTH, WorkArea.clampWidth(250, 0));
  }

  @Test
  void theDividerPositionIsTheSidebarsShareOfTheWindow() {
    assertEquals(0.25, WorkArea.dividerPosition(250, 1000), 1e-9);
    assertEquals(0.12, WorkArea.dividerPosition(120, 1000), 1e-9);
    assertEquals(0.12, WorkArea.dividerPosition(1, 1000), 1e-9, "clamped");
    assertEquals(0.76, WorkArea.dividerPosition(900, 1000), 1e-9, "clamped");
    assertEquals(
        0.5, WorkArea.dividerPosition(250, 0), "no size yet: nothing meaningful to compute");
  }

  @Test
  void theBottomPanelHeightStaysBetweenItsMinimumAndWhatLeavesRoomForTheEditors() {
    assertEquals(250, BottomPanel.clampHeight(250, 800));
    assertEquals(BottomPanel.MIN_BOTTOM_HEIGHT, BottomPanel.clampHeight(5, 800));
    assertEquals(800 - BottomPanel.MIN_EDITOR_HEIGHT, BottomPanel.clampHeight(5000, 800));
    assertEquals(
        BottomPanel.MIN_BOTTOM_HEIGHT,
        BottomPanel.clampHeight(250, 100),
        "in a tiny window the panel minimum wins");
  }

  @Test
  void theBottomDividerSitsAtTheDefaultShareUntilAHeightIsChosen() {
    assertEquals(0.7, BottomPanel.dividerPosition(0, 800), 1e-9);
    assertEquals(0.7, BottomPanel.dividerPosition(240, 0), 1e-9, "no size yet");
    assertEquals(0.75, BottomPanel.dividerPosition(200, 800), 1e-9);
    assertEquals(
        0.9, BottomPanel.dividerPosition(1, 800), 1e-9, "clamped to the minimum height, 80 of 800");
  }

  // ---- the controls ----

  private static boolean display;
  private BottomPanel bottom;

  @BeforeAll
  static void startToolkit() {
    try {
      Platform.startup(() -> {});
      display = true;
    } catch (IllegalStateException alreadyRunning) {
      display = true;
    } catch (RuntimeException | Error noDisplay) {
      display = false;
    }
  }

  @AfterEach
  void closeTerminals() throws Exception {
    if (bottom != null)
      fx(
          () -> {
            bottom.terminals().closeAll();
            return null;
          });
    bottom = null;
  }

  private static <T> T fx(Callable<T> job) throws Exception {
    FutureTask<T> task = new FutureTask<>(job);
    Platform.runLater(task);
    return task.get(30, TimeUnit.SECONDS);
  }

  /**
   * A window area holding the sidebar and either the real editor-and-terminal panel or a plain
   * stand-in, laid out without a visible window.
   */
  private record Window(WorkArea area, VBox sidebar, Region editors) {
    /** The sidebar's own width. */
    double paneWidth() {
      return sidebar.getWidth();
    }

    /**
     * Where the divider is: the width the sidebar was given, measured to the middle of the divider.
     */
    double sidebarWidth() {
      return sidebar.getWidth() + thickness() / 2;
    }

    void resize(double width, double height) {
      area.node().resize(width, height);
      layout();
    }

    void layout() {
      Region root = area.node();
      root.applyCss();
      root.layout();
      root.layout();
    }

    /** The divider between the sidebar and the editor area, or null while the sidebar is hidden. */
    Node sidebarDivider() {
      return dividerOf(area.node());
    }

    /** The divider that belongs to this split pane (not to one nested inside it). */
    static Node dividerOf(Region split) {
      for (Node node : split.lookupAll(".split-pane-divider"))
        if (node.getParent() == split) return node;
      return null;
    }

    double thickness() {
      return sidebarDivider().getLayoutBounds().getWidth();
    }

    /**
     * Presses the divider, moves the pointer by the given distance in two steps and releases it.
     */
    void drag(Node divider, double dx, double dy) {
      Bounds b = divider.localToScene(divider.getBoundsInLocal());
      double x = b.getCenterX(), y = b.getCenterY();
      fire(divider, MouseEvent.MOUSE_PRESSED, x, y);
      fire(divider, MouseEvent.MOUSE_DRAGGED, x + dx / 2, y + dy / 2);
      fire(divider, MouseEvent.MOUSE_DRAGGED, x + dx, y + dy);
      fire(divider, MouseEvent.MOUSE_RELEASED, x + dx, y + dy);
      layout();
    }

    private static void fire(
        Node target, EventType<MouseEvent> type, double sceneX, double sceneY) {
      Event.fireEvent(
          target,
          new MouseEvent(
              type,
              sceneX,
              sceneY,
              sceneX,
              sceneY,
              MouseButton.PRIMARY,
              1,
              false,
              false,
              false,
              false,
              type != MouseEvent.MOUSE_RELEASED,
              false,
              false,
              true,
              false,
              false,
              null));
    }
  }

  private Window window(boolean withTerminal) throws Exception {
    assumeTrue(display, "needs a display to run JavaFX controls");
    return fx(
        () -> {
          VBox sidebar = new VBox();
          sidebar.setPrefWidth(WorkArea.DEFAULT_SIDEBAR_WIDTH);
          StackPane editors = new StackPane();
          Region content = editors;
          if (withTerminal) {
            bottom = new BottomPanel(editors, new ProcessManager(), () -> null);
            bottom.show(bottom.terminalTab());
            content = bottom.node();
          }
          WorkArea area = new WorkArea(sidebar, content);
          new Scene(
              area.node(),
              1000,
              700); // CSS and skins need a scene; no stage, so nothing appears on screen
          Window window = new Window(area, sidebar, editors);
          window.resize(1000, 700);
          return window;
        });
  }

  @Test
  void theSidebarStartsAtItsDefaultWidth() throws Exception {
    Window w = window(false);
    fx(
        () -> {
          assertEquals(WorkArea.DEFAULT_SIDEBAR_WIDTH, w.sidebarWidth(), 1.0);
          return null;
        });
  }

  @Test
  void draggingTheDividerResizesTheSidebarAndTheEditorsTakeTheRest() throws Exception {
    Window w = window(false);
    fx(
        () -> {
          assertTrue(w.area().sidebarShown());
          w.drag(w.sidebarDivider(), 100, 0);
          assertEquals(350, w.sidebarWidth(), 2.0);
          assertEquals(350, w.area().sidebarWidth(), 2.0, "the width is remembered");
          assertEquals(1000, w.paneWidth() + w.thickness() + w.editors().getWidth(), 2.0);
          return null;
        });
  }

  @Test
  void theSidebarCannotBeDraggedAwayOrOverTheEditors() throws Exception {
    Window w = window(false);
    fx(
        () -> {
          w.drag(w.sidebarDivider(), -2000, 0);
          assertEquals(WorkArea.MIN_SIDEBAR_WIDTH, w.paneWidth(), 1.0);
          w.drag(w.sidebarDivider(), 5000, 0);
          assertTrue(
              w.editors().getWidth() >= WorkArea.MIN_EDITOR_WIDTH - 1.0,
              "the editors keep " + w.editors().getWidth());
          return null;
        });
  }

  @Test
  void theSidebarKeepsItsWidthWhenTheWindowIsResized() throws Exception {
    Window w = window(false);
    fx(
        () -> {
          w.drag(w.sidebarDivider(), 60, 0);
          double chosen = w.sidebarWidth();
          w.resize(1400, 700);
          assertEquals(chosen, w.sidebarWidth(), 1.0, "wider window");
          assertEquals(
              1400,
              w.paneWidth() + w.thickness() + w.editors().getWidth(),
              2.0,
              "the editors take the extra space");
          w.resize(800, 700);
          assertEquals(chosen, w.sidebarWidth(), 1.0, "narrower window");
          return null;
        });
  }

  @Test
  void hidingAndShowingTheSidebarRestoresItsWidth() throws Exception {
    Window w = window(false);
    fx(
        () -> {
          w.area().sidebarWidth(300);
          w.layout();
          assertEquals(300, w.sidebarWidth(), 1.0);
          w.sidebar().setVisible(false);
          w.layout();
          assertFalse(w.area().sidebarShown());
          assertEquals(
              1000,
              w.editors().getWidth(),
              4.0,
              "Focus Mode gives the editors the whole window (less the split pane's own border)");
          w.sidebar().setVisible(true);
          w.layout();
          assertTrue(w.area().sidebarShown());
          assertEquals(300, w.sidebarWidth(), 1.0);
          return null;
        });
  }

  @Test
  void theSidebarAndTheTerminalResizeTogetherAndIndependently() throws Exception {
    Window w = window(true);
    fx(
        () -> {
          Node terminalDivider = Window.dividerOf(bottom.node());
          assertNotNull(terminalDivider, "the terminal divider");
          assertNotNull(w.sidebarDivider(), "the sidebar divider");
          Region terminal = (Region) bottom.terminalTab().getTabPane();
          double terminalHeight = terminal.getHeight();
          assertTrue(terminalHeight > 0, "the terminal is shown");

          w.drag(w.sidebarDivider(), 120, 0);
          assertEquals(370, w.sidebarWidth(), 2.0);
          assertEquals(
              terminalHeight,
              terminal.getHeight(),
              1.0,
              "dragging the sidebar does not move the terminal divider");
          assertEquals(
              w.editors().getWidth(),
              terminal.getWidth(),
              1.0,
              "the terminal is as wide as the editors above it");

          w.drag(terminalDivider, 0, -100);
          assertEquals(
              terminalHeight + 100, terminal.getHeight(), 3.0, "dragging the terminal grows it");
          assertEquals(370, w.sidebarWidth(), 2.0, "and leaves the sidebar alone");

          w.resize(1300, 900);
          assertEquals(370, w.sidebarWidth(), 1.0, "a bigger window keeps the sidebar");
          assertEquals(w.editors().getWidth(), terminal.getWidth(), 1.0);
          assertEquals(
              900,
              w.editors().getHeight()
                  + terminal.getHeight()
                  + terminalDivider.getLayoutBounds().getHeight(),
              4.0,
              "editors and terminal share the height");
          return null;
        });
  }

  @Test
  void aWidthRestoredBeforeTheWindowHasASizeIsKept() throws Exception {
    assumeTrue(display, "needs a display to run JavaFX controls");
    Window w =
        fx(
            () -> {
              VBox sidebar = new VBox();
              WorkArea area = new WorkArea(sidebar, new StackPane());
              area.sidebarWidth(400); // as LayoutMemory does at start, before the first layout
              new Scene(area.node(), 1000, 700);
              Window window = new Window(area, sidebar, new StackPane());
              window.resize(1000, 700);
              return window;
            });
    fx(
        () -> {
          assertEquals(400, w.sidebarWidth(), 2.0);
          return null;
        });
  }

  @Test
  void draggingReportsTheNewSizesAndSettingThemDoesNot() throws Exception {
    Window w = window(true);
    double[] reported = {0, 0};
    fx(
        () -> {
          w.area().onSidebarWidthChanged(width -> reported[0] = width);
          bottom.onHeightChanged(height -> reported[1] = height);
          w.area().sidebarWidth(300);
          bottom.bottomHeight(200);
          w.layout();
          assertEquals(0, reported[0], "setting a width is not a drag");
          assertEquals(0, reported[1], "setting a height is not a drag");
          assertEquals(
              200,
              ((Region) bottom.terminalTab().getTabPane()).getHeight(),
              6.0,
              "the restored height is used");
          w.drag(w.sidebarDivider(), 50, 0);
          assertEquals(350, reported[0], 2.0);
          w.drag(Window.dividerOf(bottom.node()), 0, -40);
          assertEquals(240, reported[1], 8.0);
          return null;
        });
  }

  @Test
  void theBottomPanelKeepsItsHeightWhenTheWindowIsResized() throws Exception {
    Window w = window(true);
    fx(
        () -> {
          Region panel = (Region) bottom.terminalTab().getTabPane();
          w.drag(Window.dividerOf(bottom.node()), 0, -60);
          double chosen = panel.getHeight();
          w.resize(1000, 1000);
          assertEquals(
              chosen,
              panel.getHeight(),
              2.0,
              "a taller window gives the extra space to the editors");
          return null;
        });
  }
}
