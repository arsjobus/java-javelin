package com.javelin.ai.verify;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiConfig;
import com.javelin.ai.AiException;
import com.javelin.ai.AiProvider;
import com.javelin.ai.project.Adjustments;
import com.javelin.ai.project.ContextMode;
import com.javelin.ai.project.ProjectAiConfig;
import com.javelin.ai.project.ProjectAiResult;
import com.javelin.ai.project.ProjectAiService;
import com.javelin.ai.project.ProjectPlan;
import com.javelin.ai.project.ProjectWorkspace;
import com.javelin.ai.project.ScanOptions;
import com.javelin.settings.Settings;
import com.javelin.settings.Settings.Scope;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectAiServiceVerifiedTest {
  @TempDir Path dir;
  private ScriptedProvider provider;
  private ProjectAiService service;
  private ProjectWorkspace ws;
  private ProjectPlan plan;
  private Verifier verifier;
  private final AiProvider.StreamListener listener = new ArrayList<String>()::add;
  private static final String CALC = "src/main/java/demo/Calc.java",
      USER = "src/main/java/demo/User.java";

  private static String edit(String search, String replace) {
    return "<<<<<<< SEARCH\n" + search + "\n=======\n" + replace + "\n>>>>>>> REPLACE\n";
  }

  /** Changes add's signature but forgets its caller in User. */
  private static final String FORGETS_CALLER =
      "=== FILE: "
          + CALC
          + " ===\n"
          + edit(
              "  public int add(int a, int b) {\n    return a - b;",
              "  public int add(int a, int b, int c) {\n    return a + b + c;");

  private static final String UPDATES_CALLER =
      FORGETS_CALLER
          + "=== FILE: "
          + USER
          + " ===\n"
          + edit("    return new Calc().add(1, 2);", "    return new Calc().add(1, 2, 0);")
          + "=== FILE: src/test/java/demo/CalcTest.java ===\n"
          + edit(
              "    assertEquals(5, new Calc().add(2, 3));",
              "    assertEquals(5, new Calc().add(2, 3, 0));");

  @BeforeEach
  void setUp() throws IOException, AiException {
    VerifyFixtures.project(dir);
    provider = new ScriptedProvider();
    Settings settings = new Settings(dir.resolveSibling(dir.getFileName() + "-s.conf"));
    settings.put(Scope.USER, "ai.model", "m");
    ws = new ProjectWorkspace(dir, ScanOptions.defaults());
    service =
        new ProjectAiService(
            provider,
            AiConfig.from(settings),
            new ProjectAiConfig(32768, 4096, ScanOptions.defaults()));
    plan =
        service.prepare(
            ws,
            ContextMode.FULL_PROJECT,
            "add a third parameter to add",
            Adjustments.NONE,
            new AiCancel());
    verifier = new JavacVerifier(dir, VerifyFixtures.withJunit(), VerifyConfig.defaults());
  }

  @Test
  void aChangeThatLeavesACallerBrokenIsRepairedWithTheCallersError() throws AiException {
    provider.script(FORGETS_CALLER, UPDATES_CALLER);
    Verified<ProjectAiResult> v =
        service.runVerified(plan, ws, listener, new AiCancel(), verifier, 2);
    assertEquals(2, provider.requests.size(), v.report());
    assertTrue(
        provider.requests.get(1).prompt().contains(USER + ":5:"), "the broken caller is named");
    assertTrue(
        provider.requests.get(1).prompt().contains("src/test/java/demo/CalcTest.java:9:"),
        "and the broken test");
    assertEquals(3, v.answer().proposal().changes().size());
    assertTrue(v.answer().proposal().changes().stream().noneMatch(c -> c.hasErrors()));
    assertTrue(v.report().contains("Attempt 2: compiles"), v.report());
  }

  @Test
  void whenItNeverCompilesTheFileTheErrorNamesIsFlagged() throws AiException {
    provider.script(FORGETS_CALLER, FORGETS_CALLER, FORGETS_CALLER);
    Verified<ProjectAiResult> v =
        service.runVerified(plan, ws, listener, new AiCancel(), verifier, 2);
    assertEquals(3, provider.requests.size());
    var changes = v.answer().proposal().changes();
    assertEquals(1, changes.size(), "only Calc was proposed");
    assertTrue(changes.get(0).hasErrors(), "unticked by default in the review");
    assertTrue(changes.get(0).findings().get(0).message().contains("does not compile"));
  }

  @Test
  void withoutAVerifierItIsOneRequest() throws AiException {
    provider.script(FORGETS_CALLER, UPDATES_CALLER);
    Verified<ProjectAiResult> v = service.runVerified(plan, ws, listener, new AiCancel(), null, 2);
    assertEquals(1, provider.requests.size());
    assertEquals("", v.report());
  }

  @Test
  void anAnswerWithNoChangeIsNotRepeated() throws AiException {
    provider.script("Nothing needs to change.", UPDATES_CALLER);
    Verified<ProjectAiResult> v =
        service.runVerified(plan, ws, listener, new AiCancel(), verifier, 2);
    assertEquals(1, provider.requests.size());
    assertNull(v.answer().proposal());
  }

  @Test
  void theProjectIsUntouched() throws AiException, IOException {
    var before = VerifyFixtures.snapshot(dir);
    provider.script(FORGETS_CALLER, UPDATES_CALLER);
    service.runVerified(plan, ws, listener, new AiCancel(), verifier, 2);
    assertEquals(before, VerifyFixtures.snapshot(dir));
  }
}
