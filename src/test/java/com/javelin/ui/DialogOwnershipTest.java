package com.javelin.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the "window minimizes after cancelling a dialog" fix without needing a display. A dialog
 * that is shown without an owner window makes macOS lose the main window when it closes, so the
 * rule is structural: nothing may show a dialog or native chooser except through {@code Dialogs},
 * which owns it and restores the main window.
 */
class DialogOwnershipTest {
  private static final Path SOURCES = Path.of("src/main/java/com/javelin");
  private static final Path DIALOGS = SOURCES.resolve("ui").resolve("Dialogs.java");

  private static final Pattern SHOW_AND_WAIT = Pattern.compile("\\.showAndWait\\s*\\(");
  private static final Pattern CHOOSER =
      Pattern.compile(
          "\\.(showDialog|showOpenDialog|showOpenMultipleDialog|showSaveDialog)\\s*\\(");

  private static List<Path> sources() throws IOException {
    try (Stream<Path> files = Files.walk(SOURCES)) {
      return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
    }
  }

  @Test
  void theSourceTreeIsWhereTheTestExpectsIt() throws IOException {
    assertTrue(Files.isRegularFile(DIALOGS), "run the tests from the project root");
    assertTrue(sources().size() > 20);
  }

  @Test
  void noDialogIsShownExceptThroughDialogs() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : sources()) {
      if (file.equals(DIALOGS)) continue;
      List<String> lines = Files.readAllLines(file);
      for (int i = 0; i < lines.size(); i++)
        if (SHOW_AND_WAIT.matcher(lines.get(i)).find())
          violations.add(
              file + ":" + (i + 1) + " calls showAndWait() directly; use Dialogs.show(...)");
    }
    assertEquals(List.of(), violations);
  }

  @Test
  void everyNativeChooserIsRunInsideDialogsGuarded() throws IOException {
    List<String> violations = new ArrayList<>();
    for (Path file : sources()) {
      List<String> lines = Files.readAllLines(file);
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        if (CHOOSER.matcher(line).find() && !line.contains("Dialogs.guarded("))
          violations.add(file + ":" + (i + 1) + " opens a chooser outside Dialogs.guarded(...)");
      }
    }
    assertEquals(List.of(), violations);
  }

  @Test
  void dialogsGivesEveryDialogAnOwnerAndRestoresTheMainWindowOnceInFinally() throws IOException {
    String code = Files.readString(DIALOGS);
    assertTrue(
        code.contains("dialog.initOwner(parent)"),
        "dialogs without an owner are the cause of the minimizing");
    assertTrue(
        code.contains("dialog.getOwner() != parent"), "an owner that is already right is kept");
    assertTrue(
        code.contains("showing.get(showing.size() - 1)"),
        "a dialog opened from a dialog belongs to that dialog, not to the main window");
    assertEquals(
        3,
        count(code, "finally"),
        "show(), guarded() and the deferred restore each clean up in a finally... ");
    assertTrue(
        Pattern.compile("finally\\s*\\{[^}]*\\bclosed\\(\\)").matcher(code).find(),
        "...so the count is kept even when the dialog throws");
    assertEquals(
        1,
        count(code, "Platform.runLater"),
        "one deferred restore, not an immediate one plus another");
    assertTrue(code.contains("WindowRestore.restore("));
    assertFalse(
        code.contains("public static void restoreOwner"),
        "nothing may raise the main window on its own: it must go through the nesting count");
  }

  @Test
  void theMainWindowIsRegisteredAsOwnerWhenItIsCreated() throws IOException {
    String app = Files.readString(SOURCES.resolve("App.java"));
    int start = app.indexOf("public void start(Stage stage)");
    assertTrue(start >= 0);
    int setOwner = app.indexOf("Dialogs.setOwner(stage)", start);
    int show = app.indexOf("stage.show()", start);
    assertTrue(
        setOwner > start && setOwner < show,
        "the owner must be set before anything can open a dialog");
  }

  @Test
  void theGuardActuallyCatchesViolations() {
    assertTrue(SHOW_AND_WAIT.matcher("alert.showAndWait();").find());
    assertTrue(SHOW_AND_WAIT.matcher("d.showAndWait().ifPresent(x)").find());
    assertFalse(SHOW_AND_WAIT.matcher("Dialogs.show(alert)").find());
    assertTrue(CHOOSER.matcher("chooser.showOpenDialog(stage)").find());
    assertTrue(CHOOSER.matcher("File f = chooser.showSaveDialog(w);").find());
    assertFalse(CHOOSER.matcher("Dialogs.guarded(() -> pick())").find());
  }

  private static int count(String text, String needle) {
    int n = 0;
    for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) n++;
    return n;
  }
}
