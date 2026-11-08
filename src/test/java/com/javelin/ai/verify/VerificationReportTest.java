package com.javelin.ai.verify;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerificationReportTest {
  private static Verification failed(int n) {
    List<VerifyProblem> ps = new ArrayList<>();
    for (int i = 1; i <= n; i++) ps.add(new VerifyProblem("A.java", i, "m" + i));
    return new Verification(Verification.Outcome.COMPILE_FAILED, ps, 0, false, 1, "");
  }

  @Test
  void nothingCheckedSaysNothing() {
    var v = Verified.of(List.of(new VerificationLoop.Attempt<>(1, "x", null)));
    assertEquals("", v.report());
  }

  @Test
  void eachAttemptGetsALineAndLongListsAreCut() {
    var v =
        Verified.of(
            List.of(
                new VerificationLoop.Attempt<>(1, "a", failed(5)),
                new VerificationLoop.Attempt<>(2, "b", Verification.passed(0, false, 1, ""))));
    String r = v.report();
    assertTrue(r.contains("Attempt 1: does not compile (5 problems)"));
    assertTrue(r.contains("A.java:1: m1") && r.contains("A.java:3: m3"));
    assertFalse(r.contains("m4"));
    assertTrue(r.contains("(2 more)"));
    assertTrue(r.contains("Attempt 2: compiles"));
    assertTrue(r.contains("The change shown is attempt 2."));
  }

  @Test
  void aSingleAttemptDoesNotSayWhichIsShown() {
    var v =
        Verified.of(
            List.of(new VerificationLoop.Attempt<>(1, "a", Verification.passed(3, true, 1, ""))));
    assertTrue(v.report().contains("compiles; 3 test(s) passed"));
    assertFalse(v.report().contains("shown"));
  }

  @Test
  void theFindingNamesTheFirstProblem() {
    assertTrue(VerificationReport.findingText(failed(2)).contains("A.java:1: m1"));
    assertTrue(
        VerificationReport.findingText(
                new Verification(Verification.Outcome.TESTS_FAILED, List.of(), 0, true, 1, ""))
            .contains("tests fail"));
  }

  @Test
  void summariesAreHonestAboutWhatWasNotDone() {
    assertEquals("not checked: no compiler", Verification.notChecked("no compiler").summary());
    assertEquals("compiles", Verification.passed(0, false, 1, "").summary());
  }
}
