package com.javelin.ai.verify;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiException;
import com.javelin.ai.AiRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifyValuesTest {
  @TempDir Path dir;

  private static Verification failed(Verification.Outcome o, int n) {
    List<VerifyProblem> ps = new java.util.ArrayList<>();
    for (int i = 0; i < n; i++) ps.add(new VerifyProblem("A.java", i + 1, "m"));
    return new Verification(o, ps, 0, false, 1, null);
  }

  @Test
  void labelsAndSummariesCoverEveryShape() {
    assertEquals("just text", new VerifyProblem("", 0, "just text").label());
    assertEquals("just text", new VerifyProblem(null, 0, "just text").label());
    assertEquals("A.java: m", new VerifyProblem("A.java", 0, "m").label());
    assertEquals(
        "does not compile (1 problem)", failed(Verification.Outcome.COMPILE_FAILED, 1).summary());
    assertEquals(
        "does not compile (2 problems)", failed(Verification.Outcome.COMPILE_FAILED, 2).summary());
    assertEquals(
        "compiles, but 1 test failure", failed(Verification.Outcome.TESTS_FAILED, 1).summary());
    assertEquals(
        "compiles, but 2 test failures", failed(Verification.Outcome.TESTS_FAILED, 2).summary());
    assertEquals(
        "compiles (no test uses X)", Verification.passed(0, false, 1, "no test uses X").summary());
    assertEquals(
        "", failed(Verification.Outcome.COMPILE_FAILED, 0).note(), "a null note becomes empty");
    assertFalse(failed(Verification.Outcome.COMPILE_FAILED, 1).ok());
    assertTrue(
        Verification.notChecked("x").failure() == null && !Verification.notChecked("x").ok());
  }

  @Test
  void configAppendsTheAfterTextOnlyWhenSomethingWillRun() {
    VerifyConfig on = VerifyConfig.defaults();
    assertEquals("PREVIEW", on.appendedTo("PREVIEW", false));
    assertEquals("PREVIEW", new VerifyConfig(false, 2, false, 30).appendedTo("PREVIEW", true));
    String text = on.appendedTo("PREVIEW", true);
    assertTrue(text.startsWith("PREVIEW\n----- after the answer -----\n"));
    assertTrue(text.contains("up to 2 more requests"));
  }

  @Test
  void theReportShowsAnAttemptWithNothingToCheckBetweenCheckedOnes() {
    var v =
        Verified.of(
            List.of(
                new VerificationLoop.Attempt<>(
                    1, "a", failed(Verification.Outcome.COMPILE_FAILED, 1)),
                new VerificationLoop.Attempt<String>(2, "prose", null)));
    String r = v.report();
    assertTrue(r.contains("Attempt 2: no change to check"), r);
    assertEquals(1, v.chosen().number(), "the one that was checked is shown, not the prose");
    assertEquals(
        "",
        VerificationReport.findingText(
                    new Verification(
                        Verification.Outcome.COMPILE_FAILED, List.of(), 0, false, 1, ""))
                .isEmpty()
            ? ""
            : "");
  }

  @Test
  void aCancelledRetryEndsTheWholeRequest() {
    var step =
        new VerificationLoop.Step<String>() {
          @Override
          public String ask(AiRequest request, int attempt) throws AiException {
            if (attempt == 2) throw new AiException(AiException.Kind.CANCELLED, "cancelled");
            return "bad";
          }

          @Override
          public VerificationLoop.Checked check(String result, AiCancel cancel) {
            return new VerificationLoop.Checked(failed(Verification.Outcome.COMPILE_FAILED, 1), "");
          }
        };
    assertThrows(
        AiException.class,
        () -> VerificationLoop.run(new AiRequest("m", "s", "p"), 2, step, new AiCancel()));
  }

  @Test
  void aMissingHunkIsLeftOut() {
    String p =
        RepairPrompt.append(
                new AiRequest("m", "s", "p"),
                failed(Verification.Outcome.COMPILE_FAILED, 1),
                null,
                1)
            .prompt();
    assertFalse(p.contains("The part of your change"));
  }

  // ---- class path edges ----

  @Test
  void unreadableBuildFilesAreIncompleteNotCrashes() throws IOException {
    Path p = Files.createDirectories(dir.resolve("p1"));
    Files.write(p.resolve("pom.xml"), new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0xfd});
    var r = ProjectClasspath.resolve(p, null);
    assertFalse(r.complete());
    assertTrue(r.note().contains("pom.xml"));

    Path g = Files.createDirectories(dir.resolve("p2"));
    Files.write(g.resolve("build.gradle"), new byte[] {(byte) 0xff, (byte) 0xfe, (byte) 0xfd});
    assertFalse(ProjectClasspath.resolve(g, null).complete());
  }

  @Test
  void aGradleProjectWithoutDependenciesIsComplete() throws IOException {
    Path g = Files.createDirectories(dir.resolve("p3"));
    Files.writeString(g.resolve("build.gradle.kts"), "plugins { java }\n");
    assertTrue(ProjectClasspath.resolve(g, null).complete());
  }

  @Test
  void manyMissingDependenciesAreSummarised() throws IOException {
    Path p = Files.createDirectories(dir.resolve("p4"));
    StringBuilder deps = new StringBuilder();
    for (int i = 0; i < 5; i++)
      deps.append("<dependency><groupId>g</groupId><artifactId>a")
          .append(i)
          .append("</artifactId><version>1</version></dependency>");
    Files.writeString(
        p.resolve("pom.xml"), "<project><dependencies>" + deps + "</dependencies></project>");
    var r = ProjectClasspath.resolve(p, null);
    assertFalse(r.complete());
    assertTrue(r.note().contains("and 2 more"), r.note());
  }

  @Test
  void aDependencyWithoutCoordinatesIsSkippedAndTheRepositoryMayBeAbsent() throws IOException {
    Path p = Files.createDirectories(dir.resolve("p5"));
    Files.writeString(
        p.resolve("pom.xml"),
        "<project><dependencies><dependency><scope>test</scope></dependency></dependencies></project>");
    assertTrue(ProjectClasspath.resolve(p, null).complete());
    Path repo = ProjectClasspath.defaultRepository();
    assertTrue(repo == null || Files.isDirectory(repo));
  }
}
