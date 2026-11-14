package com.javelin.ai.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.javelin.ai.FailureReason;
import com.javelin.language.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The numbers the baseline is made of, and the files that carry them. */
class EvalReportTest {
  private static TaskResult r(
      String id, EvalTask.Category category, FailureReason failure, long tokens) {
    boolean passed = failure == null;
    return new TaskResult(
        id,
        category,
        failure == null || failure == FailureReason.TEST_FAILURE,
        passed || failure == FailureReason.TEST_FAILURE,
        passed,
        1,
        2000,
        tokens < 0 ? -1 : tokens,
        tokens < 0 ? -1 : tokens / 10,
        4,
        false,
        failure,
        passed ? "" : "because");
  }

  private static EvalReport report() {
    var fix = EvalTask.Category.FIX_BUG;
    var add = EvalTask.Category.ADD_METHOD;
    return new EvalReport(
        "small",
        "qwen3.5:9b",
        "2026-11-07",
        42,
        3,
        List.of(
            List.of(
                r("a", fix, null, 100),
                r("b", fix, FailureReason.TEST_FAILURE, 100),
                r("c", add, null, 100)),
            List.of(
                r("a", fix, null, 100),
                r("b", fix, FailureReason.NO_EDIT_BLOCK, 100),
                r("c", add, FailureReason.TRUNCATED, 100)),
            List.of(
                r("a", fix, null, 100),
                r("b", fix, FailureReason.TEST_FAILURE, 100),
                r("c", add, null, 100))),
        90_000);
  }

  @Test
  void passRatesAreFiguredPerRunAndOverTheRuns() {
    EvalReport report = report();
    assertEquals(List.of(2 / 3.0, 1 / 3.0, 2 / 3.0), report.rates());
    assertEquals(5 / 9.0, report.mean(), 1e-9);
    assertEquals(1 / 3.0, report.min(), 1e-9);
    assertEquals(2 / 3.0, report.max(), 1e-9);
    assertEquals(0.19245, report.stddev(), 1e-4);
  }

  @Test
  void aSingleRunHasNoSpread() {
    EvalReport one =
        new EvalReport(
            "small",
            "m",
            "d",
            1,
            1,
            List.of(List.of(r("a", EvalTask.Category.FIX_BUG, null, -1))),
            1);
    assertEquals(0, one.stddev());
    assertEquals(1.0, one.mean());
    assertEquals(-1, one.tokens(0));
    EvalReport none = new EvalReport("small", "m", "d", 1, 0, List.of(List.of()), 1);
    assertEquals(0.0, none.mean());
  }

  @Test
  void failuresAreCountedByReasonAndTasksAreSortedByHowStableTheyAre() {
    EvalReport report = report();
    assertEquals(
        Map.of(
            FailureReason.TEST_FAILURE,
            2,
            FailureReason.NO_EDIT_BLOCK,
            1,
            FailureReason.TRUNCATED,
            1),
        report.failures());
    assertEquals(List.of("c"), report.unstable());
    assertEquals(List.of("b"), report.neverPassed());
    assertEquals(
        Map.of(EvalTask.Category.FIX_BUG, 3 / 6.0, EvalTask.Category.ADD_METHOD, 2 / 3.0),
        report.byCategory());
    assertEquals(330, report.tokens(0));
  }

  @Test
  void theJsonHoldsTheSummaryAndEveryTaskOfEveryRun() {
    Object json = Json.parse(report().toJson());
    assertEquals("qwen3.5:9b", Json.string(json, "model"));
    assertEquals(3, Json.integer(json, "runCount", 0));
    Object summary = Json.get(json, "summary");
    assertEquals(2, Json.integer(Json.get(summary, "failures"), "TEST_FAILURE", 0));
    List<Object> runs = Json.asArray(Json.get(json, "runs"));
    assertEquals(3, runs.size());
    List<Object> first = Json.asArray(runs.get(0));
    assertEquals(3, first.size());
    assertEquals("a", Json.string(first.get(0), "id"));
    assertTrue(Json.bool(first.get(0), "passed"));
    assertEquals("TEST_FAILURE", Json.string(first.get(1), "failure"));
    assertEquals(100, Json.integer(first.get(0), "promptTokens", -1));
  }

  @Test
  void unreportedTokensAreNullInTheJsonAndSaidSoInTheMarkdown() {
    EvalReport unknown =
        new EvalReport(
            "small",
            "m",
            "2026-01-01",
            1,
            1,
            List.of(List.of(r("a", EvalTask.Category.FIX_BUG, null, -1))),
            1);
    Object task =
        Json.asArray(Json.asArray(Json.get(Json.parse(unknown.toJson()), "runs")).get(0)).get(0);
    assertEquals(null, Json.get(task, "promptTokens"));
    assertTrue(unknown.toMarkdown().contains("tokens not reported"));
    assertTrue(unknown.toMarkdown().contains("No task failed."));
    assertTrue(unknown.toMarkdown().contains("No task changed outcome between runs."));
  }

  @Test
  void theBaselineDocumentSaysWhatWasMeasuredHowAndWhatCameOut() {
    String md = report().toMarkdown();
    assertTrue(md.startsWith("# AI edit baseline: qwen3.5:9b, 2026-11-07"));
    assertTrue(md.contains("-Deval.model=qwen3.5:9b"));
    assertTrue(md.contains("seed 42 plus the run number"));
    assertTrue(md.contains("| 1 | 2 of 3 | 67% |"), md);
    assertTrue(md.contains("| 2 | 1 of 3 | 33% |"));
    assertTrue(md.contains("Mean 56%, lowest 33%, highest 67%"), md);
    assertTrue(md.contains("| TEST_FAILURE | 2 |"));
    assertTrue(md.contains("| FIX_BUG | 50% |"));
    assertTrue(md.contains("Passed in some runs, failed in others: c."));
    assertTrue(md.contains("## Never passed\n\nb."));
    assertTrue(md.contains("- Run 1: 6 s"));
    assertTrue(md.contains("330 tokens reported"));
    assertTrue(md.contains("single-attempt"));
  }

  @Test
  void contractFailuresCountOnlyRepliesThatCouldNotBeReadOrPlaced() {
    var fix = EvalTask.Category.FIX_BUG;
    EvalReport report =
        new EvalReport(
            "small",
            "m",
            "2026-11-08",
            1,
            1,
            List.of(
                List.of(
                    r("a", fix, FailureReason.UNPARSEABLE, 1),
                    r("b", fix, FailureReason.ANCHOR_NOT_FOUND, 1),
                    r("c", fix, FailureReason.TEST_FAILURE, 1),
                    r("d", fix, FailureReason.NO_EDIT_BLOCK, 1),
                    r("e", fix, null, 1))),
            1);
    assertEquals(2, report.contractFailures());
    assertEquals(5, report.totalResults());
    assertTrue(
        report
            .toMarkdown()
            .contains("Contract failures (`UNPARSEABLE` plus `ANCHOR_NOT_FOUND`): 2 of 5"));
    assertEquals(
        2, Json.integer(Json.get(Json.parse(report.toJson()), "summary"), "contractFailures", -1));
  }

  @Test
  void aVerifiedReportSaysSoAndCountsProposalsThatCompiled() {
    var fix = EvalTask.Category.FIX_BUG;
    TaskResult compiled = r("a", fix, null, 100);
    TaskResult notCompiled =
        new TaskResult(
            "b", fix, true, false, false, 3, 100, 10, 5, 2, true, FailureReason.COMPILE_ERROR, "x");
    TaskResult notApplied =
        new TaskResult(
            "c", fix, false, false, false, 1, 100, 10, 5, 0, false, FailureReason.UNPARSEABLE, "x");
    EvalReport verified =
        new EvalReport(
            "small",
            "m",
            "2026-11-08",
            42,
            3,
            List.of(List.of(compiled, notCompiled, notApplied)),
            1000,
            2);
    assertTrue(verified.verified());
    assertEquals(1, verified.reachedReviewCompiling());
    String md = verified.toMarkdown();
    assertTrue(md.contains("up to 2 more requests"), md);
    assertTrue(md.contains("Reached review with a compiling result: 1 of 3 results (33%)."), md);
    assertFalse(md.contains("single-attempt."), md);
    Object json = Json.get(Json.parse(verified.toJson()), "summary");
    assertEquals(1, Json.integer(json, "reachedReviewCompiling", -1));
    assertEquals(2, Json.integer(json, "retries", -1));
    var one =
        new EvalReport("small", "m", "d", 1, 1, List.of(List.of(compiled)), 1, 1).toMarkdown();
    assertTrue(one.contains("up to 1 more request "), one);
    var empty = new EvalReport("small", "m", "d", 1, 0, List.of(List.of()), 1, 2).toMarkdown();
    assertTrue(empty.contains("0 of 0 results (0%)"), empty);
  }

  @Test
  void aSingleAttemptReportIsUnchangedByPhaseTwo() {
    EvalReport plain = report();
    assertFalse(plain.verified());
    assertFalse(plain.toMarkdown().contains("Reached review"));
    assertNull(Json.get(Json.get(Json.parse(plain.toJson()), "summary"), "retries"));
  }

  @Test
  void aPlannedRunSaysSoInTheMarkdownAndTheJson() {
    EvalReport planned = new EvalReport("small", "m", "d", 1, 1, List.of(List.of()), 1, 2, 3);
    assertTrue(planned.planned());
    assertTrue(
        planned.toMarkdown().contains("planned first (at most 3 steps"), planned.toMarkdown());
    assertTrue(planned.toJson().contains("\"planSteps\":3"), planned.toJson());
    EvalReport plain = new EvalReport("small", "m", "d", 1, 1, List.of(List.of()), 1, 2);
    assertFalse(plain.planned());
    assertFalse(plain.toMarkdown().contains("planned first"));
    assertFalse(plain.toJson().contains("planSteps"));
  }

  @Test
  void aSymbolLevelReportSaysSoAndAPlainOneDoesNot() {
    EvalReport symbolic =
        new EvalReport("small", "m", "d", 1, 0, List.of(List.of()), 1, 0, 0, "small");
    assertTrue(symbolic.symbolic());
    assertTrue(symbolic.toMarkdown().contains("symbol level with the `small` profile"));
    assertTrue(symbolic.toJson().contains("\"symbols\""));
    EvalReport plain = report();
    assertFalse(plain.symbolic());
    assertFalse(plain.toMarkdown().contains("symbol level"));
    assertFalse(plain.toJson().contains("\"symbols\""));
  }

  @Test
  void aTaskThatNeverPassedSaysHowItFailed() {
    var add = EvalTask.Category.ADD_TESTS;
    TaskResult onReal =
        new TaskResult(
            "t",
            add,
            true,
            true,
            false,
            1,
            10,
            -1,
            -1,
            5,
            false,
            FailureReason.TEST_FAILURE,
            "the generated tests fail on the real code: slugify(null) threw\nstack");
    TaskResult onMutant =
        new TaskResult(
            "t",
            add,
            true,
            true,
            false,
            1,
            10,
            -1,
            -1,
            5,
            false,
            FailureReason.TEST_FAILURE,
            "the generated tests pass on the real code and also on the code with the planted bug");
    EvalReport report =
        new EvalReport(
            "small",
            "m",
            "2026-11-12",
            42,
            1,
            List.of(List.of(onReal), List.of(onReal), List.of(onMutant)),
            1000,
            0);
    assertEquals(
        Map.of(
            "t",
            "TEST_FAILURE: the generated tests fail on the real code: slugify(null) threw"
                + " (2 of 3 runs)"),
        report.neverPassedWhy());
    String md = report.toMarkdown();
    assertTrue(md.contains("## How the never-passed tasks failed\n\n- t: TEST_FAILURE:"), md);
    assertNotNull(Json.get(Json.get(Json.parse(report.toJson()), "summary"), "neverPassedWhy"));
  }

  @Test
  void aReportWithNoNeverPassedTaskHasNoSuchSection() {
    assertFalse(
        new EvalReport(
                "small",
                "m",
                "2026-11-12",
                42,
                1,
                List.of(List.of(r("a", EvalTask.Category.FIX_BUG, null, 10))),
                1000,
                0)
            .toMarkdown()
            .contains("How the never-passed tasks failed"));
  }
}
