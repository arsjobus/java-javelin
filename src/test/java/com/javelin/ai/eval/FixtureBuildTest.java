package com.javelin.ai.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The build step must survive code that a model wrote badly, and say what went wrong. */
class FixtureBuildTest {
  @TempDir Path temp;

  private FixtureBuild.TestRun runTest(String body, String... classes) throws IOException {
    Path project = temp.resolve("p");
    Trees.write(
        project,
        "src/test/java/t/ATest.java",
        "package t;\n"
            + "import org.junit.jupiter.api.*;\n"
            + "import static org.junit.jupiter.api.Assertions.*;\n"
            + "class ATest {\n"
            + body
            + "\n}\n");
    FixtureBuild.Compiled c = FixtureBuild.compile(project, temp.resolve("o"));
    assertTrue(c.ok(), c.errors().toString());
    return FixtureBuild.run(
        c.classes(), List.of(classes.length == 0 ? new String[] {"t.ATest"} : classes));
  }

  @Test
  void passingAndFailingTestsAreToldApart() throws IOException {
    FixtureBuild.TestRun run =
        runTest(
            "@Test void good() { assertEquals(2, 1 + 1); }\n"
                + "@Test void bad() { assertEquals(3, 1 + 1); }");
    assertEquals(List.of("t.ATest#good"), run.passed());
    assertEquals(List.of("t.ATest#bad"), List.copyOf(run.failed().keySet()));
    assertTrue(run.failed().get("t.ATest#bad").contains("expected: <3> but was: <2>"));
    assertFalse(run.ok());
    assertTrue(run.describe().contains("1 test(s) failed"), run.describe());
  }

  @Test
  void allPassingIsOk() throws IOException {
    FixtureBuild.TestRun run = runTest("@Test void good() { assertTrue(true); }");
    assertTrue(run.ok());
    assertEquals("", run.describe());
  }

  @Test
  void beforeAndAfterEachRunAroundEveryTestAndDisabledOnesAreSkipped() throws IOException {
    FixtureBuild.TestRun run =
        runTest(
            "int n;\n@BeforeEach void up() { n = 5; }\n@AfterEach void down() { n = 0; }\n"
                + "@Test void sees() { assertEquals(5, n); }\n"
                + "@Disabled @Test void skipped() { fail(); }");
    assertTrue(run.ok(), run.describe());
    assertEquals(1, run.passed().size());
  }

  @Test
  void aClassWithNoTestsIsNotAPass() throws IOException {
    FixtureBuild.TestRun run = runTest("void helper() {}");
    assertTrue(run.noTests());
    assertFalse(run.ok());
    assertEquals("no test method was found", run.describe());
  }

  @Test
  void aClassThatIsNotThereIsACrashNotAPass() throws IOException {
    FixtureBuild.TestRun run = runTest("@Test void a() {}", "t.Missing");
    assertFalse(run.ok());
    assertTrue(run.describe().contains("t.Missing could not be loaded"), run.describe());
  }

  @Test
  void aTestThatNeverEndsIsKilledAfterTheLimit() throws IOException {
    long started = System.nanoTime();
    FixtureBuild.TestRun run = runTest("@Test void forever() { while (true) {} }");
    assertTrue(run.timedOut());
    assertFalse(run.ok());
    assertEquals("the tests did not finish in time", run.describe());
    assertTrue(
        (System.nanoTime() - started) / 1_000_000_000 < FixtureBuild.TEST_TIMEOUT_SECONDS + 20);
  }

  @Test
  void aTestThatExitsTheJvmCannotTakeTheEvalDown() throws IOException {
    FixtureBuild.TestRun run = runTest("@Test void quits() { System.exit(3); }");
    assertFalse(run.ok());
    assertTrue(run.passed().isEmpty());
    assertEquals("no test ran", run.describe());
  }

  @Test
  void testsThatPrintDoNotConfuseTheReport() throws IOException {
    FixtureBuild.TestRun run =
        runTest("@Test void chatty() { System.out.println(\"@@EVAL@@\\tFAIL\\tfake\"); }");
    assertTrue(run.ok(), run.describe());
  }

  @Test
  void compileErrorsNameTheFileTheLineAndTheProblem() throws IOException {
    Path project = temp.resolve("bad");
    Trees.write(project, "src/main/java/A.java", "class A {\n  int f() { return missing; }\n}\n");
    FixtureBuild.Compiled c = FixtureBuild.compile(project, temp.resolve("o2"));
    assertFalse(c.ok());
    assertEquals(1, c.errors().size());
    assertTrue(c.errors().get(0).startsWith("A.java:2: "), c.errors().toString());
    assertTrue(c.errors().get(0).contains("missing"));
  }

  @Test
  void aFolderWithNoSourcesStillCompilesTheRunnerAlone() throws IOException {
    Path project = temp.resolve("empty");
    java.nio.file.Files.createDirectories(project);
    assertTrue(FixtureBuild.compile(project, temp.resolve("o3")).ok());
  }

  @Test
  void theLibrariesAreFoundOnTheClassPath() {
    assertFalse(FixtureBuild.libraries().isEmpty());
  }

  @Test
  void treesCopyOverlayAndReadByRelativePath() throws IOException {
    Path a = temp.resolve("a");
    Trees.write(a, "x/one.txt", "1");
    Trees.write(a, "two.txt", "2");
    Path b = temp.resolve("b");
    Trees.copy(a, b);
    Trees.write(temp.resolve("o"), "two.txt", "TWO");
    Trees.overlay(temp.resolve("o"), b);
    assertEquals(Map.of("x/one.txt", "1", "two.txt", "TWO"), Trees.read(b));
    assertTrue(Trees.read(temp.resolve("nothing")).isEmpty());
  }
}
