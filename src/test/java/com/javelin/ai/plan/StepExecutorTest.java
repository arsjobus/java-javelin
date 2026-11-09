package com.javelin.ai.plan;

import static com.javelin.ai.plan.PlanFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiConfig;
import com.javelin.ai.AiException;
import com.javelin.ai.FailureReason;
import com.javelin.ai.project.FileChange;
import com.javelin.ai.project.ProjectWorkspace;
import com.javelin.ai.verify.JavacVerifier;
import com.javelin.ai.verify.ProjectClasspath;
import com.javelin.ai.verify.Verification;
import com.javelin.ai.verify.Verifier;
import com.javelin.ai.verify.VerifyConfig;
import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StepExecutorTest {
  @TempDir Path dir;
  private Scripted provider;
  private ProjectWorkspace ws;
  private AiConfig config;
  private Verifier javac;
  private final List<String> events = new ArrayList<>();
  private final StepExecutor.Listener listener =
      new StepExecutor.Listener() {
        @Override
        public void onText(String piece) {}

        @Override
        public void stepStarted(int number, int total, PlanStep step) {
          events.add("start " + number + "/" + total);
        }

        @Override
        public void stepFinished(StepResult result) {
          events.add("end " + result.number() + " " + result.status());
        }
      };

  @BeforeEach
  void setUp() throws IOException {
    project(dir);
    provider = new Scripted();
    ws = workspace(dir);
    Settings settings = new Settings(dir.resolveSibling(dir.getFileName() + "-s.conf"));
    settings.put(Scope.USER, "ai.model", "m");
    config = AiConfig.from(settings);
    javac =
        new JavacVerifier(
            dir, new ProjectClasspath.Resolved(List.of(), true, ""), VerifyConfig.defaults());
  }

  private StepExecutor executor(int maxSteps) {
    return new StepExecutor(provider, config, new PlanConfig(true, maxSteps));
  }

  private PlanRun run(TaskPlan plan, Verifier verifier, int retries) throws AiException {
    return executor(3).run(plan, ws, listener, new AiCancel(), verifier, retries);
  }

  private static final TaskPlan THREE_PARAMETERS =
      plan(
          step(CALC_PATH, "add", "add a third parameter c and return a + b + c"),
          step(USER_PATH, "three", "pass 0 as the third argument"));

  @Test
  void eachStepIsItsOwnRequestAboutItsOwnMethod() throws AiException {
    provider.script(ADD_WITH_THREE, USER_CALLS_THREE);
    PlanRun r = run(THREE_PARAMETERS, javac, 0);
    assertTrue(r.complete(), r.report());
    assertEquals(2, provider.requests.size());
    String first = provider.requests.get(0).prompt();
    String second = provider.requests.get(1).prompt();
    assertTrue(first.contains("return a - b;"));
    assertFalse(first.contains("three()"), "the other step's method is not in this request");
    assertTrue(second.contains("return new Calc().add(1, 2);"));
    assertFalse(second.contains("return a - b;"), "Calc's code is not sent for User's step");
    assertTrue(
        second.contains("public int add(int a, int b, int c) {"),
        "the second step sees the signature the first step made");
    assertEquals(List.of("start 1/2", "end 1 DONE", "start 2/2", "end 2 DONE"), events);
  }

  @Test
  void theStepsMakeOneProposalForTheWholeChangeAndNothingIsWritten() throws Exception {
    var before = snapshot(dir);
    provider.script(ADD_WITH_THREE, USER_CALLS_THREE);
    PlanRun r = run(THREE_PARAMETERS, javac, 0);
    assertEquals(
        List.of(CALC_PATH, USER_PATH),
        r.proposal().changes().stream().map(FileChange::path).toList());
    assertTrue(r.proposal().changes().get(0).replacement().contains("int c"));
    assertTrue(r.proposal().changes().get(1).replacement().contains("add(1, 2, 0)"));
    assertEquals(before, snapshot(dir), "the project is untouched");
    assertNotNull(r.asResult().proposal());
    assertTrue(r.report().contains("Plan: 2 of 2 steps done."), r.report());
  }

  @Test
  void aBreakThatALaterStepIsPlannedToMendDoesNotFailTheEarlierStep() throws AiException {
    provider.script(ADD_WITH_THREE, USER_CALLS_THREE);
    PlanRun r = run(THREE_PARAMETERS, javac, 0);
    StepResult first = r.results().get(0);
    assertTrue(first.done(), r.report());
    assertTrue(first.summary().contains("later steps change"), first.summary());
    assertTrue(r.results().get(1).summary().startsWith("compiles"), r.report());
  }

  @Test
  void aBreakNoLaterStepMendsFailsTheStepAndStopsTheRun() throws AiException {
    TaskPlan plan =
        plan(step(CALC_PATH, "add", "add a third parameter"), step(CALC_PATH, "twice", "tidy"));
    provider.script(ADD_WITH_THREE);
    PlanRun r = run(plan, javac, 0);
    assertEquals(StepResult.Status.FAILED, r.results().get(0).status(), r.report());
    assertEquals(FailureReason.COMPILE_ERROR, r.results().get(0).failure());
    assertEquals(StepResult.Status.NOT_RUN, r.results().get(1).status());
    assertEquals(1, provider.requests.size());
    assertNull(r.proposal().changes().isEmpty() ? null : r.proposal());
    assertNull(r.asResult().proposal());
    assertEquals(FailureReason.COMPILE_ERROR, r.asResult().failure());
  }

  @Test
  void aFailedCheckIsRepairedWithinTheStepsRetries() throws AiException {
    String fixed =
        edit(
            CALC_PATH,
            "  public int add(int a, int b) {\n    return a - b;",
            "  public int add(int a, int b) {\n    return a + b;");
    String broken =
        edit(
            CALC_PATH,
            "  public int add(int a, int b) {\n    return a - b;",
            "  public int add(int a, int b) {\n    return a +;");
    TaskPlan plan = plan(step(CALC_PATH, "add", "make add add"));
    provider.script(broken, fixed);
    PlanRun r = run(plan, javac, 2);
    assertTrue(r.complete(), r.report());
    assertEquals(2, r.results().get(0).requests());
    assertTrue(provider.requests.get(1).prompt().contains("Calc.java:5"), "the compiler's line");
    assertTrue(r.report().contains("after 2 requests"), r.report());
  }

  @Test
  void aStepThatCannotBeRepairedStopsTheRunButKeepsTheStepsBeforeIt() throws AiException {
    String fixed =
        edit(
            CALC_PATH,
            "  public int add(int a, int b) {\n    return a - b;",
            "  public int add(int a, int b) {\n    return a + b;");
    String broken =
        edit(USER_PATH, "    return new Calc().add(1, 2);", "    return new Calc().add(1, 2;");
    TaskPlan plan =
        plan(
            step(CALC_PATH, "add", "make add add"),
            step(USER_PATH, "three", "break it"),
            step(CALC_PATH, "twice", "never reached"));
    provider.script(fixed, broken, broken, broken);
    PlanRun r = run(plan, javac, 2);
    assertEquals(
        List.of(StepResult.Status.DONE, StepResult.Status.FAILED, StepResult.Status.NOT_RUN),
        r.results().stream().map(StepResult::status).toList(),
        r.report());
    assertEquals(4, provider.requests.size(), "1 + (1 + 2 retries), none for step 3");
    assertEquals(1, r.proposal().changes().size());
    assertEquals(CALC_PATH, r.proposal().changes().get(0).path());
    assertTrue(r.report().contains("The run stopped at step 2"), r.report());
    assertNotNull(r.asResult().proposal(), "what completed is still reviewable");
  }

  @Test
  void twoStepsOnOneFileMakeOneChangeFromTheDiskTextToTheLastText() throws Exception {
    String twice =
        edit(
            CALC_PATH,
            "  public int twice(int a) {\n    return a + a;",
            "  public int twice(int a) {\n    return a * 2;");
    String add =
        edit(
            CALC_PATH,
            "  public int add(int a, int b) {\n    return a - b;",
            "  public int add(int a, int b) {\n    return a + b;");
    TaskPlan plan = plan(step(CALC_PATH, "add", "fix add"), step(CALC_PATH, "twice", "double"));
    provider.script(add, twice);
    PlanRun r = run(plan, javac, 0);
    assertTrue(r.complete(), r.report());
    assertEquals(1, r.proposal().changes().size());
    FileChange c = r.proposal().changes().get(0);
    assertEquals(CALC, c.original());
    assertTrue(c.replacement().contains("return a + b;") && c.replacement().contains("a * 2"));
    assertTrue(
        provider.requests.get(1).prompt().contains("public int add(int a, int b) {"),
        "step two is shown the file as step one left it");
  }

  @Test
  void aStepThatTouchesAnotherFileHasThatChangeIgnored() throws AiException {
    TaskPlan plan = plan(step(CALC_PATH, "add", "make add add"));
    String both =
        edit(
                CALC_PATH,
                "  public int add(int a, int b) {\n    return a - b;",
                "  public int add(int a, int b) {\n    return a + b;")
            + edit(USER_PATH, "    return new Calc().add(1, 2);", "    return 3;");
    provider.script(both);
    PlanRun r = run(plan, javac, 0);
    assertEquals(1, r.proposal().changes().size());
    assertEquals(CALC_PATH, r.proposal().changes().get(0).path());
    assertEquals(1, r.proposal().problems().size(), r.proposal().problems().toString());
    assertTrue(r.proposal().problems().get(0).contains("User.java"));
  }

  @Test
  void aNewFileAndAMethodThatUsesItCanBeOneRun() throws Exception {
    String fresh = "src/main/java/demo/Fresh.java";
    TaskPlan plan =
        plan(
            new PlanStep(fresh, "", true, "create class Fresh with int go() returning 7"),
            step(USER_PATH, "three", "return new Fresh().go() instead"));
    provider.script(
        "=== NEW FILE: "
            + fresh
            + " ===\n"
            + "```java\n"
            + "package demo;\n\n"
            + "public class Fresh {\n"
            + "  public int go() {\n"
            + "    return 7;\n"
            + "  }\n"
            + "}\n"
            + "```\n",
        edit(USER_PATH, "    return new Calc().add(1, 2);", "    return new Fresh().go();"));
    PlanRun r = run(plan, javac, 0);
    assertTrue(r.complete(), r.report());
    assertEquals(FileChange.Kind.CREATE, r.proposal().changes().get(0).kind());
    assertTrue(provider.requests.get(1).prompt().contains("public int go() {"));
  }

  @Test
  void aReplyInWordsIsAFailedStepNotAnException() throws AiException {
    provider.script("I think add is fine as it is.");
    PlanRun r = run(THREE_PARAMETERS, javac, 2);
    assertEquals(FailureReason.NO_EDIT_BLOCK, r.results().get(0).failure());
    assertEquals(1, provider.requests.size(), "no repeat for an answer with no change");
    assertEquals(StepResult.Status.NOT_RUN, r.results().get(1).status());
    assertNull(r.asResult().proposal());
  }

  @Test
  void aProviderThatTimesOutLaterIsAFailedStepButBeforeAnyStepItIsAnError() throws AiException {
    provider.failAt = 2;
    provider.failWith = new AiException(AiException.Kind.TIMEOUT, "the model did not answer");
    provider.script(ADD_WITH_THREE);
    PlanRun r = run(THREE_PARAMETERS, javac, 0);
    assertTrue(r.results().get(0).done());
    assertEquals(FailureReason.TIMEOUT, r.results().get(1).failure());
    assertEquals(1, r.proposal().changes().size());

    Scripted down = new Scripted();
    down.failAt = 1;
    down.failWith = new AiException(AiException.Kind.UNAVAILABLE, "Ollama is not running");
    var ex =
        assertThrows(
            AiException.class,
            () ->
                new StepExecutor(down, config, new PlanConfig(true, 3))
                    .run(THREE_PARAMETERS, ws, listener, new AiCancel(), javac, 0));
    assertEquals(AiException.Kind.UNAVAILABLE, ex.kind());
  }

  @Test
  void theStepLimitHoldsAtRunTimeToo() throws Exception {
    PlanStep s = step(CALC_PATH, "add", "t");
    TaskPlan four = plan(s, s, s, s);
    AiException ex =
        assertThrows(
            AiException.class, () -> executor(3).run(four, ws, listener, new AiCancel(), null, 0));
    assertEquals(FailureReason.OVERSIZED, ex.reason());
    assertEquals(AiException.Kind.REFUSED, ex.kind());
    assertTrue(provider.requests.isEmpty(), "not one request is sent for a plan over the limit");
    provider.script(ADD_WITH_THREE, ADD_WITH_THREE, ADD_WITH_THREE, ADD_WITH_THREE);
    assertDoesNotThrow(() -> executor(4).run(four, ws, listener, new AiCancel(), null, 0));
  }

  @Test
  void aPlanThatDoesNotValidateDoesNotRun() {
    TaskPlan bad = plan(step(CALC_PATH, "missing", "t"));
    AiException ex = assertThrows(AiException.class, () -> run(bad, javac, 0));
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, ex.reason());
    assertTrue(ex.getMessage().contains("Step 1"), ex.getMessage());
    assertTrue(provider.requests.isEmpty());
  }

  @Test
  void aCancelledRunStopsAtOnce() {
    AiCancel cancel = new AiCancel();
    cancel.cancel();
    AiException ex =
        assertThrows(
            AiException.class,
            () -> executor(3).run(THREE_PARAMETERS, ws, listener, cancel, javac, 0));
    assertEquals(AiException.Kind.CANCELLED, ex.kind());
    assertTrue(provider.requests.isEmpty());
  }

  @Test
  void withoutAVerifierEachStepIsOneRequestAndSaysNotChecked() throws AiException {
    provider.script(ADD_WITH_THREE, USER_CALLS_THREE);
    PlanRun r = run(THREE_PARAMETERS, null, 2);
    assertTrue(r.complete());
    assertEquals(2, provider.requests.size());
    assertTrue(r.results().get(0).summary().contains("not checked"));
  }

  @Test
  void theVerifierSeesEveryStepSoFarNotJustTheLastOne() throws AiException {
    List<Map<String, String>> seen = new ArrayList<>();
    Verifier spy =
        (files, cancel) -> {
          seen.add(Map.copyOf(files));
          return Verification.passed(0, false, 1, "");
        };
    provider.script(ADD_WITH_THREE, USER_CALLS_THREE);
    run(THREE_PARAMETERS, spy, 0);
    assertEquals(1, seen.get(0).size());
    assertEquals(2, seen.get(1).size(), "step two is checked together with step one's change");
  }

  @Test
  void leavingLaterStepsKeepsTheErrorsOfTheStepItselfAndPassesOnlyWhenNoneAreLeft() {
    var in = new com.javelin.ai.verify.VerifyProblem("a/Later.java", 3, "cannot find symbol");
    var mine = new com.javelin.ai.verify.VerifyProblem("a/Mine.java", 4, "';' expected");
    Verification both =
        new Verification(Verification.Outcome.COMPILE_FAILED, List.of(in, mine), 0, false, 1, "");
    Verification partly =
        StepExecutor.leavingLaterSteps(both, java.util.Set.of("a/Later.java"), "a/Mine.java", 4, 6);
    assertEquals(Verification.Outcome.COMPILE_FAILED, partly.outcome());
    assertEquals(List.of(mine), partly.problems());
    Verification onlyLater =
        new Verification(Verification.Outcome.COMPILE_FAILED, List.of(in), 0, false, 1, "");
    assertTrue(
        StepExecutor.leavingLaterSteps(
                onlyLater, java.util.Set.of("a/Later.java"), "a/Mine.java", 4, 6)
            .ok());
    assertSame(both, StepExecutor.leavingLaterSteps(both, java.util.Set.of(), "a/Mine.java", 4, 6));
  }

  @Test
  void inTheStepsOwnFileOnlyErrorsOutsideWhatItChangedAreLeftForALaterStep() {
    var inside = new com.javelin.ai.verify.VerifyProblem("a/F.java", 5, "';' expected");
    var outside = new com.javelin.ai.verify.VerifyProblem("a/F.java", 20, "cannot be applied");
    var noLine = new com.javelin.ai.verify.VerifyProblem("a/F.java", 0, "something");
    Verification v =
        new Verification(
            Verification.Outcome.COMPILE_FAILED, List.of(inside, outside, noLine), 0, false, 1, "");
    Verification r =
        StepExecutor.leavingLaterSteps(v, java.util.Set.of("a/F.java"), "a/F.java", 4, 8);
    assertEquals(List.of(inside, noLine), r.problems());
    assertTrue(r.note().contains("1 problem(s) in files that later steps change"));
  }

  @Test
  void theChangedLinesAreWhatDiffersBetweenTheEndsOfTheTwoTexts() {
    assertArrayEquals(new int[] {2, 3}, StepExecutor.changedLines("a\nb\nc\nd\n", "a\nX\nY\nd\n"));
    assertArrayEquals(new int[] {1, 2}, StepExecutor.changedLines("", "a\nb\n"));
    assertArrayEquals(new int[] {3, 3}, StepExecutor.changedLines("a\nb\nc\n", "a\nb\n"));
  }

  @Test
  void twoStepsOnOneFileMayMendEachOthersBreakWhenTheFirstOnlyBreaksOtherMethods()
      throws Exception {
    write(dir, CALC_PATH, CALC.replace("return a + a;", "return add(a, a);"));
    ws = workspace(dir);
    String twiceFix =
        edit(
            CALC_PATH,
            "  public int twice(int a) {\n    return add(a, a);",
            "  public int twice(int a) {\n    return add(a, a, 0);");
    TaskPlan plan =
        plan(
            step(CALC_PATH, "add", "third parameter"),
            step(CALC_PATH, "twice", "pass 0"),
            step(USER_PATH, "three", "pass 0"));
    provider.script(ADD_WITH_THREE, twiceFix, USER_CALLS_THREE);
    PlanRun r = run(plan, javac, 0);
    assertTrue(r.complete(), r.report());
    assertTrue(r.results().get(0).summary().contains("later steps change"), r.report());
    assertTrue(r.results().get(1).summary().contains("later steps change"), r.report());
    assertTrue(r.results().get(2).summary().startsWith("compiles"), r.report());
  }

  @Test
  void thePreviewListsWhatEveryStepSendsAndTheFirstMessageInFull() throws AiException {
    String text = executor(3).preview(THREE_PARAMETERS, ws);
    assertTrue(text.contains("1. " + THREE_PARAMETERS.steps().get(0).line()), text);
    assertTrue(text.contains("sends: the method add of " + CALC_PATH), text);
    assertTrue(text.contains("sends: the method three of " + USER_PATH), text);
    assertTrue(text.contains("return a - b;"), "the first message is shown in full");
    assertFalse(text.contains("return new Calc().add(1, 2);\n  }\n}"), "later messages are not");
    assertTrue(provider.requests.isEmpty(), "previewing sends nothing");
  }

  @Test
  void thePreviewOfAStepThatUsesWhatAnEarlierStepMakesSaysSo() throws AiException {
    String fresh = "src/main/java/demo/Fresh.java";
    TaskPlan plan =
        plan(new PlanStep(fresh, "", true, "create Fresh"), step(fresh, "go", "use it"));
    String text = executor(3).preview(plan, ws);
    assertTrue(text.contains("what an earlier step makes"), text);
  }

  @Test
  void jsonEditsAreUsedWhenAskedForAndSupported() throws AiException {
    provider.schema = true;
    AiConfig structured = config.withStructuredEdits(true);
    provider.script(
        "{\"explanation\":\"x\",\"edits\":[{\"file\":\""
            + CALC_PATH
            + "\",\"anchor\":\"\",\"old\":\"    return a - b;\",\"new\":\"    return a + b;\"}]}");
    PlanRun r =
        new StepExecutor(provider, structured, new PlanConfig(true, 3))
            .run(plan(step(CALC_PATH, "add", "fix")), ws, listener, new AiCancel(), javac, 0);
    assertTrue(r.complete(), r.report());
    assertNotNull(provider.requests.get(0).responseSchema());
  }

  @Test
  void aListenerThatOnlyReadsTextStillWorks() throws AiException {
    provider.script(ADD_WITH_THREE, USER_CALLS_THREE);
    StringBuilder text = new StringBuilder();
    StepExecutor.Listener textOnly =
        new StepExecutor.Listener() {
          @Override
          public void onText(String piece) {
            text.append(piece);
          }
        };
    PlanRun r = executor(3).run(THREE_PARAMETERS, ws, textOnly, new AiCancel(), javac, 0);
    assertTrue(r.complete(), r.report());
    assertTrue(text.toString().contains("=== FILE: " + CALC_PATH), "the model's text is passed on");
  }
}
