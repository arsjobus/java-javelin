package com.javelin.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AiPanelTest {
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
  void thinkingAndAnswerAreLabeledSeparatelyAndTextOnlyRepliesStayUnchanged() throws Exception {
    assumeTrue(display, "JavaFX needs a display to construct the AI panel");
    assertEquals(
        "Thinking:\nChecking the code.\n\nAnswer:\nIt adds two values.",
        fx(
            () -> {
              AiPanel panel = new AiPanel();
              panel.begin("Explain Selection", "Ollama on this computer");
              panel.appendThinking("Checking the code.");
              panel.append("It adds two values.");
              return panel.text();
            }));
    assertEquals(
        "Answer only.",
        fx(
            () -> {
              AiPanel panel = new AiPanel();
              panel.begin("Explain Selection", "Ollama on this computer");
              panel.append("Answer only.");
              return panel.text();
            }));
  }
}
