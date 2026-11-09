package com.javelin.ai.plan;

import static com.javelin.ai.plan.PlanFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.javelin.ai.FailureReason;
import com.javelin.ai.project.ProjectWorkspace;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanValidatorTest {
  @TempDir Path dir;
  private ProjectWorkspace ws;

  @BeforeEach
  void setUp() throws IOException {
    project(dir);
    ws = workspace(dir);
  }

  private PlanValidator.Check check(int max, PlanStep... steps) {
    return PlanValidator.check(plan(steps), max, ws);
  }

  private static PlanStep fresh(String file, String symbol) {
    return new PlanStep(file, symbol, true, "do it");
  }

  @Test
  void aPlanOfExistingFilesAndMethodsMayRun() {
    var c = check(3, step(CALC_PATH, "add", "t"), step(USER_PATH, "three", "t"));
    assertTrue(c.ok(), c.describe());
  }

  @Test
  void aStepThatNamesAFileThatIsNotThereIsRefusedUnlessItIsNew() {
    var c = check(3, step("src/main/java/demo/Missing.java", "go", "t"));
    assertEquals(1, c.problems().size());
    assertEquals(FailureReason.WRONG_FILE, c.problems().get(0).reason());
    assertEquals(1, c.problems().get(0).step());
    assertTrue(c.problems().get(0).message().contains("new"), "says how to create it");
    assertTrue(check(3, fresh("src/main/java/demo/Missing.java", "")).ok());
    assertTrue(check(3, fresh("src/main/java/demo/Missing.java", "go")).ok());
  }

  @Test
  void aStepThatNamesAMethodThatIsNotThereIsRefusedUnlessItIsNew() {
    var c = check(3, step(CALC_PATH, "subtract", "t"));
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, c.problems().get(0).reason());
    assertTrue(c.problems().get(0).message().contains("new subtract"));
    assertTrue(check(3, fresh(CALC_PATH, "subtract")).ok());
  }

  @Test
  void aStepWithoutAMethodNamesNothingToChange() {
    var c = check(3, step(CALC_PATH, "", "t"));
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, c.problems().get(0).reason());
    var again = check(3, fresh(CALC_PATH, ""));
    assertEquals(FailureReason.REFUSED, again.problems().get(0).reason());
  }

  @Test
  void aMethodThatIsThereCannotBeAddedAgain() {
    var c = check(3, fresh(CALC_PATH, "add"));
    assertEquals(FailureReason.REFUSED, c.problems().get(0).reason());
    assertTrue(c.problems().get(0).message().contains("Leave out 'new'"));
  }

  @Test
  void whatAnEarlierStepCreatesMayBeUsedByTheStepsAfterIt() {
    var c =
        check(
            3,
            fresh(CALC_PATH, "multiply"),
            step(CALC_PATH, "multiply", "refine it"),
            fresh("src/main/java/demo/Fresh.java", ""),
            step("src/main/java/demo/Fresh.java", "go", "use it"));
    assertEquals(
        0, c.problems().stream().filter(p -> p.step() >= 1 && p.step() <= 2).count(), c.describe());
    // the fourth step is beyond the limit of 3, which is the only thing wrong with this plan
    assertEquals(1, c.problems().size(), c.describe());
    assertEquals(FailureReason.OVERSIZED, c.problems().get(0).reason());
  }

  @Test
  void whatALaterStepCreatesIsNotThereYetForTheStepBefore() {
    var c = check(3, step(CALC_PATH, "multiply", "t"), fresh(CALC_PATH, "multiply"));
    assertEquals(1, c.problems().size());
    assertEquals(1, c.problems().get(0).step());
  }

  @Test
  void theStepLimitIsEnforced() {
    PlanStep s = step(CALC_PATH, "add", "t");
    assertTrue(check(3, s, s, s).ok());
    var c = check(3, s, s, s, s);
    assertFalse(c.ok());
    assertEquals(FailureReason.OVERSIZED, c.problems().get(0).reason());
    assertEquals(0, c.problems().get(0).step());
    assertTrue(c.problems().get(0).message().contains("limit is 3"));
    assertTrue(check(8, s, s, s, s).ok());
  }

  @Test
  void anEmptyPlanOrAStepWithNoTaskIsRefused() {
    assertEquals(FailureReason.NO_EDIT_BLOCK, check(3).problems().get(0).reason());
    var c = check(3, step(CALC_PATH, "add", ""));
    assertEquals(FailureReason.UNPARSEABLE, c.problems().get(0).reason());
  }

  @Test
  void pathsOutsideTheProjectOrForCredentialsAreRefused() {
    for (String path : List.of("../outside.java", "/etc/passwd", ".env", ".git/config")) {
      var c = check(3, fresh(path, "go"));
      assertEquals(1, c.problems().size(), path);
      assertEquals(FailureReason.WRONG_FILE, c.problems().get(0).reason(), path);
    }
  }

  @Test
  void aFileTheScanLeavesOutCannotBePlannedEvenThoughItExists() throws IOException {
    write(dir, "build/Gen.java", "class Gen { void go() {} }\n");
    write(dir, ".gitignore", "secret/\n");
    write(dir, "secret/Hidden.java", "class Hidden { void go() {} }\n");
    ws = workspace(dir);
    var c = check(3, fresh("secret/Hidden.java", "go"));
    assertEquals(FailureReason.WRONG_FILE, c.problems().get(0).reason());
    assertTrue(c.problems().get(0).message().contains("left out"), c.describe());
    assertEquals(
        FailureReason.WRONG_FILE,
        check(3, step("secret/Hidden.java", "go", "t")).problems().get(0).reason());
  }

  @Test
  void aSymbolThatIsNotAMethodNameIsRefused() {
    var c = check(3, step(CALC_PATH, "add(int", "t"));
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, c.problems().get(0).reason());
  }

  @Test
  void problemsAreDescribedWithTheirStepNumber() {
    var c = check(3, step(CALC_PATH, "add", "t"), step(CALC_PATH, "nope", "t"));
    assertTrue(c.describe().startsWith("Step 2: "), c.describe());
  }

  @Test
  void checkingChangesNothing() throws IOException {
    var before = snapshot(dir);
    check(3, step(CALC_PATH, "add", "t"), fresh("src/main/java/demo/New.java", "go"));
    assertEquals(before, snapshot(dir));
  }
}
