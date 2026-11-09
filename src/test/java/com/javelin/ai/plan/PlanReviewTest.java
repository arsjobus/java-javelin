package com.javelin.ai.plan;

import static com.javelin.ai.plan.PlanFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.javelin.ai.AiConfig;
import com.javelin.ai.project.ProjectWorkspace;
import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlanReviewTest {
  @TempDir Path dir;
  private ProjectWorkspace ws;
  private StepExecutor executor;

  @BeforeEach
  void setUp() throws IOException {
    project(dir);
    ws = workspace(dir);
    Settings settings = new Settings(dir.resolveSibling(dir.getFileName() + "-s.conf"));
    settings.put(Scope.USER, "ai.model", "m");
    executor = new StepExecutor(new Scripted(), AiConfig.from(settings), new PlanConfig(true, 3));
  }

  private PlanReview.Reviewed review(String text) {
    return PlanReview.review("req", text, ws, executor);
  }

  @Test
  void aGoodPlanIsOkAndShowsWhatEachStepSends() {
    var r =
        review(
            "1. "
                + CALC_PATH
                + " | add | third parameter\n2. "
                + USER_PATH
                + " | three | pass 0\n");
    assertTrue(r.ok(), r.shown());
    assertEquals(2, r.plan().steps().size());
    assertTrue(r.shown().contains("sends: the method add of " + CALC_PATH), r.shown());
  }

  @Test
  void textThatIsNotAPlanIsNotOkAndSaysWhichLine() {
    var r = review("this is not a step\n");
    assertFalse(r.ok());
    assertNull(r.plan());
    assertTrue(r.shown().contains("Line 1"), r.shown());
  }

  @Test
  void aPlanWithAProblemIsNotOkAndListsTheProblemsByStep() {
    var r = review(CALC_PATH + " | add | ok\n" + CALC_PATH + " | missing | no\n");
    assertFalse(r.ok());
    assertNotNull(r.plan(), "it was read, so the user can see and edit it");
    assertTrue(r.shown().contains("Step 2: "), r.shown());
  }

  @Test
  void aPlanOverTheLimitIsNotOkAndTheEditedShorterOneIs() {
    String four = (CALC_PATH + " | add | t\n").repeat(4);
    assertFalse(review(four).ok());
    assertTrue(review((CALC_PATH + " | add | t\n").repeat(3)).ok());
    assertEquals(3, executor.maxSteps());
  }
}
