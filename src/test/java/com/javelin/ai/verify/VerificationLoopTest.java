package com.javelin.ai.verify;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.ai.AiCancel;
import com.javelin.ai.AiException;
import com.javelin.ai.AiRequest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class VerificationLoopTest {
  private static final AiRequest FIRST = new AiRequest("m", "system", "the task");

  private static Verification compileFailed(String message) {
    return new Verification(
        Verification.Outcome.COMPILE_FAILED,
        List.of(new VerifyProblem("A.java", 3, message)),
        0,
        false,
        1,
        "");
  }

  /** Answers "bad" the first {@code badFor} times, "good" afterwards. */
  private static final class Scripted implements VerificationLoop.Step<String> {
    final int badFor;
    final List<AiRequest> asked = new ArrayList<>();
    final List<Integer> retryFailures = new ArrayList<>();
    int failAt = -1;

    Scripted(int badFor) {
      this.badFor = badFor;
    }

    @Override
    public String ask(AiRequest request, int attempt) throws AiException {
      asked.add(request);
      if (attempt == failAt) throw new AiException(AiException.Kind.TIMEOUT, "slow");
      return attempt <= badFor ? "bad" : "good";
    }

    @Override
    public void retryFailed(int attempt, AiException why) {
      retryFailures.add(attempt);
    }

    @Override
    public VerificationLoop.Checked check(String result, AiCancel cancel) {
      return new VerificationLoop.Checked(
          "bad".equals(result)
              ? compileFailed("cannot find symbol")
              : Verification.passed(0, false, 1, ""),
          "hunk");
    }
  }

  @Test
  void aGoodFirstAnswerIsOneRequest() throws AiException {
    Scripted step = new Scripted(0);
    var attempts = VerificationLoop.run(FIRST, 2, step, new AiCancel());
    assertEquals(1, attempts.size());
    assertSame(FIRST, step.asked.get(0));
  }

  @Test
  void aBrokenAnswerIsRepairedWithTheProblemsAndStops() throws AiException {
    Scripted step = new Scripted(1);
    var attempts = VerificationLoop.run(FIRST, 2, step, new AiCancel());
    assertEquals(2, attempts.size());
    assertTrue(attempts.get(1).verification().ok());
    String second = step.asked.get(1).prompt();
    assertTrue(second.startsWith("the task"), "the original request is sent again");
    assertTrue(second.contains("A.java:3: cannot find symbol"));
  }

  @Test
  void theNumberOfRequestsNeverExceedsTheBudgetPlusOne() throws AiException {
    for (int retries = 0; retries <= 5; retries++) {
      Scripted step = new Scripted(Integer.MAX_VALUE);
      var attempts = VerificationLoop.run(FIRST, retries, step, new AiCancel());
      assertEquals(retries + 1, attempts.size(), "retries=" + retries);
      assertEquals(retries + 1, step.asked.size());
    }
  }

  @Test
  void aNegativeBudgetStillAsksOnce() throws AiException {
    Scripted step = new Scripted(Integer.MAX_VALUE);
    assertEquals(1, VerificationLoop.run(FIRST, -3, step, new AiCancel()).size());
  }

  @Test
  void anAnswerWithNothingToCheckEndsTheLoop() throws AiException {
    var step =
        new VerificationLoop.Step<String>() {
          int asked;

          @Override
          public String ask(AiRequest request, int attempt) {
            asked++;
            return "prose";
          }

          @Override
          public VerificationLoop.Checked check(String result, AiCancel cancel) {
            return null;
          }
        };
    var attempts = VerificationLoop.run(FIRST, 2, step, new AiCancel());
    assertEquals(1, attempts.size());
    assertNull(attempts.get(0).verification());
  }

  @Test
  void aCheckThatCouldNotLookDoesNotCostARetry() throws AiException {
    var step =
        new VerificationLoop.Step<String>() {
          int asked;

          @Override
          public String ask(AiRequest request, int attempt) {
            asked++;
            return "x";
          }

          @Override
          public VerificationLoop.Checked check(String result, AiCancel cancel) {
            return new VerificationLoop.Checked(Verification.notChecked("no compiler"), "");
          }
        };
    assertEquals(1, VerificationLoop.run(FIRST, 2, step, new AiCancel()).size());
    assertEquals(1, step.asked);
  }

  @Test
  void aRepeatThatFailsKeepsTheEarlierAttempts() throws AiException {
    Scripted step = new Scripted(5);
    step.failAt = 2;
    var attempts = VerificationLoop.run(FIRST, 2, step, new AiCancel());
    assertEquals(1, attempts.size());
    assertEquals(List.of(2), step.retryFailures);
  }

  @Test
  void aFailureOfTheFirstRequestIsNotSwallowed() {
    Scripted step = new Scripted(0);
    step.failAt = 1;
    assertThrows(AiException.class, () -> VerificationLoop.run(FIRST, 2, step, new AiCancel()));
  }

  @Test
  void cancellingStopsBeforeTheNextRequest() {
    AiCancel cancel = new AiCancel();
    cancel.cancel();
    Scripted step = new Scripted(0);
    AiException ex =
        assertThrows(AiException.class, () -> VerificationLoop.run(FIRST, 2, step, cancel));
    assertEquals(AiException.Kind.CANCELLED, ex.kind());
    assertTrue(step.asked.isEmpty());
  }

  @Test
  void verifiedPrefersAPassingAttemptThenTheLastCheckedOne() {
    var bad = new VerificationLoop.Attempt<>(1, "bad", compileFailed("x"));
    var good = new VerificationLoop.Attempt<>(2, "good", Verification.passed(0, false, 1, ""));
    var bad2 = new VerificationLoop.Attempt<>(3, "bad2", compileFailed("y"));
    var none = new VerificationLoop.Attempt<String>(4, "prose", null);
    assertEquals("good", Verified.of(List.of(bad, good, bad2)).answer());
    assertEquals("bad2", Verified.of(List.of(bad, bad2, none)).answer(), "prose is not a change");
    assertEquals("prose", Verified.of(List.of(none)).answer());
  }
}
