package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.javelin.ai.AiException;
import com.javelin.ai.project.SelectionEvaluation.Case;
import com.javelin.ai.project.SelectionEvaluation.Report;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The evaluation fixtures: feature requests with the files a good answer needs, run against a small
 * project (exact expectations) and against this editor's own source (a regression and speed check
 * on a real repository). A request that a mode gets wrong is recorded here as a case.
 */
class SelectionEvaluationTest {
  @TempDir Path dir;

  static final List<Case> MINI_CASES =
      List.of(
          new Case(
              "uml generator for the editor",
              "Add a UML generator to the editor",
              Set.of(
                  "src/main/java/demo/diagram/DiagramRenderer.java",
                  "src/main/java/demo/parse/ClassModel.java",
                  "src/main/java/demo/editor/Editor.java",
                  "src/test/java/demo/diagram/DiagramRendererTest.java",
                  "ARCHITECTURE.md",
                  "pom.xml")),
          new Case(
              "highlighter colours keywords",
              "Fix the highlighter so it colors keywords",
              Set.of(
                  "src/main/java/demo/editor/Highlighter.java",
                  "src/main/java/demo/editor/Editor.java",
                  "src/test/java/demo/editor/EditorTest.java")),
          new Case(
              "ai client retries",
              "Make the AI client retry when the call fails",
              Set.of("src/main/java/demo/ai/AiClient.java")),
          new Case(
              "string helper",
              "Add a blank check to the string utility",
              Set.of(
                  "src/main/java/demo/util/Strings.java",
                  "src/test/java/demo/util/StringsTest.java")));

  @Test
  void smartContextFindsEveryNeededFileOfTheMiniProjectWithFewerFilesThanFull()
      throws IOException, AiException {
    ProjectFixture.miniEditor(dir);
    ProjectWorkspace ws = new ProjectWorkspace(dir, ScanOptions.defaults());
    Report report = SelectionEvaluation.run(ws, MINI_CASES, new ContextBudget(0, 200_000, 4_000));
    System.out.println(report.describe());
    assertEquals(1.0, report.meanRecall(ContextMode.SMART), report.describe());
    assertEquals(1.0, report.meanRecall(ContextMode.FULL_PROJECT));
    for (SelectionEvaluation.Outcome smart : report.of(ContextMode.SMART))
      assertTrue(
          smart.includedFiles() < smart.eligibleFiles(),
          smart.testCase().name() + " should be a subset of the project");
    assertEquals(MINI_CASES.size() * 2, report.outcomes().size());
  }

  @Test
  void whenTheWindowIsSmallSmartContextKeepsTheNeededFilesThatFullProjectDrops()
      throws IOException, AiException {
    ProjectFixture.miniEditor(dir);
    ProjectWorkspace ws = new ProjectWorkspace(dir, ScanOptions.defaults());
    Report report = SelectionEvaluation.run(ws, MINI_CASES, new ContextBudget(0, 2_800, 400));
    System.out.println(report.describe());
    assertTrue(
        report.meanRecall(ContextMode.SMART) > report.meanRecall(ContextMode.FULL_PROJECT),
        report.describe());
    assertTrue(report.meanRecall(ContextMode.SMART) >= 0.8, report.describe());
  }

  @Test
  void theReportNamesWhatWasMissed() throws IOException, AiException {
    ProjectFixture.miniEditor(dir);
    ProjectWorkspace ws = new ProjectWorkspace(dir, ScanOptions.defaults());
    Case impossible =
        new Case(
            "impossible", "Reticulate the splines", Set.of("src/main/java/demo/ai/AiClient.java"));
    Report report =
        SelectionEvaluation.run(ws, List.of(impossible), new ContextBudget(0, 200_000, 4_000));
    SelectionEvaluation.Outcome smart = report.of(ContextMode.SMART).get(0);
    assertEquals(Set.of("src/main/java/demo/ai/AiClient.java"), smart.missing());
    assertEquals(0.0, smart.recall());
    assertTrue(report.describe().contains("AiClient.java"));
    assertEquals(
        1.0, new Case("nothing needed", "x", Set.of()).neededFiles().isEmpty() ? 1.0 : 0.0);
    assertEquals(
        1.0,
        new SelectionEvaluation.Outcome(
                impossible, ContextMode.SMART, 0, 0, 0, Set.of(), Set.of(), 0)
            .recall());
  }

  private static Path repositoryRoot() {
    Path here = Path.of("").toAbsolutePath();
    return Files.isRegularFile(here.resolve("pom.xml"))
            && Files.isDirectory(here.resolve("src/main/java/com/javelin/ai"))
        ? here
        : null;
  }

  @Test
  void theEditorsOwnSourceIsScannedAndContextIsBuiltQuicklyAndFindsTheRightFiles()
      throws IOException, AiException {
    Path root = repositoryRoot();
    assumeTrue(root != null, "not run from the project root");
    long start = System.nanoTime();
    ProjectWorkspace ws = new ProjectWorkspace(root, ScanOptions.defaults());
    long scanMillis = (System.nanoTime() - start) / 1_000_000;
    assertTrue(ws.index().files().size() > 100);
    assertTrue(ws.index().find("pom.xml") != null);
    assertTrue(
        ws.index().files().stream()
            .noneMatch(f -> f.path().startsWith("target/") || f.path().startsWith(".git/")),
        "build output and version control are never scanned");
    List<Case> cases =
        List.of(
            new Case(
                "context budget",
                "Estimate how many tokens a project context uses",
                Set.of("src/main/java/com/javelin/ai/project/ContextBudget.java")),
            new Case(
                "ollama window",
                "Ask Ollama for a bigger context window with num_ctx",
                Set.of("src/main/java/com/javelin/ai/OllamaProvider.java")),
            new Case(
                "edit blocks",
                "Change how edit blocks are matched when the model loses indentation",
                Set.of(
                    "src/main/java/com/javelin/ai/EditBlocks.java",
                    "src/main/java/com/javelin/ai/LineMatch.java")));
    Report report = SelectionEvaluation.run(ws, cases, new ContextBudget(0, 32_768, 4_096));
    System.out.println(
        "scan: "
            + scanMillis
            + " ms, "
            + ws.index().files().size()
            + " files\n"
            + report.describe());
    assertEquals(1.0, report.meanRecall(ContextMode.SMART), report.describe());
    for (SelectionEvaluation.Outcome o : report.outcomes())
      assertTrue(o.millis() < 20_000, o.testCase().name() + " took " + o.millis() + " ms");
    assertTrue(scanMillis < 10_000, "scan took " + scanMillis);
  }
}
