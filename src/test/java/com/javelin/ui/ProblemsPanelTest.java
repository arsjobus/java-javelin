package com.javelin.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.javelin.build.Problem;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.StackPane;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProblemsPanelTest {
  private static boolean display;

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

  private static <T> T fx(Callable<T> job) throws Exception {
    FutureTask<T> task = new FutureTask<>(job);
    Platform.runLater(task);
    return task.get(30, TimeUnit.SECONDS);
  }

  @Test
  void aWarningAndAnErrorCanEachBeCopied() throws Exception {
    assumeTrue(display, "JavaFX needs a display for the system clipboard");
    Problem warning = new Problem(Problem.Severity.WARNING, "src/A.java", 3, 2, "warning");
    Problem error = new Problem(Problem.Severity.ERROR, "src/B.java", 8, 1, "error");
    ProblemsPanel panel =
        fx(
            () -> {
              ProblemsPanel problems = new ProblemsPanel();
              problems.replace("language", List.of(warning, error));
              StackPane root = new StackPane(problems);
              new Scene(root, 600, 300);
              root.resize(600, 300);
              root.applyCss();
              root.layout();
              return problems;
            });

    for (Problem problem : List.of(warning, error)) {
      fx(
          () -> {
            ListCell<?> cell =
                panel.lookupAll(".list-cell").stream()
                    .filter(
                        node ->
                            node instanceof ListCell<?> candidate
                                && problem.equals(candidate.getItem()))
                    .map(node -> (ListCell<?>) node)
                    .findFirst()
                    .orElseThrow();
            ContextMenu menu = cell.getContextMenu();
            assertNotNull(menu);
            MenuItem copy = menu.getItems().get(0);
            assertEquals("Copy", copy.getText());
            copy.fire();
            assertEquals(
                problem.toString(), javafx.scene.input.Clipboard.getSystemClipboard().getString());
            return null;
          });
    }
  }
}
