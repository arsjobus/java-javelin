package com.javelin.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChangeReplyTest {
    private static final String ORIGINAL = "    int add(int a, int b) {\n        return a - b;\n    }\n";

    @Test void aWholeBlockIsTheNewCode() throws Exception {
        ChangeReply.Interpreted r = ChangeReply.interpret("Fixed.\n```java\n    int add(int a, int b) {\n        return a + b;\n    }\n```", ORIGINAL);
        assertEquals(ChangeReply.Format.WHOLE_BLOCK, r.format());
        assertEquals("    int add(int a, int b) {\n        return a + b;\n    }", r.code());
        assertEquals("Fixed.", r.explanation());
        assertEquals(List.of(), r.notes());
    }

    @Test void editBlocksAreAppliedToTheOriginalWhateverThePromptAsked() throws Exception {
        ChangeReply.Interpreted r = ChangeReply.interpret("Fixed.\n<<<<<<< SEARCH\n        return a - b;\n=======\n        return a + b;\n>>>>>>> REPLACE", ORIGINAL);
        assertEquals(ChangeReply.Format.EDIT_BLOCKS, r.format());
        assertEquals(ORIGINAL.replace("a - b", "a + b"), r.code());
        assertEquals("Fixed.", r.explanation());
    }

    @Test void aDiffBlockIsAppliedAsAPatch() throws Exception {
        ChangeReply.Interpreted r = ChangeReply.interpret("```diff\n--- a/Foo.java\n+++ b/Foo.java\n@@ -1,3 +1,3 @@\n     int add(int a, int b) {\n-        return a - b;\n+        return a + b;\n     }\n```", ORIGINAL);
        assertEquals(ChangeReply.Format.DIFF, r.format());
        assertEquals(ORIGINAL.replace("a - b", "a + b"), r.code());
    }

    @Test void aPatchThatDoesNotFitIsAnErrorNotAGuess() {
        assertThrows(AiException.class, () -> ChangeReply.interpret("```diff\n@@ -1,1 +1,1 @@\n-nothing like it\n+x\n```", ORIGINAL));
    }

    @Test void aReplyWithoutCodeStillSaysThereIsNoChange() {
        AiException ex = assertThrows(AiException.class, () -> ChangeReply.interpret("I cannot do that.", ORIGINAL));
        assertTrue(ex.getMessage().contains("no code block"));
    }

    @Test void codeHandedBackWithoutItsIndentationGetsTheIndentationOfTheSelection() throws Exception {
        ChangeReply.Interpreted r = ChangeReply.interpret("```java\nint add(int a, int b) {\n    return a + b;\n}\n```", ORIGINAL);
        assertEquals("    int add(int a, int b) {\n        return a + b;\n    }", r.code());
        assertEquals(1, r.notes().size());
        assertFalse(r.notes().get(0).isError());
    }

    @Test void indentationThatTheModelChoseIsLeftAlone() throws Exception {
        String reply = "```java\n  int add(int a, int b) {\n      return a + b;\n  }\n```";
        ChangeReply.Interpreted r = ChangeReply.interpret(reply, ORIGINAL);
        assertEquals("  int add(int a, int b) {\n      return a + b;\n  }", r.code());
        assertEquals(List.of(), r.notes());
    }

    @Test void aSelectionWithoutIndentationIsNeverReindented() {
        List<Finding> notes = new ArrayList<>();
        assertEquals("int a;", ChangeReply.restoreIndentation("int b;\n", "int a;", notes));
        assertEquals(List.of(), notes);
    }

    @Test void furtherCodeBlocksAreCounted() throws Exception {
        assertEquals(1, ChangeReply.interpret("```\n" + ORIGINAL + "```\n```\nother\n```", ORIGINAL).extraBlocks());
    }

    @Test void codeTriedOutWhileThinkingIsNotTheAnswer() throws Exception {
        String reply = "<think>Maybe:\n```java\nint add(int a, int b) { return 0; }\n```\nNo.</think>\nFixed.\n```java\n    int add(int a, int b) {\n        return a + b;\n    }\n```";
        ChangeReply.Interpreted r = ChangeReply.interpret(reply, ORIGINAL);
        assertEquals("    int add(int a, int b) {\n        return a + b;\n    }", r.code());
        assertEquals("Fixed.", r.explanation());
    }

    @Test void reasoningThatNeverClosesLeavesNothingToPropose() {
        assertThrows(AiException.class, () -> ChangeReply.interpret("<think>hmm\n```java\nint a;\n```", ORIGINAL));
    }
}
