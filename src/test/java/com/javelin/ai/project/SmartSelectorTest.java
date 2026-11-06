package com.javelin.ai.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SmartSelectorTest {
  @TempDir Path dir;
  ProjectWorkspace ws;

  @BeforeEach
  void setUp() throws IOException {
    ProjectFixture.miniEditor(dir);
    ws = new ProjectWorkspace(dir, ScanOptions.defaults());
  }

  private List<Pick> pick(String request) {
    return SmartSelector.select(ws.index(), ws.reader(), request, ws.options());
  }

  private static List<String> paths(List<Pick> picks) {
    return picks.stream().map(Pick::path).toList();
  }

  private static Pick of(List<Pick> picks, String suffix) {
    return picks.stream().filter(p -> p.path().endsWith(suffix)).findFirst().orElse(null);
  }

  @Test
  void aUmlRequestFindsTheDiagramCodeTheModelItNeedsTheEditorAndTheirTests() {
    List<String> paths = paths(pick("Add a UML generator to the editor"));
    for (String needed :
        List.of(
            "demo/diagram/DiagramRenderer.java",
            "demo/parse/ClassModel.java",
            "demo/editor/Editor.java",
            "demo/diagram/DiagramRendererTest.java",
            "demo/editor/EditorTest.java",
            "ARCHITECTURE.md",
            "pom.xml"))
      assertTrue(
          paths.stream().anyMatch(p -> p.endsWith(needed)), needed + " missing from " + paths);
    assertFalse(paths.stream().anyMatch(p -> p.endsWith("AiClient.java")), paths.toString());
    assertFalse(paths.stream().anyMatch(p -> p.endsWith("Strings.java")), paths.toString());
  }

  @Test
  void everyPickHasAReasonAPositiveScoreAndAppearsOnce() {
    List<Pick> picks = pick("Add a UML generator to the editor");
    assertEquals(picks.size(), paths(picks).stream().distinct().count());
    for (Pick p : picks) {
      assertFalse(p.reason().isBlank(), p.path());
      assertTrue(p.score() > 0, p.path());
    }
  }

  @Test
  void picksAreOrderedByScoreThenPathAndRepeatExactly() {
    List<Pick> a = pick("Add a UML generator to the editor");
    for (int i = 1; i < a.size(); i++) {
      boolean ordered =
          a.get(i - 1).score() > a.get(i).score()
              || (a.get(i - 1).score() == a.get(i).score()
                  && a.get(i - 1).path().compareTo(a.get(i).path()) < 0);
      assertTrue(ordered, a.get(i - 1) + " before " + a.get(i));
    }
    assertEquals(a, pick("Add a UML generator to the editor"));
  }

  @Test
  void reasonsSayWhyInWords() {
    List<Pick> picks = pick("Fix the highlighter so it colors keywords");
    assertTrue(of(picks, "Highlighter.java").reason().contains("file name matches: highlighter"));
    assertTrue(of(picks, "Highlighter.java").reason().contains("declares Highlighter"));
    assertTrue(
        of(picks, "Editor.java")
            .reason()
            .contains("uses src/main/java/demo/editor/Highlighter.java"));
    assertTrue(of(picks, "EditorTest.java").reason().contains("test of "));
    assertEquals("build configuration", of(picks, "pom.xml").reason());
    assertTrue(of(picks, "README.md").reason().startsWith("project documentation"));
    List<Pick> uml = pick("Add a UML generator");
    assertTrue(
        of(uml, "DiagramRenderer.java").reason().contains("name is related to: diagram"),
        of(uml, "DiagramRenderer.java").reason());
  }

  @Test
  void whatASelectedFileUsesAndWhatUsesItFollowImports() {
    List<Pick> picks = pick("Fix the highlighter so it colors keywords");
    assertTrue(
        of(picks, "Editor.java").reason().startsWith("uses "), of(picks, "Editor.java").reason());
    List<Pick> uml = pick("Add a UML generator");
    assertTrue(
        of(uml, "ClassModel.java").reason().contains("used by"),
        of(uml, "ClassModel.java").reason());
    assertTrue(
        of(uml, "JavaParser.java")
            .reason()
            .contains("uses src/main/java/demo/parse/ClassModel.java"));
  }

  @Test
  void samePackageNeighboursComeInAtALowScore() {
    List<Pick> picks = pick("Fix the highlighter so it colors keywords");
    Pick neighbour = of(picks, "Document.java");
    assertEquals("same package as src/main/java/demo/editor/Highlighter.java", neighbour.reason());
    assertTrue(
        neighbour.score() > 1.5 && neighbour.score() <= 2.0, "a low score: " + neighbour.score());
  }

  @Test
  void aRequestThatMatchesNothingStillGetsTheOverviewAndBuildFilesOnly() {
    List<Pick> picks = pick("Reticulate the splines");
    assertEquals(List.of("AGENTS.md", "ARCHITECTURE.md", "README.md", "pom.xml"), paths(picks));
  }

  @Test
  void aWeakMatchIsStillUsedWhenNothingMatchesStrongly() {
    List<Pick> picks = pick("utility");
    assertTrue(
        paths(picks).contains("src/main/java/demo/util/Strings.java"), paths(picks).toString());
  }

  @Test
  void documentsThatTalkAboutTheRequestAreIncluded() throws IOException {
    ProjectFixture.write(
        dir,
        "docs/telemetry.md",
        "# Telemetry\n\nTelemetry telemetry telemetry events are never sent.\n");
    ws.refresh(ws.options());
    Pick doc = of(pick("explain telemetry"), "telemetry.md");
    assertTrue(doc.reason().contains("telemetry"), doc.reason());
  }

  @Test
  void importsOfOtherLanguagesAreFollowed() throws IOException {
    ProjectFixture.write(
        dir,
        "web/viewer.js",
        "import { draw } from './chart/draw';\nexport const run = () => draw();\n");
    ProjectFixture.write(dir, "web/chart/draw.js", "export function draw() {}\n");
    ProjectFixture.write(
        dir, "py/main.py", "from pkg.tools import helper\nimport os\n\ndef run():\n    helper()\n");
    ProjectFixture.write(dir, "py/pkg/__init__.py", "");
    ProjectFixture.write(dir, "src/pkg/tools.py", "def helper():\n    pass\n");
    ProjectFixture.write(dir, "src/pkg/__init__.py", "x = 1\n");
    ws.refresh(ws.options());
    List<Pick> js = pick("change the viewer");
    assertTrue(
        of(js, "web/chart/draw.js") != null
            && of(js, "web/chart/draw.js").reason().startsWith("used by web/viewer.js"),
        js.toString());
    List<Pick> py = pick("change the main run");
    assertTrue(of(py, "src/pkg/tools.py") != null, py.toString());
  }

  @Test
  void wildcardAndStaticImportsAreFollowed() throws IOException {
    ProjectFixture.write(
        dir,
        "src/main/java/demo/wild/Wild.java",
        "package demo.wild;\n\n"
            + "import demo.parse.*;\n"
            + "import static demo.util.Strings.blank;\n\n"
            + "public class Wild {\n"
            + "  ClassModel m;\n"
            + "}\n");
    ws.refresh(ws.options());
    List<Pick> picks = pick("rework wild");
    assertTrue(of(picks, "ClassModel.java") != null, paths(picks).toString());
    assertTrue(of(picks, "Strings.java") != null, paths(picks).toString());
  }

  @Test
  void testsAreFoundByTheirNamesInSeveralLanguages() throws IOException {
    ProjectFixture.write(dir, "py/calc.py", "def add(a, b):\n    return a + b\n");
    ProjectFixture.write(dir, "py/test_calc.py", "def test_add():\n    pass\n");
    ProjectFixture.write(
        dir, "go/calc.go", "package calc\n\nfunc Add(a, b int) int { return a + b }\n");
    ProjectFixture.write(dir, "go/calc_test.go", "package calc\n\nfunc TestAdd() {}\n");
    ws.refresh(ws.options());
    List<Pick> picks = pick("change calc");
    assertTrue(of(picks, "py/test_calc.py").reason().contains("test of py/calc.py"));
    assertFalse(of(picks, "py/test_calc.py").reason().contains("go/calc.go"));
    assertTrue(of(picks, "go/calc_test.go").reason().contains("test of go/calc.go"));
  }

  @Test
  void aProjectWithUnreadableFilesStillGivesPicks() throws IOException {
    java.nio.file.Files.write(
        dir.resolve("src/main/java/demo/ai/AiClient.java"), new byte[] {(byte) 0xff, (byte) 0xfe});
    ws.reader().clear();
    assertFalse(pick("Fix the highlighter").isEmpty());
  }
}
