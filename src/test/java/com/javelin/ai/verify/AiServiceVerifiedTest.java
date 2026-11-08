package com.javelin.ai.verify;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.ai.AiAction;
import com.javelin.ai.AiCancel;
import com.javelin.ai.AiConfig;
import com.javelin.ai.AiException;
import com.javelin.ai.AiPlan;
import com.javelin.ai.AiProvider;
import com.javelin.ai.AiResult;
import com.javelin.ai.AiService;
import com.javelin.ai.AiSource;
import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AiServiceVerifiedTest {
  @TempDir Path dir;
  private ScriptedProvider provider;
  private AiService service;
  private AiPlan plan;
  private VerifyTarget target;
  private Verifier verifier;
  private final List<String> streamed = new ArrayList<>();
  private final AiProvider.StreamListener listener = streamed::add;
  private static final String PATH = "src/main/java/demo/Calc.java";

  private static String edit(String search, String replace) {
    return "<<<<<<< SEARCH\n" + search + "\n=======\n" + replace + "\n>>>>>>> REPLACE\n";
  }

  private static final String BROKEN = edit("    return a - b;", "    return a + ;");
  private static final String GOOD = edit("    return a - b;", "    return a + b;");

  @BeforeEach
  void setUp() throws IOException, AiException {
    VerifyFixtures.project(dir);
    provider = new ScriptedProvider();
    Settings settings = new Settings(dir.resolveSibling(dir.getFileName() + "-s.conf"));
    settings.put(Scope.USER, "ai.model", "m");
    service = new AiService(provider, AiConfig.from(settings));
    String text = VerifyFixtures.CALC;
    int from = text.indexOf("  public int add"), to = text.indexOf("  }\n", from) + 4;
    plan =
        service.prepare(
            AiAction.PROPOSE_CHANGE,
            new AiSource(PATH, "Calc.java", "java", text, from, to),
            "make add really add");
    target = new VerifyTarget(PATH, text);
    verifier = new JavacVerifier(dir, VerifyFixtures.withJunit(), VerifyConfig.defaults());
  }

  @Test
  void aBrokenAnswerIsRepairedOnTheSecondRequest() throws AiException {
    provider.script(BROKEN, GOOD);
    Verified<AiResult> v = service.runVerified(plan, listener, new AiCancel(), target, verifier, 2);
    assertEquals(2, v.requests());
    assertEquals(2, provider.requests.size());
    assertFalse(v.answer().hasErrors(), v.answer().findings().toString());
    assertTrue(v.answer().proposal().replacement().contains("return a + b;"));
    String second = provider.requests.get(1).prompt();
    assertTrue(
        second.startsWith(plan.request().prompt()), "the same request, with the problems added");
    assertTrue(second.contains(PATH + ":5:"), "the compiler's file and line go back");
    assertEquals(Verification.Outcome.COMPILE_FAILED, v.attempts().get(0).verification().outcome());
    assertTrue(v.attempts().get(1).verification().ok());
    String report = v.report();
    assertTrue(
        report.contains("Attempt 1: does not compile") && report.contains("Attempt 2: compiles"),
        report);
    assertTrue(report.contains("attempt 2"), "says which attempt is shown");
    assertTrue(
        String.join("", streamed).contains("attempt 2"), "the panel shows a second attempt began");
  }

  @Test
  void theBudgetIsNeverExceededAndTheLastChangeIsShownFlaggedAsBroken() throws AiException {
    provider.script(BROKEN, BROKEN, BROKEN, BROKEN, BROKEN);
    Verified<AiResult> v = service.runVerified(plan, listener, new AiCancel(), target, verifier, 2);
    assertEquals(3, provider.requests.size(), "one request and two repeats");
    assertTrue(v.answer().hasErrors(), "the review will say Apply anyway");
    assertTrue(
        v.answer().findings().stream()
            .anyMatch(f -> f.isError() && f.message().contains("does not compile")),
        v.answer().findings().toString());
    assertNotNull(v.answer().proposal());
  }

  @Test
  void noRetriesMeansOneRequest() throws AiException {
    provider.script(BROKEN, GOOD);
    Verified<AiResult> v = service.runVerified(plan, listener, new AiCancel(), target, verifier, 0);
    assertEquals(1, provider.requests.size());
    assertTrue(v.answer().hasErrors());
  }

  @Test
  void aGoodFirstAnswerCostsOneRequest() throws AiException {
    provider.script(GOOD);
    Verified<AiResult> v = service.runVerified(plan, listener, new AiCancel(), target, verifier, 2);
    assertEquals(1, provider.requests.size());
    assertFalse(v.answer().hasErrors());
  }

  @Test
  void withoutATargetItIsASingleUncheckedRequest() throws AiException {
    provider.script(BROKEN, GOOD);
    Verified<AiResult> v = service.runVerified(plan, listener, new AiCancel(), null, verifier, 2);
    assertEquals(1, provider.requests.size());
    assertEquals("", v.report());
    assertNotNull(v.answer().proposal());
  }

  @Test
  void whenTheCheckCannotLookTheChangeIsNotBlamed() throws AiException {
    provider.script(GOOD, GOOD);
    Verifier cannot = (files, cancel) -> Verification.notChecked("no compiler");
    Verified<AiResult> v = service.runVerified(plan, listener, new AiCancel(), target, cannot, 2);
    assertEquals(1, provider.requests.size());
    assertFalse(v.answer().hasErrors());
    assertTrue(v.report().contains("not checked: no compiler"));
  }

  @Test
  void aRepeatThatTimesOutKeepsTheFirstChange() throws AiException {
    provider.script(BROKEN, GOOD);
    provider.failAt = 2;
    Verified<AiResult> v = service.runVerified(plan, listener, new AiCancel(), target, verifier, 2);
    assertEquals(1, v.requests());
    assertTrue(v.answer().hasErrors());
    assertNotNull(v.answer().proposal(), "the user can still see and reject or apply it");
    assertTrue(String.join("", streamed).contains("failed"));
  }

  @Test
  void aProseAnswerStopsTheLoop() throws AiException {
    provider.script("I am not sure what you mean.", GOOD);
    Verified<AiResult> v = service.runVerified(plan, listener, new AiCancel(), target, verifier, 2);
    assertEquals(1, provider.requests.size());
    assertNull(v.answer().proposal());
  }

  @Test
  void generatedTestsAndExplanationsAreNeverRepeated() throws AiException, IOException {
    AiPlan explain =
        service.prepare(
            AiAction.EXPLAIN,
            new AiSource(PATH, "Calc.java", "java", VerifyFixtures.CALC, 0, 20),
            null);
    provider.script("It adds.", "unused");
    Verified<AiResult> v =
        service.runVerified(explain, listener, new AiCancel(), target, verifier, 2);
    assertEquals(1, provider.requests.size());
    assertEquals("It adds.", v.answer().text());
  }

  @Test
  void theProjectIsUntouchedByTheWholeLoop() throws AiException, IOException {
    var before = VerifyFixtures.snapshot(dir);
    provider.script(BROKEN, GOOD);
    service.runVerified(plan, listener, new AiCancel(), target, verifier, 2);
    assertEquals(before, VerifyFixtures.snapshot(dir));
  }
}
