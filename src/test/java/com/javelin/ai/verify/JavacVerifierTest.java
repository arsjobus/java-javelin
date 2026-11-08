package com.javelin.ai.verify;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.ai.AiCancel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavacVerifierTest {
  @TempDir Path dir;

  private JavacVerifier verifier(VerifyConfig config) throws IOException {
    VerifyFixtures.project(dir);
    return new JavacVerifier(dir, VerifyFixtures.withJunit(), config);
  }

  private static final String CALC_WITH_NEW_SIGNATURE =
      "package demo;\n\n"
          + "public class Calc {\n"
          + "  public long add(long a, long b, long c) {\n"
          + "    return a + b + c;\n"
          + "  }\n"
          + "}\n";

  @Test
  void aChangeThatCompilesPasses() throws IOException {
    Verification v =
        verifier(VerifyConfig.defaults())
            .verify(
                Map.of("src/main/java/demo/Calc.java", VerifyFixtures.CALC_FIXED), new AiCancel());
    assertEquals(Verification.Outcome.PASSED, v.outcome(), v.problems().toString());
    assertTrue(v.ok());
    assertNull(v.failure());
  }

  @Test
  void aSyntaxErrorIsReportedWithFileAndLine() throws IOException {
    String broken = VerifyFixtures.CALC.replace("return a - b;", "return a + ;");
    Verification v =
        verifier(VerifyConfig.defaults())
            .verify(Map.of("src/main/java/demo/Calc.java", broken), new AiCancel());
    assertEquals(Verification.Outcome.COMPILE_FAILED, v.outcome());
    VerifyProblem first = v.problems().get(0);
    assertEquals("src/main/java/demo/Calc.java", first.path());
    assertEquals(5, first.line());
    assertTrue(first.label().startsWith("src/main/java/demo/Calc.java:5: "));
    assertEquals(com.javelin.ai.FailureReason.COMPILE_ERROR, v.failure());
  }

  @Test
  void aChangeThatBreaksAnUnchangedCallerIsCaught() throws IOException {
    Verification v =
        verifier(VerifyConfig.defaults())
            .verify(
                Map.of("src/main/java/demo/Calc.java", CALC_WITH_NEW_SIGNATURE), new AiCancel());
    assertEquals(Verification.Outcome.COMPILE_FAILED, v.outcome());
    assertTrue(
        v.problems().stream().anyMatch(p -> p.path().equals("src/main/java/demo/User.java")),
        v.problems().toString());
  }

  @Test
  void aNewFileIsCompiledWithTheRestOfTheProject() throws IOException {
    String extra =
        "package demo;\n\n"
            + "public class Twice {\n"
            + "  int of(int x) {\n"
            + "    return new Calc().add(x, x);\n"
            + "  }\n"
            + "}\n";
    Verification v =
        verifier(VerifyConfig.defaults())
            .verify(Map.of("src/main/java/demo/Twice.java", extra), new AiCancel());
    assertEquals(Verification.Outcome.PASSED, v.outcome(), v.problems().toString());
  }

  @Test
  void errorsTheProjectAlreadyHadAreNotBlamedOnTheChange() throws IOException {
    VerifyFixtures.project(dir);
    VerifyFixtures.write(
        dir,
        "src/main/java/demo/Old.java",
        "package demo;\n\npublic class Old {\n  int x() {\n    return missing();\n  }\n}\n");
    JavacVerifier verifier =
        new JavacVerifier(dir, VerifyFixtures.withJunit(), VerifyConfig.defaults());
    Verification v =
        verifier.verify(
            Map.of("src/main/java/demo/Calc.java", VerifyFixtures.CALC_FIXED), new AiCancel());
    // Old.java is pulled in only if something uses it; use it from the changed file
    String usesOld =
        VerifyFixtures.CALC_FIXED.replace("return a + b;", "return a + b + new Old().x();");
    Verification withOld =
        verifier.verify(Map.of("src/main/java/demo/Calc.java", usesOld), new AiCancel());
    assertEquals(Verification.Outcome.PASSED, v.outcome());
    assertEquals(
        Verification.Outcome.PASSED,
        withOld.outcome(),
        "the error in Old.java is the project's own: " + withOld.problems());
  }

  @Test
  void aFileThatIsNotJavaIsNotChecked() throws IOException {
    Verification v =
        verifier(VerifyConfig.defaults()).verify(Map.of("README.md", "# hi"), new AiCancel());
    assertEquals(Verification.Outcome.NOT_CHECKED, v.outcome());
    assertFalse(v.failed());
    assertNull(v.failure(), "not being able to look is not a failure of the model");
  }

  @Test
  void anUnknownClassPathIsNotCheckedRatherThanBlamedOnTheModel() throws IOException {
    VerifyFixtures.project(dir);
    JavacVerifier verifier =
        new JavacVerifier(
            dir,
            new ProjectClasspath.Resolved(List.of(), false, "the class path is not known: x"),
            VerifyConfig.defaults());
    Verification v =
        verifier.verify(Map.of("src/main/java/demo/Calc.java", "not java at all"), new AiCancel());
    assertEquals(Verification.Outcome.NOT_CHECKED, v.outcome());
    assertTrue(v.note().contains("class path is not known"));
  }

  @Test
  void checkingNeverTouchesTheProject() throws IOException, InterruptedException {
    JavacVerifier verifier = verifier(VerifyConfig.defaults());
    Map<String, String> before = VerifyFixtures.snapshot(dir);
    Thread.sleep(20);
    verifier.verify(
        Map.of("src/main/java/demo/Calc.java", VerifyFixtures.CALC_FIXED), new AiCancel());
    verifier.verify(
        Map.of("src/main/java/demo/Calc.java", VerifyFixtures.CALC.replace("a - b", "a +")),
        new AiCancel());
    verifier.verify(
        Map.of("src/main/java/demo/New.java", "package demo;\nclass New {}\n"), new AiCancel());
    assertEquals(before, VerifyFixtures.snapshot(dir), "contents, names and modification times");
    try (Stream<Path> walk = Files.walk(dir)) {
      assertTrue(walk.noneMatch(p -> p.toString().endsWith(".class")), "no class file was written");
    }
  }

  // ---- tests ----

  private static VerifyConfig withTests() {
    return new VerifyConfig(true, 2, true, 30);
  }

  @Test
  void testsThatUseTheChangedClassArePicked() throws IOException {
    JavacVerifier verifier = verifier(withTests());
    assertEquals(
        List.of("src/test/java/demo/CalcTest.java"),
        verifier.selectTests(Map.of("src/main/java/demo/Calc.java", VerifyFixtures.CALC_FIXED)));
    assertEquals(
        List.of(),
        verifier.selectTests(Map.of("src/main/java/demo/User.java", VerifyFixtures.USER)),
        "no test mentions User");
  }

  @Test
  void aFailingTestIsReported() throws IOException {
    Verification v =
        verifier(withTests())
            .verify(Map.of("src/main/java/demo/Calc.java", VerifyFixtures.CALC), new AiCancel());
    assertEquals(Verification.Outcome.TESTS_FAILED, v.outcome(), v.problems().toString());
    assertTrue(v.problems().get(0).message().contains("CalcTest#adds"), v.problems().toString());
    assertEquals(com.javelin.ai.FailureReason.TEST_FAILURE, v.failure());
  }

  @Test
  void aPassingTestPasses() throws IOException {
    Verification v =
        verifier(withTests())
            .verify(
                Map.of("src/main/java/demo/Calc.java", VerifyFixtures.CALC_FIXED), new AiCancel());
    assertEquals(Verification.Outcome.PASSED, v.outcome(), v.problems().toString());
    assertEquals(1, v.testsRun());
    assertTrue(v.summary().contains("1 test(s) passed"));
  }

  @Test
  void aTestThatDoesNotCompileAfterTheChangeIsACompileFailure() throws IOException {
    Verification v =
        verifier(withTests())
            .verify(
                Map.of(
                    "src/main/java/demo/Calc.java",
                    "package demo;\n\n"
                        + "public class Calc {\n"
                        + "  public int plus(int a, int b) {\n"
                        + "    return a + b;\n"
                        + "  }\n"
                        + "}\n"),
                new AiCancel());
    assertEquals(Verification.Outcome.COMPILE_FAILED, v.outcome());
  }

  @Test
  void aTestThatNeverEndsIsStoppedAndLeavesNoTemporaryFiles() throws IOException {
    VerifyFixtures.project(dir);
    VerifyFixtures.write(
        dir,
        "src/main/java/demo/Calc.java",
        VerifyFixtures.CALC_FIXED.replace("return a + b;", "return a + b;"));
    String loop = VerifyFixtures.CALC_FIXED.replace("return a + b;", "while (true) {}");
    Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
    long before = leftovers(tmp);
    JavacVerifier verifier =
        new JavacVerifier(dir, VerifyFixtures.withJunit(), new VerifyConfig(true, 2, true, 5));
    Verification v = verifier.verify(Map.of("src/main/java/demo/Calc.java", loop), new AiCancel());
    assertEquals(Verification.Outcome.TESTS_FAILED, v.outcome());
    assertTrue(v.problems().stream().anyMatch(p -> p.message().contains("did not finish")));
    assertEquals(before, leftovers(tmp));
  }

  private static long leftovers(Path tmp) throws IOException {
    try (Stream<Path> list = Files.list(tmp)) {
      return list.filter(p -> p.getFileName().toString().startsWith("javelin-verify-")).count();
    }
  }

  @Test
  void withoutJunitOnTheClassPathTestsAreSkippedWithANote() throws IOException {
    VerifyFixtures.project(dir);
    VerifyFixtures.write(dir, "src/test/java/demo/CalcTest.java", VerifyFixtures.CALC_TEST);
    JavacVerifier verifier =
        new JavacVerifier(dir, new ProjectClasspath.Resolved(List.of(), true, ""), withTests());
    Verification v =
        verifier.verify(
            Map.of("src/main/java/demo/Calc.java", VerifyFixtures.CALC_FIXED), new AiCancel());
    assertEquals(Verification.Outcome.PASSED, v.outcome());
    assertTrue(v.note().contains("no JUnit"));
  }
}
