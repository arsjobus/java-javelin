package com.javelin.launch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Running {@code Javelin} in several folders must give several independent editor windows. The
 * windows themselves have no automated tests (see HARDENING.md), so these check the wiring that
 * makes that safe, in the same way {@code DialogOwnershipTest} checks the dialog wiring.
 */
class MultiInstanceTest {
  private static final Path ROOT = Path.of("src/main/java/com/javelin");
  private static final Path APP = ROOT.resolve("App.java");
  private static final Path RECOVERY = ROOT.resolve("ui/recovery/RecoveryController.java");
  private static final Path SESSION = ROOT.resolve("ui/project/ProjectSession.java");

  /**
   * The launcher's file name differs in case between checkouts (the case-only rename cannot be
   * applied on macOS), so find it by name.
   */
  private static final Path COMMAND = findLauncher();

  private static Path findLauncher() {
    try (Stream<Path> files = Files.list(Path.of("packaging", "macos"))) {
      return files
          .filter(p -> p.getFileName().toString().equalsIgnoreCase("Javelin"))
          .findFirst()
          .orElse(Path.of("packaging", "macos", "Javelin"));
    } catch (IOException e) {
      return Path.of("packaging", "macos", "Javelin");
    }
  }

  @Test
  void theCommandAlwaysStartsANewInstance() throws IOException {
    String script = Files.readString(COMMAND);
    assertTrue(script.contains("open -n -a"), "open -n is what gives every folder its own window");
    assertFalse(
        script.contains("pgrep"),
        "looking for a running instance is what made folders open in one window");
  }

  @Test
  void theAppClaimsItsRecoverySessionBeforeLookingForLeftovers() throws IOException {
    String app = Files.readString(RECOVERY);
    int method = app.indexOf("public void start()");
    assertTrue(method >= 0);
    int claim = app.indexOf("recovery.startSession()", method);
    int leftovers = app.indexOf("recovery.pending()", method);
    assertTrue(
        claim > method && claim < leftovers,
        "another window's live snapshots would otherwise be offered, and discarded, here");
  }

  @Test
  void everyBufferOfTheAppIsSnapshottedUnderItsOwnSession() throws IOException {
    String app = Files.readString(RECOVERY);
    assertTrue(app.contains("recovery.newSessionId()"));
    assertFalse(
        app.contains("RecoveryStore.newId()"),
        "a plain id carries no session, so other windows would treat it as a leftover");
  }

  @Test
  void theAppDoesNotPollForFoldersMeantForAnotherWindow() throws IOException {
    String app = windowSources();
    assertFalse(
        app.contains("OpenRequests"),
        "with several windows, whichever polled first would take the folder");
    assertFalse(app.contains("requestPoll"));
  }

  @Test
  void theAppClaimsTheProjectBeforeOpeningItAndReleasesItOnExit() throws IOException {
    String session = Files.readString(SESSION);
    int open = session.indexOf("public boolean open(File directory)");
    assertTrue(open >= 0);
    int claim = session.indexOf("claimProject(", open);
    int assign = session.indexOf("bench.projectDirectory(nextProjectDirectory)", open);
    assertTrue(
        claim > open && claim < assign,
        "the folder must be claimed before the window starts showing it");
    assertTrue(
        session.contains("instances.claim("), "the check goes through launch.ProjectInstances");
    int dispose = session.indexOf("public void dispose()");
    assertTrue(
        session.indexOf("projectClaim.close()", dispose) > dispose,
        "a clean exit gives the project back at once");
    String app = Files.readString(APP);
    int stop = app.indexOf("public void stop()");
    assertTrue(
        app.indexOf("session.dispose()", stop) > stop,
        "the window releases the claim when it stops");
  }

  @Test
  void aWindowStartedOnlyForAnAlreadyOpenFolderClosesWhenTheUserDeclines() throws IOException {
    String app = Files.readString(APP);
    int method = app.indexOf("private void openFromCommandLine()");
    int exit = app.indexOf("Platform.exit()", method);
    assertTrue(method >= 0 && exit > method && exit < app.indexOf("public void stop()", method));
  }

  @Test
  void completionBracketAndLineLogicLivesOutsideTheWindowClass() throws IOException {
    String app = windowSources();
    assertTrue(app.contains("Completions.plan(") && app.contains("Completions.replacement("));
    assertTrue(app.contains("Brackets.near(") && app.contains("Brackets.pair("));
    assertTrue(app.contains("LineNavigation.parseLine(") && app.contains("LineNavigation.clamp("));
    assertFalse(app.contains("\"([{)]}\""), "the bracket tables belong to editor.Brackets");
  }

  /** App and every JavaFX class under ui/, which together are "the window". */
  private static String windowSources() throws IOException {
    try (Stream<Path> files = Files.walk(ROOT.resolve("ui"))) {
      String ui =
          files
              .filter(p -> p.toString().endsWith(".java"))
              .sorted()
              .map(
                  p -> {
                    try {
                      return Files.readString(p);
                    } catch (IOException e) {
                      throw new java.io.UncheckedIOException(e);
                    }
                  })
              .collect(Collectors.joining("\n"));
      return Files.readString(APP) + "\n" + ui;
    }
  }
}
