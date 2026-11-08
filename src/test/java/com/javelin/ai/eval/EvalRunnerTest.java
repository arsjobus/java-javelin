package com.javelin.ai.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.ai.AiException;
import com.javelin.ai.AiReply;
import com.javelin.ai.AiUsage;
import com.javelin.ai.FailureReason;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The runner against scripted providers: one that always knows the right change (every task must
 * pass, through the real pipeline), and one for each way a model can fail (each must be counted
 * under its own reason).
 */
class EvalRunnerTest {
  @TempDir Path scratch;
  private static List<EvalTask> tasks;

  @BeforeAll
  static void load() throws IOException {
    tasks = EvalCorpus.load();
  }

  static List<String> ids() throws IOException {
    return EvalCorpus.load().stream().map(EvalTask::id).toList();
  }

  private EvalRunner runner(ScriptedProvider provider) {
    return new EvalRunner(provider, EvalProfile.small("scripted-model"), scratch);
  }

  private static EvalTask task(String id) {
    return tasks.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("ids")
  void aModelThatKnowsTheAnswerPassesEveryTask(String id) {
    TaskResult r = runner(ScriptedProvider.oracle(tasks)).run(task(id), 42);
    assertTrue(r.passed(), id + ": " + r.failure() + " " + r.detail());
    assertNull(r.failure());
    assertTrue(r.applied() && r.compiled());
    assertEquals(1, r.attempts());
    assertEquals(500, r.promptTokens());
    assertEquals(50, r.completionTokens());
    assertTrue(r.changedLines() > 0);
  }

  @Test
  void theRunAsksForTemperatureZeroAndTheSeedOfTheRun() {
    ScriptedProvider provider = ScriptedProvider.oracle(tasks);
    runner(provider).run(task("fix-bug-off-by-one"), 45);
    assertEquals(1, provider.requests.size());
    assertEquals(0.0, provider.requests.get(0).sampling().temperature());
    assertEquals(45L, provider.requests.get(0).sampling().seed());
  }

  @Test
  void eachRunOfTheCorpusUsesItsOwnSeed() {
    ScriptedProvider provider = ScriptedProvider.oracle(tasks);
    List<EvalTask> two = List.of(task("fix-bug-off-by-one"), task("fix-bug-null-name"));
    List<String> lines = new ArrayList<>();
    List<TaskResult> second = runner(provider).runAll(two, 42, 1, lines::add);
    assertEquals(2, second.size());
    assertEquals(
        List.of(43L, 43L), provider.requests.stream().map(r -> r.sampling().seed()).toList());
    assertEquals(2, lines.size());
    assertTrue(lines.get(0).startsWith("run 2 fix-bug-off-by-one: pass"), lines.get(0));
  }

  private TaskResult answered(String id, String reply) {
    return runner(ScriptedProvider.replying(reply)).run(task(id), 1);
  }

  @Test
  void aReplyWithNoCodeIsNoEditBlock() {
    TaskResult r = answered("fix-bug-off-by-one", "I would change the loop bound.");
    assertFalse(r.passed());
    assertFalse(r.applied());
    assertEquals(FailureReason.NO_EDIT_BLOCK, r.failure());
  }

  @Test
  void aReplyThatStopsInsideItsCodeIsTruncated() {
    TaskResult r = answered("fix-bug-off-by-one", "```java\npublic static int sumUpTo(int n) {\n");
    assertEquals(FailureReason.TRUNCATED, r.failure());
  }

  @Test
  void aReplyTheModelCutOffIsTruncated() {
    ScriptedProvider provider =
        new ScriptedProvider(q -> new AiReply("```java\nint x;\n```", true));
    TaskResult r = runner(provider).run(task("fix-bug-off-by-one"), 1);
    assertEquals(FailureReason.TRUNCATED, r.failure());
    assertEquals(
        -1, r.promptTokens(), "a provider that does not report tokens leaves them unknown");
  }

  @Test
  void editBlocksForCodeThatIsNotThereAreAnchorNotFound() {
    TaskResult r =
        answered(
            "fix-bug-off-by-one",
            "<<<<<<< SEARCH\nthis line does not exist\n=======\nx\n>>>>>>> REPLACE\n");
    assertEquals(FailureReason.ANCHOR_NOT_FOUND, r.failure());
    assertFalse(r.applied());
  }

  @Test
  void anEditBlockWithNothingToSearchForIsUnparseable() {
    TaskResult r =
        answered("fix-bug-off-by-one", "<<<<<<< SEARCH\n\n=======\nx\n>>>>>>> REPLACE\n");
    assertEquals(FailureReason.UNPARSEABLE, r.failure());
  }

  @Test
  void aProjectReplyNamingAFileOutsideTheProjectIsWrongFile() {
    TaskResult r =
        answered(
            "rename-incr", "=== FILE: ../elsewhere/Nope.java ===\n```java\nclass Nope {}\n```\n");
    assertEquals(FailureReason.WRONG_FILE, r.failure());
  }

  @Test
  void aProjectReplyWithNoFilesIsNoEditBlock() {
    TaskResult r = answered("rename-incr", "Rename it yourself.");
    assertEquals(FailureReason.NO_EDIT_BLOCK, r.failure());
  }

  @Test
  void codeThatDoesNotCompileIsCompileError() {
    TaskResult r =
        answered(
            "fix-bug-off-by-one",
            "```java\n  public static int sumUpTo(int n) {\n    return undefinedName;\n  }\n```\n");
    assertTrue(r.applied());
    assertFalse(r.compiled());
    assertEquals(FailureReason.COMPILE_ERROR, r.failure());
    assertTrue(r.detail().contains("undefinedName") || r.detail().contains("symbol"), r.detail());
  }

  @Test
  void codeThatCompilesButIsWrongIsTestFailure() {
    TaskResult r =
        answered(
            "fix-bug-off-by-one",
            "```java\n  public static int sumUpTo(int n) {\n    return 0;\n  }\n```\n");
    assertTrue(r.compiled());
    assertFalse(r.passed());
    assertEquals(FailureReason.TEST_FAILURE, r.failure());
    assertTrue(r.detail().contains("SumsTest"), r.detail());
  }

  @Test
  void aChangeFarBeyondTheTaskIsOversizedWhateverTheTestsSay() {
    StringBuilder big = new StringBuilder("```java\n  public static int sumUpTo(int n) {\n");
    for (int i = 0; i < 80; i++)
      big.append("    int unused").append(i).append(" = ").append(i).append(";\n");
    big.append(
        "    int total = 0;\n"
            + "    for (int i = 1; i <= n; i++) total += i;\n"
            + "    return total;\n"
            + "  }\n"
            + "```\n");
    TaskResult r = answered("fix-bug-off-by-one", big.toString());
    assertEquals(FailureReason.OVERSIZED, r.failure());
    assertTrue(r.applied());
    assertTrue(r.detail().contains("limit"), r.detail());
  }

  @Test
  void generatedTestsThatAssertNothingAreNotAcceptedAsTests() {
    TaskResult r =
        answered(
            "tests-slugify",
            "```java\n"
                + "package web;\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "class WeakSlugifierTest {\n"
                + "  @Test\n"
                + "  void runs() {\n"
                + "    Slugifier.slugify(\"a\");\n"
                + "  }\n"
                + "}\n"
                + "```\n");
    assertEquals(FailureReason.TEST_FAILURE, r.failure());
    assertTrue(r.detail().contains("planted bug"), r.detail());
  }

  @Test
  void generatedTestsThatFailOnTheRealCodeAreTestFailure() {
    TaskResult r =
        answered(
            "tests-slugify",
            "```java\n"
                + "package web;\n"
                + "import org.junit.jupiter.api.Test;\n"
                + "import static org.junit.jupiter.api.Assertions.*;\n"
                + "class WrongSlugifierTest {\n"
                + "  @Test\n"
                + "  void wrong() {\n"
                + "    assertEquals(\"nope\", Slugifier.slugify(\"a\"));\n"
                + "  }\n"
                + "}\n"
                + "```\n");
    assertEquals(FailureReason.TEST_FAILURE, r.failure());
    assertTrue(r.detail().contains("fail on the real code"), r.detail());
  }

  @Test
  void aProviderThatTimesOutIsATimeout() {
    ScriptedProvider provider = ScriptedProvider.replying("x");
    provider.failure = new AiException(AiException.Kind.TIMEOUT, "no answer in 120 s");
    assertEquals(
        FailureReason.TIMEOUT, runner(provider).run(task("fix-bug-off-by-one"), 1).failure());
  }

  @Test
  void aRefusalIsRefused() {
    ScriptedProvider provider = ScriptedProvider.replying("x");
    provider.failure = new AiException(AiException.Kind.REFUSED, "no");
    assertEquals(
        FailureReason.REFUSED, runner(provider).run(task("fix-bug-off-by-one"), 1).failure());
  }

  @Test
  void aProviderThatIsNotRunningStopsTheEvalInsteadOfFailingEveryTask() {
    ScriptedProvider provider = ScriptedProvider.replying("x");
    provider.failure = new AiException(AiException.Kind.UNAVAILABLE, "Ollama is not running");
    EvalRunner.EvalAbort stop =
        assertThrows(
            EvalRunner.EvalAbort.class, () -> runner(provider).run(task("fix-bug-off-by-one"), 1));
    assertTrue(stop.getMessage().contains("Ollama is not running"));
    assertTrue(stop.getMessage().contains("fix-bug-off-by-one"));
  }

  @Test
  void tokenCountsAreSummedOverTheCallsAndUnknownWinsOverPartial() {
    MeteredProvider metered =
        new MeteredProvider(
            ScriptedProvider.replying("x"), com.javelin.ai.Sampling.deterministic(1));
    assertEquals(-1, metered.promptTokens(), "nothing asked yet");
    var request = new com.javelin.ai.AiRequest("m", "s", "p");
    try {
      metered.complete(request, s -> {}, new com.javelin.ai.AiCancel());
      metered.complete(request, s -> {}, new com.javelin.ai.AiCancel());
    } catch (AiException ex) {
      throw new AssertionError(ex);
    }
    assertEquals(2, metered.calls());
    assertEquals(200, metered.promptTokens());
    assertEquals(40, metered.completionTokens());
    MeteredProvider partial =
        new MeteredProvider(
            new ScriptedProvider(
                q ->
                    new AiReply(
                        "x", false, q.prompt().equals("p") ? AiUsage.UNKNOWN : new AiUsage(1, 1))),
            com.javelin.ai.Sampling.deterministic(1));
    try {
      partial.complete(request, s -> {}, new com.javelin.ai.AiCancel());
      partial.complete(
          new com.javelin.ai.AiRequest("m", "s", "q"), s -> {}, new com.javelin.ai.AiCancel());
    } catch (AiException ex) {
      throw new AssertionError(ex);
    }
    assertEquals(-1, partial.promptTokens());
    assertEquals("scripted", partial.id());
    assertEquals("Scripted", partial.displayName());
    assertEquals("127.0.0.1:1", partial.location());
    assertTrue(partial.isLocal());
    try {
      assertEquals(List.of("scripted-model"), partial.models(new com.javelin.ai.AiCancel()));
      assertTrue(partial.contextLimit("m", new com.javelin.ai.AiCancel()).isEmpty());
    } catch (AiException ex) {
      throw new AssertionError(ex);
    }
  }

  // ---- Phase 2: the verification loop in the eval ----

  private EvalRunner verifying(ScriptedProvider provider, int retries) {
    return new EvalRunner(provider, EvalProfile.small("scripted-model"), scratch, retries);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("ids")
  void withTheLoopOnAModelThatKnowsTheAnswerStillAsksOnce(String id) {
    ScriptedProvider provider = ScriptedProvider.oracle(tasks);
    TaskResult r = verifying(provider, 2).run(task(id), 42);
    assertTrue(r.passed(), id + ": " + r.failure() + " " + r.detail());
    assertEquals(1, r.attempts(), "a change that compiles is not asked for again");
    assertFalse(r.flagged());
  }

  /** The first answer does not compile; every later one is the reference change. */
  private static ScriptedProvider brokenThenRight() {
    ScriptedProvider oracle = ScriptedProvider.oracle(tasks);
    int[] calls = {0};
    return new ScriptedProvider(
        request -> {
          if (calls[0]++ == 0)
            return new AiReply(
                "```java\npublic static int sumUpTo(int n) { return n +; }\n```", false);
          try {
            return oracle.complete(request, s -> {}, new com.javelin.ai.AiCancel());
          } catch (AiException ex) {
            throw new IllegalStateException(ex);
          }
        });
  }

  @Test
  void aBrokenFirstAnswerIsRepairedAndTheTaskPasses() {
    ScriptedProvider provider = brokenThenRight();
    TaskResult r = verifying(provider, 2).run(task("fix-bug-off-by-one"), 42);
    assertTrue(r.passed(), r.failure() + " " + r.detail());
    assertEquals(2, r.attempts());
    assertTrue(provider.requests.get(1).prompt().contains("did not compile"));
  }

  @Test
  void withoutRetriesTheSameBrokenAnswerIsAFailureAsInPhaseZero() {
    TaskResult r = verifying(brokenThenRight(), 0).run(task("fix-bug-off-by-one"), 42);
    assertFalse(r.passed());
    assertEquals(1, r.attempts());
    assertTrue(r.flagged(), "the editor's own check said the proposal is broken");
    TaskResult phaseZero = runner(brokenThenRight()).run(task("fix-bug-off-by-one"), 42);
    assertEquals(r.failure(), phaseZero.failure());
  }

  @Test
  void theRetriesAreWhatWasAsked() {
    assertEquals(-1, runner(ScriptedProvider.oracle(tasks)).retries());
    assertEquals(3, verifying(ScriptedProvider.oracle(tasks), 3).retries());
  }

  @Test
  void aProjectRequestIsVerifiedAndRepairedToo() {
    EvalTask multi =
        tasks.stream().filter(t -> t.mode() == EvalTask.Mode.PROJECT).findFirst().orElseThrow();
    ScriptedProvider oracle = ScriptedProvider.oracle(tasks);
    int[] calls = {0};
    ScriptedProvider provider =
        new ScriptedProvider(
            request -> {
              if (calls[0]++ == 0) {
                try {
                  AiReply good = oracle.complete(request, s -> {}, new com.javelin.ai.AiCancel());
                  return new AiReply(
                      good.text().replaceFirst("return ", "return missing + "), false);
                } catch (AiException ex) {
                  throw new IllegalStateException(ex);
                }
              }
              try {
                return oracle.complete(request, s -> {}, new com.javelin.ai.AiCancel());
              } catch (AiException ex) {
                throw new IllegalStateException(ex);
              }
            });
    TaskResult r = verifying(provider, 2).run(multi, 42);
    assertEquals(2, r.attempts(), r.failure() + " " + r.detail());
    assertTrue(r.passed(), r.failure() + " " + r.detail());
  }
}
