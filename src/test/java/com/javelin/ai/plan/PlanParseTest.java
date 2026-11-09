package com.javelin.ai.plan;

import static org.junit.jupiter.api.Assertions.*;

import com.javelin.ai.AiException;
import com.javelin.ai.FailureReason;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanParseTest {
  private static TaskPlan reply(String text) throws AiException {
    return PlanParser.parseReply("req", text, false);
  }

  private static FailureReason reasonOf(String text, boolean truncated) {
    AiException ex =
        assertThrows(AiException.class, () -> PlanParser.parseReply("req", text, truncated));
    return ex.reason();
  }

  @Test
  void aLinePerStepIsRead() throws AiException {
    TaskPlan plan =
        reply(
            "1. src/A.java | add | make add take a third parameter\n"
                + "2. src/B.java | three | pass the third argument\n");
    assertEquals(2, plan.steps().size());
    assertEquals(
        new PlanStep("src/A.java", "add", false, "make add take a third parameter"),
        plan.steps().get(0));
    assertEquals("src/B.java", plan.steps().get(1).file());
    assertEquals("req", plan.instruction());
  }

  @Test
  void numbersBulletsAndBackticksAreUnderstoodAndProseIsSkipped() throws AiException {
    TaskPlan plan =
        reply(
            "Here is the plan:\n"
                + "- `src/A.java` | add | one\n"
                + "2) ./src/B.java | three | two\n"
                + "3: src\\C.java | go | three\n"
                + "That is all.\n");
    assertEquals(
        List.of("src/A.java", "src/B.java", "src/C.java"),
        plan.steps().stream().map(PlanStep::file).toList());
  }

  @Test
  void newMarksWhatAStepCreates() throws AiException {
    TaskPlan plan =
        reply(
            "src/A.java | new multiply | add multiply\n"
                + "src/Fresh.java | new | create the class\n"
                + "src/B.java | go (new) | add go\n"
                + "src/C.java | New |  create C\n");
    assertEquals(new PlanStep("src/A.java", "multiply", true, "add multiply"), plan.steps().get(0));
    assertTrue(plan.steps().get(1).isNew());
    assertEquals("", plan.steps().get(1).symbol());
    assertEquals(new PlanStep("src/B.java", "go", true, "add go"), plan.steps().get(2));
    assertTrue(plan.steps().get(3).isNew());
  }

  @Test
  void aMethodWrittenWithItsClassOrParametersIsJustItsName() throws AiException {
    TaskPlan plan =
        reply(
            "a/A.java | Calc.add | x\n"
                + "a/A.java | Calc#add | x\n"
                + "a/A.java | add(int a, int b) | x\n");
    for (PlanStep s : plan.steps()) assertEquals("add", s.symbol());
  }

  @Test
  void aTaskMayContainABar() throws AiException {
    TaskPlan plan = reply("a/A.java | add | use a | b instead of a - b\n");
    assertEquals("use a | b instead of a - b", plan.steps().get(0).task());
  }

  @Test
  void jsonStepsAreReadWithOrWithoutAFence() throws AiException {
    String json =
        "{\"steps\":[{\"file\":\"a/A.java\",\"method\":\"add\",\"new\":false,\"task\":\"t1\"},"
            + "{\"file\":\"a/B.java\",\"method\":\"go\",\"new\":true,\"task\":\"t2\"}]}";
    for (String text : List.of(json, "```json\n" + json + "\n```")) {
      TaskPlan plan = reply(text);
      assertEquals(2, plan.steps().size());
      assertFalse(plan.steps().get(0).isNew());
      assertTrue(plan.steps().get(1).isNew());
      assertEquals("go", plan.steps().get(1).symbol());
    }
  }

  @Test
  void aReasoningBlockIsNotThePlan() throws AiException {
    TaskPlan plan = reply("<think>a/X.java | no | not this</think>\na/A.java | add | yes\n");
    assertEquals(1, plan.steps().size());
    assertEquals("a/A.java", plan.steps().get(0).file());
  }

  @Test
  void aReplyThatIsCutOffIsNeverAShorterPlan() {
    assertEquals(
        FailureReason.TRUNCATED, reasonOf("a/A.java | add | one\na/B.java | go | tw", true));
  }

  @Test
  void aReplyWithNoStepsHasNoPlan() {
    assertEquals(FailureReason.NO_EDIT_BLOCK, reasonOf("I would change the add method.", false));
    assertEquals(FailureReason.NO_EDIT_BLOCK, reasonOf("{\"steps\":[]}", false));
    assertEquals(FailureReason.NO_EDIT_BLOCK, reasonOf("", false));
  }

  @Test
  void brokenJsonIsUnparseable() {
    assertEquals(FailureReason.UNPARSEABLE, reasonOf("{\"steps\":[{\"file\":", false));
    assertEquals(FailureReason.UNPARSEABLE, reasonOf("{\"other\":1}", false));
    assertEquals(FailureReason.UNPARSEABLE, reasonOf("{\"steps\":[1,2]}", false));
  }

  @Test
  void theEditedTextIsReadStrictly() throws AiException {
    TaskPlan plan =
        PlanParser.parseText(
            "req", "\n# a comment\n1. a/A.java | add | one\n\n2. a/B.java | new go | two\n");
    assertEquals(2, plan.steps().size());
    AiException ex =
        assertThrows(AiException.class, () -> PlanParser.parseText("req", "a/A.java add one\n"));
    assertTrue(ex.getMessage().contains("Line 1"), ex.getMessage());
    assertEquals(FailureReason.UNPARSEABLE, ex.reason());
  }

  @Test
  void aPlanSurvivesBeingShownAndEdited() throws AiException {
    TaskPlan plan =
        reply("a/A.java | add | one\n" + "a/B.java | new go | two\n" + "a/C.java | new | three\n");
    assertEquals(plan, PlanParser.parseText("req", plan.text()));
  }

  @Test
  void stepsCanBeRemovedAndReordered() throws AiException {
    TaskPlan plan = reply("a/A.java | a | 1\na/B.java | b | 2\na/C.java | c | 3\n");
    assertEquals(
        List.of("b", "c"), plan.without(0).steps().stream().map(PlanStep::symbol).toList());
    assertEquals(
        List.of("c", "a", "b"), plan.moved(2, 0).steps().stream().map(PlanStep::symbol).toList());
    assertEquals(3, plan.steps().size(), "the plan itself is not changed");
  }

  @Test
  void stepsLabelThemselvesForMessages() {
    assertEquals("Calc.java#add", new PlanStep("src/Calc.java", "add", false, "t").label());
    assertEquals("Calc.java#go (new)", new PlanStep("src/Calc.java", "go", true, "t").label());
    assertEquals("Fresh.java (new)", new PlanStep("src/Fresh.java", "", true, "t").label());
  }
}
