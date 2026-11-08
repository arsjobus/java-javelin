package com.javelin.ai.verify;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.ai.AiRequest;
import com.javelin.ai.Sampling;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepairPromptTest {
  private static Verification failed(int problems, Verification.Outcome outcome) {
    List<VerifyProblem> list = new ArrayList<>();
    for (int i = 1; i <= problems; i++)
      list.add(new VerifyProblem("Foo.java", i * 10, "problem " + i));
    return new Verification(outcome, list, 0, false, 1, "");
  }

  @Test
  void theOriginalRequestIsKeptAndTheSettingsAreUnchanged() {
    AiRequest first =
        new AiRequest("m", "sys", "ORIGINAL PROMPT", 4096, Sampling.deterministic(7), "{}");
    AiRequest again =
        RepairPrompt.append(first, failed(1, Verification.Outcome.COMPILE_FAILED), "  3 | x", 1);
    assertTrue(again.prompt().startsWith("ORIGINAL PROMPT"));
    assertEquals(first.system(), again.system());
    assertEquals(first.model(), again.model());
    assertEquals(first.contextTokens(), again.contextTokens());
    assertEquals(first.sampling(), again.sampling());
    assertEquals(first.responseSchema(), again.responseSchema());
  }

  @Test
  void onlyTheFirstFewProblemsGoBackWithTheirFileAndLine() {
    AiRequest again =
        RepairPrompt.append(
            new AiRequest("m", "s", "p"), failed(8, Verification.Outcome.COMPILE_FAILED), "", 1);
    String p = again.prompt();
    assertTrue(p.contains("- Foo.java:10: problem 1"));
    assertTrue(p.contains("- Foo.java:50: problem 5"));
    assertFalse(p.contains("problem 6"));
    assertTrue(p.contains("(3 more)"));
  }

  @Test
  void testFailuresAreNamedAsSuch() {
    AiRequest again =
        RepairPrompt.append(
            new AiRequest("m", "s", "p"), failed(1, Verification.Outcome.TESTS_FAILED), "", 2);
    assertTrue(again.prompt().contains("compiled but these tests failed"));
    assertTrue(again.prompt().contains("attempt 2"));
  }

  @Test
  void theAnswerIsAskedAgainstTheOriginalCode() {
    AiRequest again =
        RepairPrompt.append(
            new AiRequest("m", "s", "p"), failed(1, Verification.Outcome.COMPILE_FAILED), "", 1);
    assertTrue(again.prompt().contains("ORIGINAL code"));
  }

  @Test
  void theHunkIsAWindowAroundTheLineNotTheWholeFile() {
    StringBuilder text = new StringBuilder();
    for (int i = 1; i <= 200; i++) text.append("line").append(i).append('\n');
    String hunk = RepairPrompt.hunkAround(text.toString(), 100);
    assertTrue(hunk.contains("line100"));
    assertFalse(hunk.contains("line1\n") && hunk.contains("line200"));
    assertTrue(hunk.lines().count() <= RepairPrompt.MAX_HUNK_LINES);
    assertTrue(hunk.contains(" 100 | line100"), "numbered so the compiler's line matches");
  }

  @Test
  void theHunkCopesWithLinesOutOfRange() {
    assertTrue(RepairPrompt.hunkAround("a\nb\n", 0).contains("| a"));
    assertTrue(RepairPrompt.hunkAround("a\nb\n", 999).contains("| a"));
    assertTrue(RepairPrompt.hunkAround("", 5).length() >= 0);
  }
}
