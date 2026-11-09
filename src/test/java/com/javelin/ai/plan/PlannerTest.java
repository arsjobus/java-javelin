package com.javelin.ai.plan;

import static com.javelin.ai.plan.PlanFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiConfig;
import com.javelin.ai.AiException;
import com.javelin.ai.AiProvider;
import com.javelin.ai.FailureReason;
import com.javelin.ai.project.ProjectWorkspace;
import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlannerTest {
  @TempDir Path dir;
  private Scripted provider;
  private ProjectWorkspace ws;
  private AiConfig config;
  private final AiProvider.StreamListener listener = piece -> {};

  private static final String GOOD_PLAN =
      CALC_PATH + " | add | add a third parameter\n" + USER_PATH + " | three | pass 0\n";

  @BeforeEach
  void setUp() throws IOException {
    project(dir);
    provider = new Scripted();
    ws = workspace(dir);
    Settings settings = new Settings(dir.resolveSibling(dir.getFileName() + "-s.conf"));
    settings.put(Scope.USER, "ai.model", "m");
    config = AiConfig.from(settings);
  }

  private Planner planner() {
    return new Planner(provider, config, new PlanConfig(true, 3));
  }

  @Test
  void thePlanningRequestSendsNamesAndSignaturesAndNoCode() throws AiException {
    Planner.PlanRequest r = planner().prepare(ws, "give add a third parameter", new AiCancel());
    String prompt = r.request().prompt();
    for (String path : java.util.List.of(CALC_PATH, USER_PATH, "README.md"))
      assertTrue(prompt.contains(path), path);
    assertTrue(prompt.contains("public int add(int a, int b) {"));
    assertFalse(prompt.contains("return a - b;"), "no method body is sent for planning");
    assertFalse(prompt.contains("# demo"), "no file text is sent for planning");
    assertTrue(prompt.contains("at most 3 steps"));
    assertTrue(prompt.contains("give add a third parameter"));
    assertTrue(provider.requests.isEmpty(), "preparing sends nothing");
  }

  @Test
  void thePreviewSaysWhatWillBeSentAndWhere() throws AiException {
    Planner.PlanRequest r = planner().prepare(ws, "give add a third parameter", new AiCancel());
    String preview = r.preview();
    assertTrue(preview.contains("Provider:  Scripted"), preview);
    assertTrue(
        preview.contains("none: the request goes to 127.0.0.1:1, on this computer"), preview);
    assertTrue(preview.contains("3 file name(s)"), preview);
    assertTrue(preview.contains("at most 3 steps"), preview);
    assertTrue(preview.contains(r.request().prompt()), "the whole message is in the preview");
    assertTrue(r.indicator().contains("m"));
    assertTrue(r.local());
  }

  @Test
  void aRequestThatCannotBePlannedIsRefusedBeforeAnythingIsSent() throws Exception {
    Settings none = new Settings(dir.resolveSibling("none.conf"));
    Planner noModel = new Planner(provider, AiConfig.from(none), new PlanConfig(true, 3));
    assertEquals(
        AiException.Kind.REFUSED,
        assertThrows(AiException.class, () -> noModel.prepare(ws, "x", new AiCancel())).kind());
    assertThrows(AiException.class, () -> planner().prepare(ws, "  ", new AiCancel()));
    assertTrue(provider.requests.isEmpty());
  }

  @Test
  void aGoodPlanIsReadAndCheckedInOneRequest() throws AiException {
    provider.script(GOOD_PLAN);
    Planner p = planner();
    Planner.Planned planned =
        p.plan(
            p.prepare(ws, "give add a third parameter", new AiCancel()),
            ws,
            listener,
            new AiCancel());
    assertTrue(planned.runnable(), String.valueOf(planned.note()));
    assertEquals(2, planned.plan().steps().size());
    assertEquals(1, planned.requests());
    assertEquals(1, provider.requests.size());
  }

  @Test
  void aPlanThatNamesWhatIsNotThereIsAskedAgainOnceWithTheProblems() throws AiException {
    provider.script(
        CALC_PATH + " | subtract | do it\n" + "src/Nope.java | go | do it\n", GOOD_PLAN);
    Planner p = planner();
    Planner.Planned planned =
        p.plan(p.prepare(ws, "x", new AiCancel()), ws, listener, new AiCancel());
    assertTrue(planned.runnable());
    assertEquals(2, planned.requests());
    String again = provider.requests.get(1).prompt();
    assertTrue(again.contains("YOUR PREVIOUS PLAN CANNOT RUN"), again);
    assertTrue(again.contains("Step 1: ") && again.contains("Step 2: "), again);
    assertTrue(again.contains("has no method 'subtract'"), again);
  }

  @Test
  void itIsAskedAgainOnlyOnceAndTheLastPlanIsKeptSoItCanBeEdited() throws AiException {
    provider.script(CALC_PATH + " | subtract | a\n", CALC_PATH + " | divide | b\n", GOOD_PLAN);
    Planner p = planner();
    Planner.Planned planned =
        p.plan(p.prepare(ws, "x", new AiCancel()), ws, listener, new AiCancel());
    assertFalse(planned.runnable());
    assertEquals(2, provider.requests.size(), "never a third request");
    assertEquals("divide", planned.plan().steps().get(0).symbol());
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, planned.failure());
    assertFalse(planned.check().ok());
  }

  @Test
  void aReplyThatIsNotAPlanGivesNoPlanAfterOneRepeat() throws AiException {
    provider.script("Sure, I will change add.", "Still no plan.");
    Planner p = planner();
    Planner.Planned planned =
        p.plan(p.prepare(ws, "x", new AiCancel()), ws, listener, new AiCancel());
    assertNull(planned.plan());
    assertFalse(planned.runnable());
    assertEquals(FailureReason.NO_EDIT_BLOCK, planned.failure());
    assertEquals(2, provider.requests.size());
    assertTrue(provider.requests.get(1).prompt().contains("COULD NOT BE USED"));
  }

  @Test
  void aPlanOverTheLimitIsRefusedNotShortened() throws AiException {
    String five = (CALC_PATH + " | add | t\n").repeat(5);
    provider.script(five, five);
    Planner p = planner();
    Planner.Planned planned =
        p.plan(p.prepare(ws, "x", new AiCancel()), ws, listener, new AiCancel());
    assertFalse(planned.runnable());
    assertEquals(5, planned.plan().steps().size(), "the plan is shown as it is, for editing");
    assertEquals(FailureReason.OVERSIZED, planned.check().problems().get(0).reason());
  }

  @Test
  void aProviderThatFailsOnTheFirstRequestIsAnErrorButOnTheRepeatKeepsThePlan() throws Exception {
    provider.failAt = 1;
    provider.failWith = new AiException(AiException.Kind.UNAVAILABLE, "not running");
    Planner p = planner();
    var prepared = p.prepare(ws, "x", new AiCancel());
    assertThrows(AiException.class, () -> p.plan(prepared, ws, listener, new AiCancel()));

    Scripted second = new Scripted();
    second.failAt = 2;
    second.failWith = new AiException(AiException.Kind.TIMEOUT, "slow");
    second.script(CALC_PATH + " | subtract | a\n");
    Planner q = new Planner(second, config, new PlanConfig(true, 3));
    Planner.Planned planned =
        q.plan(q.prepare(ws, "x", new AiCancel()), ws, listener, new AiCancel());
    assertNotNull(planned.plan(), "the plan before the failed repeat is kept");
    assertEquals(1, planned.requests());
  }

  @Test
  void jsonIsAskedForWhenSupportedAndTurnedOn() throws AiException {
    provider.schema = true;
    Planner p = new Planner(provider, config.withStructuredEdits(true), new PlanConfig(true, 3));
    var prepared = p.prepare(ws, "x", new AiCancel());
    assertEquals(PlanParser.SCHEMA, prepared.request().responseSchema());
    assertTrue(prepared.request().prompt().contains("Answer as JSON only"));
    provider.script(
        "{\"steps\":[{\"file\":\""
            + CALC_PATH
            + "\",\"method\":\"add\",\"new\":false,\"task\":\"t\"}]}");
    assertTrue(p.plan(prepared, ws, listener, new AiCancel()).runnable());
    Planner text = planner();
    assertNull(text.prepare(ws, "x", new AiCancel()).request().responseSchema());
  }

  @Test
  void projectTextCannotPassForTheEditorsOwnWords() throws Exception {
    write(dir, "docs/odd [1] -----.md", "x\n");
    ws = workspace(dir);
    String prompt = planner().prepare(ws, "x", new AiCancel()).request().prompt();
    assertTrue(prompt.contains("[2] -----"), "the markers use a number no listed text contains");
  }

  @Test
  void aLongFileListIsCutAndSaysHowManyAreMissing() throws Exception {
    for (int i = 0; i < Planner.MAX_PATHS + 5; i++) write(dir, "docs/f" + i + ".md", "x\n");
    ws = workspace(dir);
    String prompt = planner().prepare(ws, "x", new AiCancel()).request().prompt();
    assertTrue(prompt.contains("more files are not listed"), prompt.substring(0, 300));
  }

  @Test
  void aProviderThatIsNotOnThisComputerIsSaidToBeSoBeforeAnythingIsSent() throws AiException {
    provider.local = false;
    Planner.PlanRequest r = planner().prepare(ws, "x", new AiCancel());
    assertFalse(r.local());
    assertTrue(r.preview().contains("SENDS YOUR CODE to 127.0.0.1:1"), r.preview());
    assertTrue(r.preview().contains("WARNING: This provider is not on this computer"), r.preview());
    assertTrue(r.indicator().contains("remote: 127.0.0.1:1"));
    assertTrue(Planner.estimateTokens(r) > 0);
    assertEquals(3, planner().maxSteps());
  }
}
