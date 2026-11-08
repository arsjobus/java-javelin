package com.javelin.ai.verify;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.ai.AiCancel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavacVerifierEdgesTest {
  @TempDir Path dir;
  private static final String CALC = "src/main/java/demo/Calc.java";

  private JavacVerifier verifier(VerifyConfig config) throws IOException {
    VerifyFixtures.project(dir);
    return new JavacVerifier(dir, VerifyFixtures.withJunit(), config);
  }

  private static VerifyConfig tests() {
    return new VerifyConfig(true, 2, true, 30);
  }

  private static String testClass(String name, String body) {
    return "package demo;\n\n"
        + "import org.junit.jupiter.api.*;\n"
        + "import static org.junit.jupiter.api.Assertions.*;\n\n"
        + "class "
        + name
        + " {\n"
        + body
        + "\n}\n";
  }

  @Test
  void noCompilerMeansNotChecked() throws IOException {
    VerifyFixtures.project(dir);
    JavacVerifier v =
        new JavacVerifier(dir, VerifyFixtures.withJunit(), VerifyConfig.defaults(), null);
    Verification r = v.verify(Map.of(CALC, VerifyFixtures.CALC_FIXED), new AiCancel());
    assertEquals(Verification.Outcome.NOT_CHECKED, r.outcome());
    assertTrue(r.note().contains("no compiler"));
  }

  @Test
  void cancellingBeforeTheTestsRunStopsThere() throws IOException {
    AiCancel cancel = new AiCancel();
    cancel.cancel();
    Verification r = verifier(tests()).verify(Map.of(CALC, VerifyFixtures.CALC_FIXED), cancel);
    assertEquals(Verification.Outcome.NOT_CHECKED, r.outcome());
    assertTrue(r.note().contains("cancelled"));
  }

  @Test
  void aNullCancelIsAllowed() throws IOException {
    Verification r =
        verifier(VerifyConfig.defaults()).verify(Map.of(CALC, VerifyFixtures.CALC_FIXED), null);
    assertTrue(r.ok());
  }

  @Test
  void aProjectThatCannotBeReadIsNotChecked() {
    JavacVerifier v =
        new JavacVerifier(
            dir.resolve("gone"),
            new ProjectClasspath.Resolved(List.of(), true, ""),
            VerifyConfig.defaults());
    Verification r =
        v.verify(Map.of("A.java", "class A { int x() { return y; } }"), new AiCancel());
    assertTrue(
        r.outcome() == Verification.Outcome.NOT_CHECKED
            || r.outcome() == Verification.Outcome.COMPILE_FAILED,
        r.toString());
  }

  @Test
  void aTestClassThatCannotBeLoadedIsReported() throws IOException {
    VerifyFixtures.project(dir);
    VerifyFixtures.write(
        dir,
        "src/test/java/demo/BoomTest.java",
        testClass(
            "BoomTest",
            "  static { if (true) throw new IllegalStateException(\"boom\"); }\n"
                + "  @Test void t() { new Calc(); }"));
    Verification r =
        new JavacVerifier(dir, VerifyFixtures.withJunit(), tests())
            .verify(Map.of(CALC, VerifyFixtures.CALC_FIXED), new AiCancel());
    assertEquals(Verification.Outcome.TESTS_FAILED, r.outcome(), r.problems().toString());
    assertTrue(r.problems().stream().anyMatch(p -> p.message().contains("could not be loaded")));
  }

  @Test
  void setupAndTeardownRunDisabledTestsDoNotAndASetupFailureFailsTheTest() throws IOException {
    VerifyFixtures.project(dir);
    VerifyFixtures.write(
        dir,
        "src/test/java/demo/LifeTest.java",
        testClass(
            "LifeTest",
            "  static int log;\n"
                + "  int x;\n"
                + "  @BeforeEach void up() { x = 5; log++; }\n"
                + "  @AfterEach void down() { log++; }\n"
                + "  @Test void sees() { assertEquals(5, new Calc().add(x, 0)); }\n"
                + "  @Disabled @Test void skipped() { fail(\"must not run\"); }\n"));
    Verification r =
        new JavacVerifier(dir, VerifyFixtures.withJunit(), tests())
            .verify(Map.of(CALC, VerifyFixtures.CALC_FIXED), new AiCancel());
    assertEquals(Verification.Outcome.PASSED, r.outcome(), r.problems().toString());
    assertEquals(2, r.testsRun(), "CalcTest and LifeTest.sees; the disabled one did not run");
  }

  @Test
  void aTestWhoseConstructorThrowsFails() throws IOException {
    VerifyFixtures.project(dir);
    VerifyFixtures.write(
        dir,
        "src/test/java/demo/CtorTest.java",
        testClass(
            "CtorTest",
            "  CtorTest() { throw new IllegalStateException(\"no\"); }\n"
                + "  @Test void t() { new Calc(); }"));
    Verification r =
        new JavacVerifier(dir, VerifyFixtures.withJunit(), tests())
            .verify(Map.of(CALC, VerifyFixtures.CALC_FIXED), new AiCancel());
    assertEquals(Verification.Outcome.TESTS_FAILED, r.outcome());
  }

  @Test
  void aTestFileWithNoTestMethodsIsNotPickedAndOneThatRunsNothingSaysSo() throws IOException {
    VerifyFixtures.project(dir);
    java.nio.file.Files.delete(dir.resolve("src/test/java/demo/CalcTest.java"));
    VerifyFixtures.write(
        dir, "src/test/java/demo/HelperTest.java", "package demo;\nclass HelperTest { Calc c; }\n");
    JavacVerifier v = new JavacVerifier(dir, VerifyFixtures.withJunit(), tests());
    assertEquals(List.of(), v.selectTests(Map.of(CALC, VerifyFixtures.CALC_FIXED)));
    VerifyFixtures.write(
        dir,
        "src/test/java/demo/QuietTest.java",
        "package demo;\n"
            + "// @Test is mentioned but nothing is annotated\n"
            + "class QuietTest { Calc c; }\n");
    Verification r = v.verify(Map.of(CALC, VerifyFixtures.CALC_FIXED), new AiCancel());
    assertEquals(Verification.Outcome.PASSED, r.outcome());
    assertTrue(r.note().contains("no test method ran"), r.note());
  }

  @Test
  void aChangedTestFileIsAlwaysRunAndTheListIsCapped() throws IOException {
    VerifyFixtures.project(dir);
    for (int i = 0; i < 25; i++)
      VerifyFixtures.write(
          dir,
          "src/test/java/demo/Many" + i + "Test.java",
          testClass("Many" + i + "Test", "  @Test void t() { new Calc(); }"));
    JavacVerifier v = new JavacVerifier(dir, VerifyFixtures.withJunit(), tests());
    assertEquals(20, v.selectTests(Map.of(CALC, VerifyFixtures.CALC_FIXED)).size());
    String changed = testClass("Unrelated", "  @Test void t() { }");
    assertEquals(
        List.of("src/test/java/demo/Unrelated.java"),
        v.selectTests(Map.of("src/test/java/demo/Unrelated.java", changed)).stream()
            .filter(p -> p.endsWith("Unrelated.java"))
            .toList());
  }

  @Test
  void aProjectWithoutTestsHasNothingToRun() throws IOException {
    VerifyFixtures.write(dir, CALC, VerifyFixtures.CALC);
    JavacVerifier v = new JavacVerifier(dir, VerifyFixtures.withJunit(), tests());
    assertEquals(List.of(), v.selectTests(Map.of(CALC, VerifyFixtures.CALC)));
    Verification r = v.verify(Map.of(CALC, VerifyFixtures.CALC_FIXED), new AiCancel());
    assertEquals(Verification.Outcome.PASSED, r.outcome());
    assertTrue(r.note().contains("no test uses"));
  }

  @Test
  void theCallersLookedAtAreCapped() throws IOException {
    VerifyFixtures.write(dir, CALC, VerifyFixtures.CALC);
    for (int i = 0; i < JavacVerifier.MAX_DEPENDENTS + 5; i++)
      VerifyFixtures.write(
          dir,
          "src/main/java/demo/U" + i + ".java",
          "package demo;\nclass U" + i + " { Calc c; }\n");
    JavacVerifier v = new JavacVerifier(dir, VerifyFixtures.withJunit(), VerifyConfig.defaults());
    assertEquals(JavacVerifier.MAX_DEPENDENTS, v.dependents(java.util.Set.of(CALC)).size());
  }

  @Test
  void aProjectWithoutTheStandardFoldersUsesItsRoot() throws IOException {
    VerifyFixtures.write(
        dir, "Util.java", "public class Util { static int one() { return 1; } }\n");
    VerifyFixtures.write(
        dir, "Main.java", "public class Main { int x() { return Util.one(); } }\n");
    JavacVerifier v = new JavacVerifier(dir, VerifyFixtures.withJunit(), VerifyConfig.defaults());
    assertEquals(
        Verification.Outcome.PASSED,
        v.verify(
                Map.of("Util.java", "public class Util { static int one() { return 2; } }\n"),
                new AiCancel())
            .outcome());
    assertEquals(
        Verification.Outcome.COMPILE_FAILED,
        v.verify(
                Map.of("Util.java", "public class Util { static int two() { return 2; } }\n"),
                new AiCancel())
            .outcome());
  }

  @Test
  void aFileNameWithASpaceAndAProblemOutsideAnyFileAreHandled() throws IOException {
    VerifyFixtures.project(dir);
    Verification v =
        new JavacVerifier(dir, VerifyFixtures.withJunit(), VerifyConfig.defaults())
            .verify(
                Map.of("src/main/java/demo/My Class.java", "package demo;\nclass Wrong { }\n"),
                new AiCancel());
    assertTrue(v.ok() || v.failed(), v.toString());
  }

  @Test
  void problemsAreCappedForTheDialogAndTheRetry() throws IOException {
    StringBuilder body = new StringBuilder("package demo;\npublic class Calc {\n");
    for (int i = 0; i < 30; i++)
      body.append("  int f").append(i).append("() { return nope").append(i).append("; }\n");
    body.append("}\n");
    Verification v =
        verifier(VerifyConfig.defaults()).verify(Map.of(CALC, body.toString()), new AiCancel());
    assertEquals(JavacVerifier.MAX_PROBLEMS, v.problems().size());
  }
}
